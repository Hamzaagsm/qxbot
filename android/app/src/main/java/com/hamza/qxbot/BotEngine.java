package com.hamza.qxbot;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bot trading loop. Port of the desktop gui.py bot_loop.
 * Runs on its own background thread; talks to the UI through callbacks.
 *
 * Flow: connect -> login -> balance -> depth/follow -> warmup candles ->
 * signal on each new closed 1-min candle -> orders/open -> wait expiry ->
 * balance diff = profit -> stats + history.
 */
public class BotEngine {

    public static class Config {
        public String email, password;
        public boolean isDemo = true;
        public String asset = "EURUSD";   // without _otc
        public double stake = 1.0;
        public int maxTrades = 10;
        public int emaFast = 9, emaSlow = 21, rsiPeriod = 14;  // desktop gui.py defaults
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
        void stats(int trades, double pnl, double balance, int wins);
        void warmup(String msg);
        void history(List<TradeRecord> records);
        void finished(String summary);
    }

    private final Config cfg;
    private final Ui ui;
    private final QuotexClient client = new QuotexClient();
    private volatile boolean running;
    private Thread worker;

    // timestamp -> close (latest update per candle wins)
    private final TreeMap<Long, Double> candles = new TreeMap<>();
    private String followedAsset;

    public BotEngine(Config cfg, Ui ui) {
        this.cfg = cfg;
        this.ui = ui;
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
        int trades = 0, wins = 0;
        double pnl = 0.0;
        try {
            ui.log("Quotex se connect ho raha...");
            client.connect();
            ui.log("✅ Connected");

            QuotexClient.LoginResult lr = client.login(cfg.email, cfg.password, cfg.isDemo);
            ui.log("✅ Login OK (" + (cfg.isDemo ? "DEMO" : "REAL") + ")");

            double[] bal = client.getBalances();
            double balance = cfg.isDemo ? bal[0] : bal[1];
            ui.log(String.format(Locale.US, "Balance: $%.2f (%s)", balance, cfg.isDemo ? "DEMO" : "REAL"));
            ui.stats(trades, pnl, balance, wins);

            // asset key: desktop uses plain "BTCUSD", others + "_otc"
            followedAsset = cfg.asset.equals("BTCUSD") ? "BTCUSD" : cfg.asset + "_otc";
            client.subscribe("depth", this::onDepth);
            client.followDepth(followedAsset);

            int warmupNeed = cfg.emaSlow + 10;
            ui.log("📡 Candle stream shuru. Warmup: " + warmupNeed + " candles (~" + warmupNeed + " min)...");
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
                try { sig = Strategy.signal(closes, cfg.emaFast, cfg.emaSlow, cfg.rsiPeriod); }
                catch (Exception e) { sig = null; }
                if (sig == null) { sleepQuiet(3000); continue; }

                double price = closes.get(closes.size() - 1);
                ui.log("📊 Signal: " + sig.toUpperCase() + " @ " + String.format(Locale.US, "%.5f", price)
                        + " | " + Strategy.describe(closes, cfg.emaFast, cfg.emaSlow, cfg.rsiPeriod));

                // balance before trade
                double before = activeBalance();
                ui.log("Trade lag rahi: " + sig.toUpperCase() + " $" + cfg.stake + " ...");
                try {
                    client.placeOrder(followedAsset, cfg.stake, sig, cfg.isDemo);
                } catch (Exception e) {
                    ui.log("⚠️ Order error: " + e.getMessage());
                    sleepQuiet(5000);
                    continue;
                }
                ui.log("✅ Order lag gaya. Result ka intezar (60s)...");

                // wait for expiry + buffer (interruptible for STOP)
                if (!sleepQuiet(63000)) break;

                double after = activeBalance();
                double profit = after - before;
                pnl += profit;
                trades++;
                boolean win = profit > 0;
                if (win) wins++;
                records.add(0, new TradeRecord(ts(), cfg.asset, sig.toUpperCase(), cfg.stake, profit));
                ui.history(new ArrayList<>(records));
                ui.log((win ? "✅ JEET" : "❌ HAAR") + ": " + String.format(Locale.US, "%+.2f", profit)
                        + " | Balance $" + String.format(Locale.US, "%.2f", after));
                ui.stats(trades, pnl, after, wins);
            }

            ui.warmup("");
            String summary = "Session khatam: " + trades + " trades, P/L $" + String.format(Locale.US, "%+.2f", pnl);
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

    private double activeBalance() {
        try {
            double[] b = client.getBalances();
            return cfg.isDemo ? b[0] : b[1];
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
