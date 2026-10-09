# 🤖 Hamza Qx Bot (Android)

Quotex auto-trading bot ka **Android APK** — mobile par login aur monitoring aasan!

## Features
- 🔑 Login screen (email/password, yaad rakhta hai)
- 📊 Asset selector: EURUSD, GBPUSD, USDJPY, AUDUSD, EURJPY, BTCUSD...
- 💵 Stake + Max trades setting
- 📈 Strategy: EMA(9/21) + RSI(14) — desktop jaisi (configurable)
- ▶️ Big START / ⏹ STOP buttons
- 📜 Live scrolling log
- 💰 Balance display (live)
- 📊 Trade history (win/loss)
- ⚠️ REAL mode sakht warning ke saath (default: DEMO)

## Technical
- Native Java, **no WebView**
- Direct Socket.IO (EIO=3) HTTP long-polling client (`QuotexClient.java`)
- Desktop Python bot ka protocol port: `login`, `depth/follow`, `orders/open`, `s_balance/list`
- Trade result = balance difference (real P/L, desktop ke mock se behtar)
- OkHttp 4.12.0 only dependency

## Build
GitHub Actions: push on `main` → APK artifact `HamzaQxBot-apk`.

## ⚠️ Warnings
- **Pehle DEMO par test karo!**
- Bot se **profit ki guarantee NAHI** — paise doob sakte hain
- **Quotex bots ko BAN kar sakta hai**
