const TEXT = process.env.SMS_TEXT || 'otp:{code} JANGAN DIBERITAHU SIAPAPUN';
const esc = s => JSON.stringify(String(s)).slice(1, -1);
const fill = (tpl, v, enc) => tpl.replace(/\{(\w+)\}/g, (_, k) => enc(v[k] ?? ''));

exports.sendOtpSms = async function (phone, code) {
  const text = fill(TEXT, { code }, String);
  const v = { to: phone, to_plain: phone.replace('+', ''), text };
  const url = fill(process.env.SMS_HTTP_URL, v, encodeURIComponent);
  const method = process.env.SMS_HTTP_METHOD || 'POST';
  const opt = { method, headers: JSON.parse(process.env.SMS_HTTP_HEADERS || '{}'), signal: AbortSignal.timeout(10000) };
  if (method !== 'GET' && process.env.SMS_HTTP_BODY) opt.body = fill(process.env.SMS_HTTP_BODY, v, esc);
  const r = await fetch(url, opt);
  if (!r.ok) throw new Error('Gateway SMS membalas ' + r.status);
  console.log('[SMS] terkirim');
};
