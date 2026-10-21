'use strict';
const http = require('http'), fs = require('fs'), path = require('path'), crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = +process.env.PORT || 3000;
const PROVIDER = process.env.OTP_PROVIDER || 'dev';
const CCS = (process.env.ALLOWED_COUNTRY_CODES || '62').split(',').map(s => s.trim());
const MC_BASE = 'https://cpaas.messagecentral.com';
const MC_ID = process.env.MC_CUSTOMER_ID, MC_PW = process.env.MC_PASSWORD;
const MC_EMAIL = process.env.MC_EMAIL, MC_COUNTRY = process.env.MC_COUNTRY || '62';
const DB_FILE = path.join(__dirname, 'data.json');

// ---------- storage (JSON file, atomic write) ----------
let db = { users: {}, sessions: {}, contacts: {}, messages: [] };
try { db = Object.assign(db, JSON.parse(fs.readFileSync(DB_FILE, 'utf8'))); } catch {}
let saveT = null;
function save() {
  clearTimeout(saveT);
  saveT = setTimeout(() => {
    fs.writeFileSync(DB_FILE + '.tmp', JSON.stringify(db));
    fs.renameSync(DB_FILE + '.tmp', DB_FILE);
  }, 400);
}
const sha = s => crypto.createHash('sha256').update(s).digest('hex');

// ---------- rate limit ----------
const hits = new Map();
function hit(key, max, windowMs) {
  const now = Date.now();
  const arr = (hits.get(key) || []).filter(t => now - t < windowMs);
  if (arr.length >= max) { hits.set(key, arr); return false; }
  arr.push(now); hits.set(key, arr); return true;
}
setInterval(() => { const now = Date.now(); for (const [k, v] of hits) if (!v.some(t => now - t < 3600e3)) hits.delete(k); }, 600e3);

// ---------- phone ----------
function normPhone(p) {
  p = String(p || '').replace(/[\s\-()]/g, '');
  if (p.startsWith('08')) p = '+62' + p.slice(1);
  else if (/^\d/.test(p)) p = '+' + p;
  if (!/^\+\d{8,15}$/.test(p)) return null;
  const cc = CCS.find(c => p.startsWith('+' + c));
  if (!cc) return null;
  return { phone: p, cc, national: p.slice(1 + cc.length) };
}

// ---------- OTP providers ----------
const pending = new Map(); // phone -> {vid, hash, expires, attempts}
let mcToken = null;
async function mcAuth() {
  const q = new URLSearchParams({ customerId: MC_ID, key: Buffer.from(MC_PW).toString('base64'), scope: 'NEW', country: MC_COUNTRY });
  if (MC_EMAIL) q.set('email', MC_EMAIL);
  const j = await (await fetch(MC_BASE + '/auth/v1/authentication/token?' + q)).json();
  if (!j.token) throw new Error('MessageCentral auth gagal');
  mcToken = j.token;
}
async function mc(method, pq) {
  if (!mcToken) await mcAuth();
  let r = await fetch(MC_BASE + pq, { method, headers: { authToken: mcToken } });
  if (r.status === 401) { await mcAuth(); r = await fetch(MC_BASE + pq, { method, headers: { authToken: mcToken } }); }
  return r.json();
}
async function sendOtp(n) {
  const entry = { expires: Date.now() + 5 * 60e3, attempts: 0 };
  if (PROVIDER === 'messagecentral') {
    const q = new URLSearchParams({ countryCode: n.cc, customerId: MC_ID, flowType: 'SMS', mobileNumber: n.national, otpLength: '6' });
    const j = await mc('POST', '/verification/v3/send?' + q);
    if (!j.data || !j.data.verificationId) throw new Error('Gagal kirim OTP');
    entry.vid = j.data.verificationId;
  } else {
    const code = crypto.randomInt(0, 1e6).toString().padStart(6, '0');
    entry.hash = sha(code);
    console.log('[DEV OTP]', n.phone, code);
  }
  pending.set(n.phone, entry);
}
async function checkOtp(n, code, entry) {
  if (PROVIDER === 'messagecentral') {
    const q = new URLSearchParams({ countryCode: n.cc, mobileNumber: n.national, verificationId: entry.vid, customerId: MC_ID, code });
    const j = await mc('GET', '/verification/v3/validateOtp?' + q);
    return !!(j.data && j.data.verificationStatus === 'VERIFICATION_COMPLETED');
  }
  return sha(code) === entry.hash;
}

// ---------- http helpers ----------
const json = (res, c, o) => { res.writeHead(c, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(o)); };
const ipOf = req => req.headers['cf-connecting-ip'] || req.socket.remoteAddress;
function body(req) {
  return new Promise((ok, no) => {
    let s = '';
    req.on('data', d => { s += d; if (s.length > 10240) { no(new Error('too big')); req.destroy(); } });
    req.on('end', () => { try { ok(s ? JSON.parse(s) : {}); } catch { no(new Error('bad json')); } });
  });
}
function authPhone(req, url) {
  const h = req.headers.authorization || '';
  const t = h.startsWith('Bearer ') ? h.slice(7) : url.searchParams.get('token');
  return t ? db.sessions[sha(t)] : null;
}

