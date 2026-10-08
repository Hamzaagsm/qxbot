#!/usr/bin/env python3
"""Hamza Quotex Bot - GUI version. Demo default, real money locked behind explicit consent."""
import tkinter as tk
from tkinter import ttk, scrolledtext, messagebox
import threading, sys, os, time, asyncio
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from strategy import signal as get_signal

class QXBotGUI:
    def __init__(self, root):
        self.root = root
        root.title("Hamza Quotex Bot 🤖")
        root.geometry("650x600")
        root.configure(bg="#0a0a14")
        self.running = False

        hdr = tk.Label(root, text="🤖 HAMZA QUOTEX BOT", font=("Arial", 20, "bold"),
                       bg="#0a0a14", fg="#00d4aa")
        hdr.pack(pady=10)
        tk.Label(root, text="1-Minute Auto Trading Bot (Quotex)", bg="#0a0a14", fg="#888").pack()

        cf = tk.LabelFrame(root, text="Login", bg="#0a0a14", fg="#00d4aa", font=("Arial", 10, "bold"))
        cf.pack(fill="x", padx=15, pady=8)
        tk.Label(cf, text="Email:", bg="#0a0a14", fg="#fff").grid(row=0, column=0, padx=8, pady=5, sticky="w")
        self.email = tk.Entry(cf, width=35, bg="#1a1a2e", fg="#fff", insertbackground="#fff")
        self.email.grid(row=0, column=1, padx=8, pady=5)
        tk.Label(cf, text="Password:", bg="#0a0a14", fg="#fff").grid(row=1, column=0, padx=8, pady=5, sticky="w")
        self.pw = tk.Entry(cf, width=35, show="*", bg="#1a1a2e", fg="#fff", insertbackground="#fff")
        self.pw.grid(row=1, column=1, padx=8, pady=5)

        sf = tk.LabelFrame(root, text="Settings", bg="#0a0a14", fg="#00d4aa", font=("Arial", 10, "bold"))
        sf.pack(fill="x", padx=15, pady=8)

        tk.Label(sf, text="Balance:", bg="#0a0a14", fg="#fff").grid(row=0, column=0, padx=8, pady=5, sticky="w")
        self.mode = ttk.Combobox(sf, values=["DEMO", "REAL (Asli paisa!)"], width=18, state="readonly")
        self.mode.current(0)
        self.mode.grid(row=0, column=1, padx=8, pady=5, sticky="w")

        tk.Label(sf, text="Asset:", bg="#0a0a14", fg="#fff").grid(row=0, column=2, padx=8, pady=5, sticky="w")
        self.asset = ttk.Combobox(sf, values=["EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "EURJPY", "BTCUSD"], width=12, state="readonly")
        self.asset.current(0)
        self.asset.grid(row=0, column=3, padx=8, pady=5, sticky="w")

        tk.Label(sf, text="Amount ($):", bg="#0a0a14", fg="#fff").grid(row=1, column=0, padx=8, pady=5, sticky="w")
        self.stake = tk.Entry(sf, width=10, bg="#1a1a2e", fg="#fff", insertbackground="#fff")
        self.stake.insert(0, "1")
        self.stake.grid(row=1, column=1, padx=8, pady=5, sticky="w")

        tk.Label(sf, text="Max Trades:", bg="#0a0a14", fg="#fff").grid(row=1, column=2, padx=8, pady=5, sticky="w")
        self.max_trades = tk.Entry(sf, width=10, bg="#1a1a2e", fg="#fff", insertbackground="#fff")
        self.max_trades.insert(0, "10")
        self.max_trades.grid(row=1, column=3, padx=8, pady=5, sticky="w")

        bf = tk.Frame(root, bg="#0a0a14")
        bf.pack(pady=10)
        self.start_btn = tk.Button(bf, text="▶ START BOT", font=("Arial", 14, "bold"),
                                    bg="#00d4aa", fg="#000", width=15, command=self.start_bot)
        self.start_btn.pack(side="left", padx=10)
        self.stop_btn = tk.Button(bf, text="⏹ STOP", font=("Arial", 14, "bold"),
                                   bg="#e94560", fg="#fff", width=12, command=self.stop_bot,
                                   state="disabled")
        self.stop_btn.pack(side="left", padx=10)

        self.stats = tk.Label(root, text="Trades: 0 | P/L: $0.00 | Balance: -",
                               bg="#0a0a14", fg="#00d4aa", font=("Arial", 11, "bold"))
        self.stats.pack(pady=5)

        tk.Label(root, text="Log:", bg="#0a0a14", fg="#888").pack(anchor="w", padx=15)
        self.log = scrolledtext.ScrolledText(root, height=12, bg="#111", fg="#0f0",
                                              font=("Consolas", 9))
        self.log.pack(fill="both", expand=True, padx=15, pady=5)
        self.log_msg("Quotex Bot tayyar! Pehle DEMO par test karo. ⚠️")

    def log_msg(self, msg):
        ts = datetime.now().strftime("%H:%M:%S")
        self.log.insert("end", f"[{ts}] {msg}\n")
        self.log.see("end")

    def start_bot(self):
        if not self.email.get() or not self.pw.get():
            messagebox.showerror("Error", "Email aur Password likho!")
            return
        if self.mode.get().startswith("REAL"):
            ok = messagebox.askyesno("⚠️ KHATRA!",
                "REAL MONEY mode!\n\n• Paise DOOB sakte hain!\n• Account BAN ho sakta hai!\n\nPhir bhi chalana hai?")
            if not ok: return
        self.running = True
        self.start_btn.config(state="disabled")
        self.stop_btn.config(state="normal")
        threading.Thread(target=self.run_async_bot, daemon=True).start()

    def stop_bot(self):
        self.running = False
        self.start_btn.config(state="normal")
        self.stop_btn.config(state="disabled")
        self.log_msg("Bot roka gaya.")

    def run_async_bot(self):
        asyncio.run(self.bot_loop())

    async def bot_loop(self):
        try:
            from QuotexAPI import QuotexAPI, TradeDirection
        except ImportError:
            self.log_msg("❌ QuotexAPI install nahi!")
            self.stop_bot(); return
        try:
            self.log_msg("Connecting to Quotex...")
            api = QuotexAPI(email=self.email.get(), password=self.pw.get())
            profile = await api.connect()
            self.log_msg(f"✅ Connected: {profile.email if hasattr(profile,'email') else 'OK'}")

            is_demo = self.mode.get() == "DEMO"
            # switch account
            try:
                await api.switch_account(demo=is_demo)
            except: pass

            bal = await api.get_balance()
            bal_amt = bal.amount if hasattr(bal, 'amount') else bal
            self.log_msg(f"Mode={'DEMO' if is_demo else 'REAL'} Balance=${bal_amt}")
            self.stats.config(text=f"Trades: 0 | P/L: $0.00 | Balance: ${bal_amt}")

            asset = self.asset.get()
            # Quotex OTC suffix for forex
            qx_asset = asset if asset in ("BTCUSD",) else asset + "_otc"
            stake = float(self.stake.get())
            max_t = int(self.max_trades.get())
            trades, pnl = 0, 0.0
            closes = []

            while self.running and trades < max_t:
                try:
                    # get candles
                    candles = await api.get_candles(qx_asset, 60, 70)
                    if candles:
                        # extract closes (handle dict or object)
                        new_closes = []
                        for c in candles[:-1]:  # skip forming candle
                            if isinstance(c, dict):
                                new_closes.append(float(c.get("close", c.get("c", 0))))
                            else:
                                new_closes.append(float(getattr(c, "close", 0)))
                        if len(new_closes) >= 60:
                            closes = new_closes
                            sig = get_signal(closes)
                            if sig:
                                self.log_msg(f"📊 Signal: {sig.upper()} on {asset}")
                                direction = TradeDirection.CALL if sig == "call" else TradeDirection.PUT
                                trade = await api.buy(asset=qx_asset, amount=stake,
                                                      direction=direction, expiry=60)
                                oid = trade.order_id if hasattr(trade, 'order_id') else trade
                                self.log_msg(f"Trade lagayi: {sig} ${stake}")
                                result = await api.wait_for_result(oid)
                                profit = result.profit if hasattr(result, 'profit') else 0
                                pnl += float(profit)
                                trades += 1
                                self.log_msg(f"{'✅ JEET' if profit > 0 else '❌ HAAR'}: {profit:+.2f}")
                                nb = await api.get_balance()
                                nb_amt = nb.amount if hasattr(nb, 'amount') else nb
                                self.stats.config(text=f"Trades: {trades} | P/L: ${pnl:+.2f} | Balance: ${nb_amt}")
                    await asyncio.sleep(15)
                except Exception as e:
                    self.log_msg(f"⚠️ Error: {e}")
                    await asyncio.sleep(5)
            self.log_msg(f"Session khatam: {trades} trades, P/L ${pnl:+.2f}")
            await api.disconnect()
        except Exception as e:
            self.log_msg(f"❌ Fatal: {e}")
        self.stop_bot()

if __name__ == "__main__":
    root = tk.Tk()
    app = QXBotGUI(root)
    root.mainloop()
