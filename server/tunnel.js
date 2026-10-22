const ngrok = require('@ngrok/ngrok');
(async () => {
  try {
    const l = await ngrok.forward({
      addr: +(process.env.PORT || 3000),
      authtoken: process.env.NGROK_AUTHTOKEN,
      domain: process.env.NGROK_DOMAIN,
    });
    console.log('Tunnel aktif:', l.url());
  } catch (e) {
    console.error('Tunnel gagal:', e.message || e);
  }
})();
