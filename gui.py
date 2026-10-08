#!/usr/bin/env python3
"""Hamza Quotex Bot v1.1 - GUI version.

Uses the vendored QuotexAPI (streaming candles via WebSocket).
Demo default, real money locked behind explicit consent.

Strategy: EMA(9/21) + RSI(14) on 1-minute candles built from the live
candle stream. Needs ~25 candles of warmup (~25 min) before trading.
"""
import tkinter as tk
from tkinter import ttk, scrolledtext, messagebox
import threading, sys, os, asyncio, time
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from strategy import signal as get_signal

WARMUP_CANDLES = 31  # ema_slow(21) + 10


class QXBotGUI:
    def __init__(self, root):
        self.root = root
        root.title("Hamza Quotex Bot v1.1 🤖")
        root.geometry("650x620")
        root.configure(bg="#0a0a14")
        self.running = False
        self.candles = {}  # timestamp -> candle dict

        hdr = tk.Label(root, text="🤖 HAMZA QUOTEX BOT", font=("Arial", 20, "bold"),
                       bg="#0a0a14", fg="#00d4aa")
        hdr.pack(pady=8)
        tk.Label(root, text="1-Minute Auto Trading Bot (Quotex) v1.1", bg="#0a0a14", fg="#888").pack()

        cf = tk.LabelFrame(root, text="Login", bg="#0a0a14", fg="#00d4aa", font=("Arial", 10, "bold"))
        cf.pack(fill="x", padx=15, pady=6)
        tk.Label(cf, text="Email:", bg="#0a0a14", fg="#fff").grid(row=0, column=0, padx=8, pady=4, sticky="w")
        self.email = tk.Entry(cf, width=35, bg="#1a1a2e", fg="#fff", insertbackground="#fff")
        self.email.grid(row=0, column=1, padx=8, pady=4)
        tk.Label(cf, text="Password:", bg="#0a0a14", fg="#fff").grid(row=1, column=0, padx=8, pady=4, sticky="w")
        self.pw = tk.Entry(cf, width=35, show="*", bg="#1a1a2e", fg="#fff", insertbackground="#fff")
        self.pw.grid(row=1, column=1, padx=8, pady=4)

        sf = tk.LabelFrame(root, text="Settings", bg="#0a0a14", fg="#00d4aa", font=("Arial", 10, "bold"))
        sf.pack(fill="x", padx=15, pady=6)

        tk.Label(sf, text="Balance:", bg="#0a0a14", fg="#fff").grid(row=0, column=0, padx=8, pady=4, sticky="w")
        self.mode = ttk.Combobox(sf, values=["DEMO", "REAL (Asli paisa!)"], width=16, state="readonly")
        self.mode.current(0)
        self.mode.grid(row=0, column=1, padx=8, pady=4, sticky="w")

        tk.Label(sf, text="Asset:", bg="#0a0a14", fg="#fff").grid(row=0, column=2, padx=8, pady=4, sticky="w")
        self.asset = ttk.Combobox(sf, values=["EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "EURJPY", "BTCUSD"], width=10, state="readonly")
        self.asset.current(0)
        self.asset.grid(row=0, column=3, padx=8, pady=4, sticky="w")

        tk.Label(sf, text="Amount ($):", bg="#0a0a14", fg="#fff").grid(row=1, column=0, padx=8, pady=4, sticky="w")
        self.stake = tk.Entry(sf, width=10, bg="#1a1a2e", fg="#fff", insertbackground="#fff")
        self.stake.insert(0, "1")
        self.stake.grid(row=1, column=1, padx=8, pady=4, sticky="w")

        tk.Label(sf, text="Max Trades:", bg="#0a0a14", fg="#fff").grid(row=1, column=2, padx=8, pady=4, sticky="w")
        self.max_trades = tk.Entry(sf, width=10, bg="#1a1a2e", fg="#fff", insertbackground="#fff")
        self.max_trades.insert(0, "10")
        self.max_trades.grid(row=1, column=3, padx=8, pady=4, sticky="w")

        bf = tk.Frame(root, bg="#0a0a14")
        bf.pack(pady=8)
        self.start_btn = tk.Button(bf, text="▶ START BOT", font=("Arial", 14, "bold"),
                                    bg="#00d4aa", fg="#000", width=15, command=self.start_bot)
        self.start_btn.pack(side="left", padx=10)
        self.stop_btn = tk.Button(bf, text="⏹ STOP", font=("Arial", 14, "bold"),
                                   bg="#e94560", fg="#fff", width=12, command=self.stop_bot,
                                   state="disabled")
        self.stop_btn.pack(side="left", padx=10)

        self.stats = tk.Label(root, text="Trades: 0 | P/L: $0.00 | Balance: -",
                               bg="#0a0a14", fg="#00d4aa", font=("Arial", 11, "bold"))
        self.stats.pack(pady=4)
        self.warm = tk.Label(root, text="", bg="#0a0a14", fg="#ffaa00", font=("Arial", 10))
        self.warm.pack()

        tk.Label(root, text="Log:", bg="#0a0a14", fg="#888").pack(anchor="w", padx=15)
        self.log = scrolledtext.ScrolledText(root, height=11, bg="#111", fg="#0f0",
                                              font=("Consolas", 9))
        self.log.pack(fill="both", expand=True, padx=15, pady=5)
        self.log_msg("Quotex Bot v1.1 tayyar! Pehle DEMO par test karo. ⚠️")
        self.log_msg("Note: live candles jama hone me ~25 min lagenge (warmup).")

    def log_msg(self, msg):
        ts = datetime.now().strftime("%H:%M:%S")
        def _w():
            self.log.insert("end", f"[{ts}] {msg}\n")
            self.log.see("end")
        self.root.after(0, _w)

    def set_warm(self, msg):
        self.root.after(0, lambda: self.warm.config(text=msg))

    def set_stats(self, msg):
        self.root.after(0, lambda: self.stats.config(text=msg))

    def start_bot(self):
        if not self.email.get() or not self.pw.get():
            messagebox.showerror("Error", "Email aur Password likho!")
            return
        if self.mode.get().startswith("REAL"):
            ok = messagebox.askyesno("⚠️ KHATRA!",
                "REAL MONEY mode!\n\n• Paise DOOB sakte hain!\n• Account BAN ho sakta hai!\n\nPhir bhi chalana hai?")
            if not ok: return
        self.running = True
        self.candles = {}
        self.start_btn.config(state="disabled")
        self.stop_btn.config(state="normal")
        threading.Thread(target=self.run_async_bot, daemon=True).start()

    def stop_bot(self):
        self.running = False
        self.root.after(0, lambda: self.start_btn.config(state="normal"))
        self.root.after(0, lambda: self.stop_btn.config(state="disabled"))
        self.set_warm("")
        self.log_msg("Bot roka gaya.")

    def run_async_bot(self):
        asyncio.run(self.bot_loop())

    async def bot_loop(self):
        try:
            from QuotexAPI import QuotexAPI, TradeDirection, AccountType
        except ImportError as e:
            self.log_msg(f"❌ QuotexAPI load nahi hui: {e}")
            self.stop_bot(); return

        api = None
        try:
            self.log_msg("Quotex se connect ho raha...")
            api = QuotexAPI(email=self.email.get(), password=self.pw.get())
            profile = await api.connect()
            em = getattr(profile, "email", "OK")
            self.log_msg(f"✅ Connected: {em}")

            is_demo = self.mode.get() == "DEMO"
            try:
                bal = await api.switch_account(AccountType.DEMO if is_demo else AccountType.REAL)
                bal_amt = getattr(bal, "amount", bal)
            except Exception as e:
                self.log_msg(f"⚠️ Account switch: {e}")
                nb = await api.get_balance()
                bal_amt = getattr(nb, "amount", nb)
            self.log_msg(f"Mode={'DEMO' if is_demo else 'REAL'} | Balance=${bal_amt}")
            self.set_stats(f"Trades: 0 | P/L: $0.00 | Balance: ${bal_amt}")

            asset = self.asset.get()
            qx_key = asset if asset == "BTCUSD" else asset + "_otc"
            # Quotex OTC assets need _otc suffix for trading too
            trade_asset = qx_key
            stake = float(self.stake.get())
            max_t = int(self.max_trades.get())
            trades, pnl = 0, 0.0
            last_sig_ts = 0

            loop = asyncio.get_running_loop()
            q = asyncio.Queue()

            def on_candle(data):
                try:
                    loop.call_soon_threadsafe(q.put_nowait, dict(data))
                except Exception:
                    pass

            api.data.on_candle(qx_key, on_candle)
            await api.data.subscribe_candles(asset, timeframe=60)
            self.log_msg(f"📡 {asset} candles subscribe ho gaye. Warmup...")

            while self.running and trades < max_t:
                try:
                    data = await asyncio.wait_for(q.get(), timeout=30)
                except asyncio.TimeoutError:
                    self.set_warm(f"⏳ Candle ka intezar... ({len(self.candles)}/{WARMUP_CANDLES})")
                    continue

                ts = int(data.get("timestamp", data.get("time", 0)))
                if ts <= 0:
                    continue
                # keep latest update per candle timestamp
                self.candles[ts] = data
                # drop old (keep last 120)
                if len(self.candles) > 120:
                    for k in sorted(self.candles)[:-120]:
                        del self.candles[k]

                ordered = sorted(self.candles)
                # closed candles = all except the latest (still forming)
                closed = ordered[:-1] if len(ordered) > 1 else []
                closes = [float(self.candles[t].get("close", 0)) for t in closed]
                closes = [c for c in closes if c > 0]

                n = len(closes)
                if n < WARMUP_CANDLES:
                    self.set_warm(f"⏳ Warmup: {n}/{WARMUP_CANDLES} candles jama...")
                    continue
                self.set_warm(f"✅ Live: {n} candles | strategy active")

                # only evaluate once per newest closed candle
                newest = closed[-1]
                if newest == last_sig_ts:
                    continue
                last_sig_ts = newest

                sig = get_signal(closes, ema_fast=9, ema_slow=21, rsi_period=14)
                if not sig:
                    continue
                self.log_msg(f"📊 Signal: {sig.upper()} @ {closes[-1]:.5f}")
                try:
                    direction = TradeDirection.CALL if sig == "call" else TradeDirection.PUT
                    trade = await api.buy(asset=trade_asset, amount=stake,
                                          direction=direction, expiry=60)
                    oid = getattr(trade, "order_id", trade)
                    self.log_msg(f"Trade lagayi: {sig.upper()} ${stake}")
                    result = await api.wait_for_result(oid)
                    profit = float(getattr(result, "profit", 0) or 0)
                    pnl += profit
                    trades += 1
                    self.log_msg(f"{'✅ JEET' if profit > 0 else '❌ HAAR'}: {profit:+.2f}")
                    try:
                        nb = await api.get_balance()
                        nb_amt = getattr(nb, "amount", nb)
                    except Exception:
                        nb_amt = "?"
                    self.set_stats(f"Trades: {trades} | P/L: ${pnl:+.2f} | Balance: ${nb_amt}")
                except Exception as e:
                    self.log_msg(f"⚠️ Trade error: {e}")

            self.log_msg(f"Session khatam: {trades} trades, P/L ${pnl:+.2f}")
            self.set_warm("")
        except Exception as e:
            self.log_msg(f"❌ Fatal: {type(e).__name__}: {e}")
        finally:
            if api:
                try: await api.disconnect()
                except Exception: pass
        self.stop_bot()


if __name__ == "__main__":
    root = tk.Tk()
    app = QXBotGUI(root)
    root.mainloop()
