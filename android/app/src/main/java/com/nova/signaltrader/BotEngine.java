package com.nova.signaltrader;

import android.content.Context;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeMap;

/**
 * Bot trading loop v2.0.
 *
 * Flow: connect -> login -> balance -> depth/follow -> warmup candles ->
 * signal on each new closed 1-min candle -> human-like pause -> orders/open ->
 * wait expiry -> balance diff = profit -> stats + history.
 *
 * Stealth: signal ke baad 3-12s random "human hesitation" pause, ~12% signals
 * skip (insaan har signal par trade nahi karta), poll/expiry timing me jitter.
 *
 * Martingale: default OFF. ON ho to haar par stake double (max 2 steps = 4x),
 * jeet par reset. RISKY — sirf demo par!
 */
public class BotEngine {

    public static class Config {
        public Context context;
        public String email, password;
        public boolean isDemo = true;
        public String assetKey = "EURUSD_otc"; // exact server key
        public String assetDisplay = "EURUSD (OTC)";
        public double stake = 1.0;
        public int maxTrades = 10;
        public int emaFast = 9, emaSlow = 21, rsiPeriod = 14;
        public boolean strongOnly = true;   // strong signals only
        public boolean martingale = false;  // OFF by default (risky!)
        public double skipChance = 0.12;    // 12% signals skip (human-like)
    }

    public static class TradeRecord {
        public final String time, asset, direction;
        public final double amount, profit;
        TradeRecord(String t, String a, String d, double amt, double p) {
            time = t; asset = a; direction = d; amount = amt; profit = p;
        }
    }

    public interface Ui {
        void log(String msg);
        void stats(int trades, int wins, int losses, double pnl, double balance);
        void warmup(String msg);
        void history(List<TradeRecord> records);
        void finished(String summary);
    }

    private final Config cfg;
    private final Ui ui;
    private final QuotexClient client;
    private final Random rnd = new Random();
    private volatile boolean running;
    private Thread worker;

    // timestamp -> close (latest update per candle wins)
    private final TreeMap<Long, Double> candles = new TreeMap<>();
    private String followedAsset;

    public BotEngine(Config cfg, Ui ui) {
        this.cfg = cfg;
        this.ui = ui;
        this.client = new QuotexClient(cfg.context);
        client.setLogSink(ui::log);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        candles.clear();
        worker = new Thread(this::loop, "QxBot");
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        try { client.unfollowDepth(followedAsset); } catch (Exception ignored) {}
        client.disconnect();
    }

    public boolean isRunning() { return running; }

