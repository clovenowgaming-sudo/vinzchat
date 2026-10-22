const { execFile } = require('child_process');

const TEXT = process.env.SMS_TEXT || 'otp:{code} JANGAN DIBERITAHU SIAPAPUN';
const esc = s => JSON.stringify(String(s)).slice(1, -1);
const fill = (tpl, v, enc) => tpl.replace(/\{(\w+)\}/g, (_, k) => enc(v[k] ?? ''));

function viaTermux(phone, text) {
  return new Promise((ok, no) =>
    execFile('termux-sms-send', ['-n', phone, text], { timeout: 20000 },
      e => (e ? no(new Error('termux-sms-send gagal (kode ' + (e.code ?? '?') + ')')) : ok())));
}

async function viaHttp(phone, text) {
  const v = { to: phone, to_plain: phone.replace('+', ''), text };
  const url = fill(process.env.SMS_HTTP_URL, v, encodeURIComponent);
  const method = process.env.SMS_HTTP_METHOD || 'POST';
  const opt = { method, headers: JSON.parse(process.env.SMS_HTTP_HEADERS || '{}'), signal: AbortSignal.timeout(10000) };
  if (method !== 'GET' && process.env.SMS_HTTP_BODY) opt.body = fill(process.env.SMS_HTTP_BODY, v, esc);
  const r = await fetch(url, opt);
  if (!r.ok) throw new Error('Gateway SMS membalas ' + r.status);
}

exports.sendOtpSms = async function (phone, code) {
  const text = fill(TEXT, { code }, String);
  const order = (process.env.SMS_MODE || 'http,termux').split(',').map(s => s.trim());
  let last = null;
  for (const m of order) {
    try {
      if (m === 'http') await viaHttp(phone, text);
      else if (m === 'termux') await viaTermux(phone, text);
      else continue;
      console.log('[SMS] terkirim via', m);
      return;
    } catch (e) {
      last = e;
      console.error('[SMS]', m, 'gagal:', e.message);
    }
  }
  throw last || new Error('Tidak ada pengirim SMS');
};
