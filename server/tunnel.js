const ngrok = require('ngrok');
(async () => {
  try {
    const url = await ngrok.connect({
      addr: process.env.PORT || 3000,
      authtoken: process.env.NGROK_AUTHTOKEN,
      domain: process.env.NGROK_DOMAIN,
    });
    console.log('Tunnel aktif:', url);
  } catch (e) {
    console.error('Tunnel gagal:', e.message || e);
  }
})();