    private String ts() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private void loop() {
        List<TradeRecord> records = new ArrayList<>();
        int trades = 0, wins = 0, losses = 0, consecLosses = 0;
        double pnl = 0.0;
        try {
            ui.log("Quotex se connect ho raha...");
            client.connect();
            ui.log("✅ Connected");

            QuotexClient.LoginResult lr = client.login(cfg.email, cfg.password, cfg.isDemo);
            ui.log("✅ Login OK (" + (cfg.isDemo ? "DEMO" : "REAL") + ")");

            double balance = activeBalance();
            ui.log(String.format(Locale.US, "Balance: $%.2f (%s)", balance, cfg.isDemo ? "DEMO" : "REAL"));
            ui.stats(trades, wins, losses, pnl, balance);

            followedAsset = cfg.assetKey;
            client.subscribe("depth", this::onDepth);
            client.followDepth(followedAsset);

            int warmupNeed = cfg.emaSlow + 10;
            ui.log("📡 Candle stream shuru. Warmup: " + warmupNeed + " candles (~" + warmupNeed + " min)...");
            ui.log("🎯 Strong signals: " + (cfg.strongOnly ? "ON" : "OFF")
                    + " | Martingale: " + (cfg.martingale ? "⚠️ ON (RISKY!)" : "OFF (safe)"));
            long lastSigTs = 0;

            while (running && trades < cfg.maxTrades) {
                List<Double> closes;
                long newest;
                synchronized (candles) {
                    if (candles.size() < 2) { closes = null; newest = -1; }
                    else {
                        List<Long> keys = new ArrayList<>(candles.keySet());
                        List<Long> closed = keys.subList(0, keys.size() - 1); // latest still forming
                        closes = new ArrayList<>();
                        for (long k : closed) {
                            double c = candles.get(k);
                            if (c > 0) closes.add(c);
                        }
                        newest = closed.get(closed.size() - 1);
                    }
                }
                int n = closes == null ? 0 : closes.size();
                if (n < warmupNeed) {
                    ui.warmup("⏳ Warmup: " + n + "/" + warmupNeed + " candles jama ho rahe...");
                    sleepQuiet(5000);
                    continue;
                }
                ui.warmup("✅ Live: " + n + " candles | strategy active");
                if (newest == lastSigTs) { sleepQuiet(3000); continue; }
                lastSigTs = newest;

                String sig;
                try { sig = Strategy.signal(closes, cfg.emaFast, cfg.emaSlow, cfg.rsiPeriod, cfg.strongOnly); }
                catch (Exception e) { sig = null; }
                if (sig == null) { sleepQuiet(3000); continue; }

                // Human-like: kabhi kabhi signal skip (insaan har signal nahi leta)
                if (rnd.nextDouble() < cfg.skipChance) {
                    ui.log("⏭️ Signal skip — human-like pause (" + sig.toUpperCase() + " ignore)");
                    sleepQuiet(4000 + rnd.nextInt(6000));
                    continue;
                }

                double price = closes.get(closes.size() - 1);
                ui.log("📊 Signal: " + sig.toUpperCase() + " @ " + String.format(Locale.US, "%.5f", price)
                        + " | " + Strategy.describe(closes, cfg.emaFast, cfg.emaSlow, cfg.rsiPeriod));

                // Human hesitation: signal ke baad 3-12s sochne ka waqfa
                int hesitate = 3000 + rnd.nextInt(9000);
                ui.log("🤔 Soch raha... (" + (hesitate / 1000) + "s)");
                if (!sleepQuiet(hesitate)) break;

                // Martingale stake: haar par double (max 2 steps)
                double stakeThis = cfg.stake;
                if (cfg.martingale && consecLosses > 0) {
                    int step = Math.min(consecLosses, 2);
                    stakeThis = cfg.stake * (1 << step);
                    ui.log("⚠️ Martingale step " + step + ": stake $" + String.format(Locale.US, "%.0f", stakeThis));
                }

                double before = activeBalance();
                if (before < 0) { ui.log("⚠️ Balance read fail — trade skip"); sleepQuiet(5000); continue; }
                ui.log("Trade lag rahi: " + sig.toUpperCase() + " $" + String.format(Locale.US, "%.0f", stakeThis) + " ...");
                try {
                    client.placeOrder(followedAsset, stakeThis, sig, cfg.isDemo);
                } catch (Exception e) {
                    ui.log("⚠️ Order error: " + e.getMessage());
                    sleepQuiet(5000);
                    continue;
                }
                ui.log("✅ Order lag gaya. Result ka intezar (60s)...");

                // expiry wait + jitter (interruptible for STOP)
                if (!sleepQuiet(63000 + rnd.nextInt(4000))) break;

                double after = activeBalance();
                if (after < 0) { ui.log("⚠️ Balance read fail — P/L unknown"); sleepQuiet(5000); continue; }
                double profit = after - before;
                pnl += profit;
                trades++;
                boolean win = profit > 0;
                if (win) { wins++; consecLosses = 0; }
                else { losses++; consecLosses++; }
                records.add(0, new TradeRecord(ts(), cfg.assetDisplay, sig.toUpperCase(), stakeThis, profit));
                ui.history(new ArrayList<>(records));
                ui.log((win ? "✅ JEET" : "❌ HAAR") + ": " + String.format(Locale.US, "%+.2f", profit)
                        + " | Balance $" + String.format(Locale.US, "%.2f", after));
                ui.stats(trades, wins, losses, pnl, after);
            }

            ui.warmup("");
            double winRate = trades > 0 ? 100.0 * wins / trades : 0;
            String summary = String.format(Locale.US,
                    "Session khatam: %d trades | jeet %d | haar %d | win rate %.0f%% | P/L $%+.2f",
                    trades, wins, losses, winRate, pnl);
            ui.log(summary);
            ui.finished(summary);
        } catch (Exception e) {
            ui.log("❌ Fatal: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            ui.finished("Ruk gaya: " + e.getMessage());
        } finally {
            running = false;
            try { client.disconnect(); } catch (Exception ignored) {}
        }
    }

    /** Active (demo/real) balance, ya -1 on failure. */
    private double activeBalance() {
        try {
            double[] b = client.getBalances();
            if (b == null) return -1;
            double v = cfg.isDemo ? b[0] : b[1];
            return v >= 0 ? v : -1;
        } catch (Exception e) {
            ui.log("⚠️ Balance read fail: " + e.getMessage());
            return -1;
        }
    }

    private void onDepth(JSONObject d) {
        try {
            String asset = d.optString("asset", "");
            if (!asset.equals(followedAsset)) return;
            long t = d.optLong("timestamp", d.optLong("time", 0));
            double close = d.optDouble("close", 0);
            if (t <= 0 || close <= 0) return;
            synchronized (candles) {
                candles.put(t, close);
                while (candles.size() > 150) candles.pollFirstEntry();
            }
        } catch (Exception ignored) {}
    }

    /** @return false if interrupted (STOP pressed) */
    private boolean sleepQuiet(long ms) {
        try { Thread.sleep(ms); return true; }
        catch (InterruptedException e) { return false; }
    }
}
