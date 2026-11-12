# 📊 Nova Trader v2.0 (Android)

Quotex auto-trading bot ka **Android APK** — mobile par login aur monitoring aasan!

## v2.0 Features
- 🔑 Login screen (email/password, yaad rakhta hai) — DNS fail par VPN hint
- 🌐 **Connection fix**: Cronet (Chrome TLS) + DNS-over-HTTPS fallback — ISP block ka tor
- 🥷 **Stealth**: browser-identical headers, human-like pauses, random skips, generic package
- 💰 **Balance card**: DEMO + REAL dono, app khulne par
- 📈 **💰 Profit Dashboard**: Session P/L (green/red), Win Rate %, Jeet/Haar/Trades
- 📋 **Live pairs**: Quotex se live asset list (forex + OTC 24/7 + crypto)
- 🎯 **Strong signals**: RSI zone + momentum filters (toggle)
- ⚠️ **Martingale**: default OFF + warning (ON ho to max 2 steps)
- ▶️ Big START / ⏹ STOP, 📜 live log, 📊 colored trade history
- ⚠️ REAL mode sakht warning (default: DEMO)

## Warnings
- **Pehle DEMO par test karo!** Profit ki koi guarantee NAHI.
- Quotex bots ko BAN kar sakta hai. Martingale RISKY hai.

## Technical
- Native Java, **no WebView**
- Dual transport: `CronetTransport` (Chromium stack) → `OkHttpDohTransport` (DoH DNS)
- Socket.IO EIO=3 HTTP long-polling: `login`, `depth/follow`, `orders/open`,
  `s_balance/list`, `instruments/list`
- Strategy: EMA(9/21) + RSI(14) + strong-signal filters (port of desktop bot)
- Trade result = balance difference (real P/L)