// ---------- server ----------
const sockets = new Map(); // phone -> Set<ws>
const INDEX = path.join(__dirname, 'public', 'index.html');

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  try {
    if (req.method === 'GET' && url.pathname === '/') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      return res.end(fs.readFileSync(INDEX));
    }
    if (!url.pathname.startsWith('/api/')) return json(res, 404, { error: 'not found' });
    const ip = ipOf(req);

    if (req.method === 'POST' && url.pathname === '/api/register') {
      const n = normPhone((await body(req)).phone);
      if (!n) return json(res, 400, { error: 'Nomor tidak valid / negara tidak didukung' });
      if (!hit('ip:' + ip, 10, 3600e3) || !hit('ph:' + n.phone, 3, 3600e3))
        return json(res, 429, { error: 'Terlalu banyak permintaan, coba lagi nanti' });
      await sendOtp(n);
      return json(res, 200, { ok: true });
    }

    if (req.method === 'POST' && url.pathname === '/api/verify') {
      const b = await body(req);
      const n = normPhone(b.phone);
      const code = String(b.code || '');
      if (!n || !/^\d{6}$/.test(code)) return json(res, 400, { error: 'Data tidak valid' });
      const e = pending.get(n.phone);
      if (!e || e.expires < Date.now()) return json(res, 400, { error: 'OTP kedaluwarsa, minta ulang' });
      if (++e.attempts > 5) { pending.delete(n.phone); return json(res, 429, { error: 'Terlalu banyak percobaan' }); }
      if (!(await checkOtp(n, code, e))) return json(res, 400, { error: 'Kode salah' });
      pending.delete(n.phone);
      if (!db.users[n.phone]) { db.users[n.phone] = { createdAt: Date.now() }; db.contacts[n.phone] = []; }
      const token = crypto.randomBytes(32).toString('hex');
      db.sessions[sha(token)] = n.phone;
      save();
      return json(res, 200, { token, phone: n.phone });
    }

    const me = authPhone(req, url);
    if (!me) return json(res, 401, { error: 'Belum login' });

    if (req.method === 'GET' && url.pathname === '/api/contacts')
      return json(res, 200, { contacts: db.contacts[me] || [] });

    if (req.method === 'POST' && url.pathname === '/api/contacts') {
      if (!hit('add:' + me, 20, 3600e3)) return json(res, 429, { error: 'Terlalu banyak percobaan' });
      const n = normPhone((await body(req)).phone);
      if (!n || n.phone === me) return json(res, 400, { error: 'Nomor tidak valid' });
      if (!db.users[n.phone]) return json(res, 404, { error: 'Nomor belum terdaftar' });
      const list = db.contacts[me] = db.contacts[me] || [];
      if (!list.includes(n.phone)) list.push(n.phone);
      save();
      return json(res, 200, { phone: n.phone });
    }

    if (req.method === 'GET' && url.pathname === '/api/history') {
      const w = url.searchParams.get('with');
      const m = db.messages.filter(x => (x.from === me && x.to === w) || (x.from === w && x.to === me)).slice(-200);
      return json(res, 200, { messages: m });
    }

    if (req.method === 'POST' && url.pathname === '/api/logout') {
      const h = req.headers.authorization || '';
      delete db.sessions[sha(h.slice(7))]; save();
      return json(res, 200, { ok: true });
    }
    json(res, 404, { error: 'not found' });
  } catch (e) {
    console.error(e.message);
    json(res, 500, { error: 'Server error' });
  }
});

// ---------- websocket ----------
const wss = new WebSocketServer({ noServer: true, maxPayload: 8192 });
server.on('upgrade', (req, sock, head) => {
  const url = new URL(req.url, 'http://x');
  const me = url.pathname === '/ws' && authPhone(req, url);
  if (!me) { sock.write('HTTP/1.1 401 Unauthorized\r\n\r\n'); return sock.destroy(); }
  wss.handleUpgrade(req, sock, head, ws => { ws.me = me; wss.emit('connection', ws); });
});
function push(phone, obj) {
  const s = sockets.get(phone);
  if (s) for (const w of s) if (w.readyState === 1) w.send(JSON.stringify(obj));
}
wss.on('connection', ws => {
  const me = ws.me;
  if (!sockets.has(me)) sockets.set(me, new Set());
  sockets.get(me).add(ws);
  ws.on('close', () => sockets.get(me).delete(ws));
  ws.on('message', raw => {
    try {
      const m = JSON.parse(raw);
      const to = normPhone(m.to);
      const text = String(m.text || '').slice(0, 2000).trim();
      if (!to || !text || !db.users[to.phone]) return;
      if (!hit('msg:' + me, 30, 60e3)) return ws.send(JSON.stringify({ type: 'err', error: 'Pelan-pelan' }));
      const msg = { type: 'msg', id: crypto.randomUUID(), from: me, to: to.phone, text, ts: Date.now() };
      db.messages.push(msg);
      if (db.messages.length > 5000) db.messages.splice(0, db.messages.length - 5000);
      const c = db.contacts[to.phone] = db.contacts[to.phone] || [];
      if (!c.includes(me)) c.push(me);
      save();
      push(me, msg); push(to.phone, msg);
    } catch {}
  });
});

server.listen(PORT, () => console.log(`Vinz Chat jalan di :${PORT} (OTP: ${PROVIDER})`));
