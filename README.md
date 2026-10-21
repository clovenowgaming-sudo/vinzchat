# Vinz Chat

Chat server mini, daftar pakai nomor HP + OTP 6 digit. Server Node.js, klien web, APK WebView.

## Jalankan server (Termux)
    pkg install nodejs cloudflared
    cd server && npm install && cp .env.example .env
    npm start
    cloudflared tunnel --url http://localhost:3000   # dapat URL https publik

## OTP gratis (tanpa kartu kamu)
Daftar di messagecentral.com (VerifyNow, kredit gratis tanpa kartu kredit), isi di `.env`:
    OTP_PROVIDER=messagecentral
    MC_CUSTOMER_ID=...  MC_PASSWORD=...  MC_EMAIL=...
Selama `OTP_PROVIDER=dev`, kode OTP cuma dicetak di console server (buat testing).

## APK
Buka APK, isi URL tunnel saat diminta (atau set repo variable SERVER_URL sebelum build).
