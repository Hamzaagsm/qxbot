package com.nova.signaltrader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Html;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Main screen v2.0: balance card, 💰 profit dashboard, live assets, bot controls. */
public class MainActivity extends Activity implements BotEngine.Ui {

    private Spinner modeSp, assetSp;
    private EditText stakeEt, maxEt, emaFEt, emaSEt, rsiEt;
    private CheckBox strongCb, martinCb;
    private Button startBtn, stopBtn;
    private TextView balDemoTv, balRealTv, pnlTv, winRateTv, wlTv;
    private TextView warmTv, logTv, histTv;
    private ScrollView logScroll;
    private BotEngine engine;
    private boolean realConfirmed = false;
    private List<QuotexClient.AssetInfo> assets = new ArrayList<>();
    private double lastDemoBal = -1, lastRealBal = -1;

    /** Static fallback agar live fetch fail ho. */
    private static List<QuotexClient.AssetInfo> defaultAssets() {
        List<QuotexClient.AssetInfo> l = new ArrayList<>();
        String[] forex = {"EURUSD","GBPUSD","USDJPY","AUDUSD","USDCAD","USDCHF","EURJPY",
                "GBPJPY","EURGBP","AUDJPY","NZDUSD","EURCAD","GBPCHF","EURAUD"};
        for (String f : forex) {
            l.add(new QuotexClient.AssetInfo(f, f));                    // regular
            l.add(new QuotexClient.AssetInfo(f + " (OTC)", f + "_otc")); // OTC 24/7
        }
        l.add(new QuotexClient.AssetInfo("BTCUSD", "BTCUSD"));
        l.add(new QuotexClient.AssetInfo("ETHUSD", "ETHUSD"));
        l.add(new QuotexClient.AssetInfo("BTCUSD (OTC)", "BTCUSD_otc"));
        return l;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0a0a14"));
        root.setPadding(28, 28, 28, 28);

        TextView title = new TextView(this);
        title.setText("📊 NOVA TRADER");
        title.setTextSize(22);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextColor(Color.parseColor("#00d4aa"));
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView ver = new TextView(this);
        ver.setText("v2.0 — stealth + smart");
        ver.setTextColor(Color.parseColor("#666677"));
        ver.setTextSize(11);
        ver.setGravity(Gravity.CENTER);
        root.addView(ver);
        root.addView(spacer(8));

        // ---- balance card ----
        LinearLayout balCard = new LinearLayout(this);
        balCard.setOrientation(LinearLayout.HORIZONTAL);
        balCard.setBackgroundColor(Color.parseColor("#111122"));
        balCard.setPadding(20, 14, 20, 14);
        balDemoTv = bigBal("DEMO\n$—");
        balRealTv = bigBal("REAL\n$—");
        balCard.addView(balDemoTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        balCard.addView(balRealTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(balCard);
        root.addView(spacer(10));

        // ---- 💰 profit dashboard ----
        root.addView(sectionLabel("💰 Profit Dashboard"));
        LinearLayout dash = new LinearLayout(this);
        dash.setOrientation(LinearLayout.VERTICAL);
        dash.setBackgroundColor(Color.parseColor("#0e1a14"));
        dash.setPadding(20, 12, 20, 12);
        pnlTv = dashLine("Session P/L: $+0.00", "#00d4aa", 18, true);
        winRateTv = dashLine("Win Rate: —", "#ffffff", 14, false);
        wlTv = dashLine("Jeet: 0 | Haar: 0 | Trades: 0", "#aaaaaa", 13, false);
        dash.addView(pnlTv); dash.addView(wlTv); dash.addView(winRateTv);
        root.addView(dash);
        root.addView(spacer(10));

        // ---- settings ----
        root.addView(sectionLabel("⚙️ Settings"));
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);

        grid.addView(row("Mode:", modeSp = spinner(new String[]{"DEMO", "REAL (Asli paisa!)"})));
        assetSp = spinner(new String[]{"⏳ Assets load ho rahe..."});
        grid.addView(row("Pair:", assetSp));
        grid.addView(row("Stake ($):", stakeEt = num("1")));
        grid.addView(row("Max Trades:", maxEt = num("10")));
        LinearLayout strat = new LinearLayout(this);
        strat.setOrientation(LinearLayout.HORIZONTAL);
        strat.addView(lbl("EMA:"));
        strat.addView(emaFEt = num("9"));
        strat.addView(emaSEt = num("21"));
        strat.addView(lbl(" RSI:"));
        strat.addView(rsiEt = num("14"));
        grid.addView(strat);

        strongCb = new CheckBox(this);
        strongCb.setText("🎯 Sirf STRONG signals (recommended)");
        strongCb.setTextColor(Color.WHITE);
        strongCb.setChecked(true);
        grid.addView(strongCb);

        martinCb = new CheckBox(this);
        martinCb.setText("⚠️ Martingale (RISKY — haar par stake double!)");
        martinCb.setTextColor(Color.parseColor("#ff8888"));
        martinCb.setChecked(false);
        grid.addView(martinCb);
        root.addView(grid);
        root.addView(spacer(10));

        // ---- buttons ----
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        startBtn = new Button(this);
        startBtn.setText("▶ START");
        startBtn.setTextSize(18);
        startBtn.setBackgroundColor(Color.parseColor("#00d4aa"));
        startBtn.setTextColor(Color.BLACK);
        startBtn.setEnabled(false); // assets load hone tak
        stopBtn = new Button(this);
        stopBtn.setText("⏹ STOP");
        stopBtn.setTextSize(18);
        stopBtn.setBackgroundColor(Color.parseColor("#e94560"));
        stopBtn.setTextColor(Color.WHITE);
        stopBtn.setEnabled(false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        bp.setMargins(8, 0, 8, 0);
        btns.addView(startBtn, bp);
        btns.addView(stopBtn, bp);
        root.addView(btns);
        root.addView(spacer(8));

        warmTv = new TextView(this);
        warmTv.setTextColor(Color.parseColor("#ffaa00"));
        warmTv.setGravity(Gravity.CENTER);
        root.addView(warmTv);
        root.addView(spacer(6));

        root.addView(sectionLabel("📜 Log:"));
        logTv = new TextView(this);
        logTv.setTextColor(Color.parseColor("#00ff88"));
        logTv.setBackgroundColor(Color.parseColor("#111122"));
        logTv.setPadding(16, 16, 16, 16);
        logTv.setTextSize(12);
        logScroll = new ScrollView(this);
        logScroll.addView(logTv);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f));
        root.addView(spacer(6));

        root.addView(sectionLabel("📊 Trade History:"));
        histTv = new TextView(this);
        histTv.setTextColor(Color.WHITE);
        histTv.setTextSize(12);
        histTv.setText("—");
        ScrollView hs = new ScrollView(this);
        hs.addView(histTv);
        root.addView(hs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(new ScrollView(this) {{
            addView(root);
        }});

        log("Nova Trader v2.0 tayyar! Assets + balance load ho rahe... ⚙️");

        modeSp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == 1 && !realConfirmed) askRealConfirm();
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        martinCb.setOnCheckedChangeListener((cb, on) -> {
            if (on) Toast.makeText(this,
                    "⚠️ Martingale RISKY hai! Haar par stake double hoga — sirf demo par!",
                    Toast.LENGTH_LONG).show();
        });

        startBtn.setOnClickListener(v -> startBot());
        stopBtn.setOnClickListener(v -> stopBot());

        loadAssetsAndBalance();
    }

    /** Quotex se live assets + dono balances lao (background). */
    private void loadAssetsAndBalance() {
        SharedPreferences p = getSharedPreferences(LoginActivity.PREFS, MODE_PRIVATE);
        final String email = p.getString("email", "");
        final String pw = p.getString("password", "");
        new Thread(() -> {
            List<QuotexClient.AssetInfo> got = null;
            try {
                QuotexClient c = new QuotexClient(this);
                c.connect();
                c.login(email, pw, true);
                got = c.fetchInstruments();
                double[] bal = c.getBalances();
                if (bal != null) { lastDemoBal = bal[0]; lastRealBal = bal[1]; }
                c.disconnect();
            } catch (Exception e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> log("⚠️ Live load fail: " + msg + " — default list"));
            }
            final List<QuotexClient.AssetInfo> fin =
                    (got != null && !got.isEmpty()) ? got : defaultAssets();
            final boolean live = got != null && !got.isEmpty();
            runOnUiThread(() -> {
                assets = fin;
                String[] names = new String[fin.size()];
                for (int i = 0; i < fin.size(); i++) names[i] = fin.get(i).display;
                ArrayAdapter<String> a = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, names);
                a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                assetSp.setAdapter(a);
                // default: EURUSD (OTC) select karo agar mile
                for (int i = 0; i < fin.size(); i++)
                    if (fin.get(i).key.equals("EURUSD_otc")) { assetSp.setSelection(i); break; }
                startBtn.setEnabled(true);
                updateBalanceCard();
                log("✅ " + fin.size() + " pairs ready" + (live ? " (live)" : " (default)"));
            });
        }).start();
    }

    private void updateBalanceCard() {
        balDemoTv.setText("DEMO\n" + (lastDemoBal >= 0
                ? String.format(Locale.US, "$%.2f", lastDemoBal) : "$—"));
        balRealTv.setText("REAL\n" + (lastRealBal >= 0
                ? String.format(Locale.US, "$%.2f", lastRealBal) : "$—"));
    }

    // ---------- UI helpers ----------
    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }
    private TextView sectionLabel(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(Color.parseColor("#00d4aa"));
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        return tv;
    }
    private TextView bigBal(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(17);
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        tv.setTextColor(Color.parseColor("#00d4aa"));
        tv.setGravity(Gravity.CENTER);
        return tv;
    }
    private TextView dashLine(String t, String color, int size, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(size);
        if (bold) tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        tv.setTextColor(Color.parseColor(color));
        tv.setGravity(Gravity.CENTER);
        return tv;
    }
    private TextView lbl(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(Color.WHITE);
        tv.setPadding(4, 0, 4, 0);
        return tv;
    }
    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        return s;
    }
    private EditText num(String def) {
        EditText e = new EditText(this);
        e.setText(def);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setTextColor(Color.WHITE);
        e.setBackgroundColor(Color.parseColor("#1a1a2e"));
        e.setPadding(16, 8, 16, 8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(4, 0, 4, 0);
        e.setLayoutParams(lp);
        return e;
    }
    private LinearLayout row(String label, View control) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = lbl(label);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        r.addView(tv);
        control.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        r.addView(control);
        r.setPadding(0, 4, 0, 4);
        return r;
    }

    private void askRealConfirm() {
        final CheckBox cb = new CheckBox(this);
        cb.setText("Main samajhta hoon: paise DOOB sakte hain, account BAN ho sakta hai!");
        cb.setTextColor(Color.WHITE);
        new AlertDialog.Builder(this)
                .setTitle("⚠️ REAL MONEY KHATRA!")
                .setMessage("REAL mode me ASLI paise lagenge!\n\n• Paise poore DOOB sakte hain\n• Quotex bot accounts BAN kar sakta hai\n• Profit ki koi GUARANTEE NAHI\n\nSirf woh paisa lagao jo haar sako!")
                .setView(cb)
                .setPositiveButton("Main samajh gaya, REAL chalao", (d, w) -> {
                    if (cb.isChecked()) {
                        realConfirmed = true;
                    } else {
                        modeSp.setSelection(0);
                        Toast.makeText(this, "Pehle tick karo!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Nahi, DEMO hi rakho", (d, w) -> {
                    modeSp.setSelection(0);
                    realConfirmed = false;
                })
                .setCancelable(false)
                .show();
    }

    // ---------- bot control ----------
    private void startBot() {
        SharedPreferences p = getSharedPreferences(LoginActivity.PREFS, MODE_PRIVATE);
        String email = p.getString("email", "");
        String pw = p.getString("password", "");
        if (email.isEmpty() || pw.isEmpty()) {
            Toast.makeText(this, "Pehle login karo!", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        boolean isDemo = modeSp.getSelectedItemPosition() == 0;
        if (!isDemo && !realConfirmed) { askRealConfirm(); return; }
        if (assets.isEmpty()) {
            Toast.makeText(this, "Assets abhi load ho rahe, thero!", Toast.LENGTH_SHORT).show();
            return;
        }

        double stake;
        int maxT, ef, es, rp;
        try {
            stake = Double.parseDouble(stakeEt.getText().toString());
            maxT = Integer.parseInt(maxEt.getText().toString());
            ef = Integer.parseInt(emaFEt.getText().toString());
            es = Integer.parseInt(emaSEt.getText().toString());
            rp = Integer.parseInt(rsiEt.getText().toString());
        } catch (Exception e) {
            Toast.makeText(this, "Numbers theek likho!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (stake < 1) { Toast.makeText(this, "Min stake $1", Toast.LENGTH_SHORT).show(); return; }

        QuotexClient.AssetInfo sel = assets.get(assetSp.getSelectedItemPosition());
        BotEngine.Config cfg = new BotEngine.Config();
        cfg.context = this;
        cfg.email = email; cfg.password = pw;
        cfg.isDemo = isDemo;
        cfg.assetKey = sel.key;
        cfg.assetDisplay = sel.display;
        cfg.stake = stake; cfg.maxTrades = maxT;
        cfg.emaFast = ef; cfg.emaSlow = es; cfg.rsiPeriod = rp;
        cfg.strongOnly = strongCb.isChecked();
        cfg.martingale = martinCb.isChecked();

        log("🚀 Bot start: " + sel.display + " | $" + stake + " x" + maxT
                + " | " + (isDemo ? "DEMO" : "⚠️ REAL")
                + (cfg.martingale ? " | ⚠️ MARTINGALE ON" : ""));
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        engine = new BotEngine(cfg, this);
        engine.start();
    }

    private void stopBot() {
        if (engine != null) engine.stop();
        runOnUiThread(() -> {
            startBtn.setEnabled(true);
            stopBtn.setEnabled(false);
            warmTv.setText("");
        });
        log("⏹ Bot roka gaya.");
    }

    // ---------- BotEngine.Ui ----------
    @Override
    public void log(final String msg) {
        runOnUiThread(() -> {
            String t = new java.text.SimpleDateFormat("HH:mm:ss",
                    Locale.US).format(new java.util.Date());
            logTv.append("[" + t + "] " + msg + "\n");
            String all = logTv.getText().toString();
            int lines = 0, cut = -1;
            for (int i = 0; i < all.length(); i++)
                if (all.charAt(i) == '\n' && ++lines > 400) { cut = i + 1; break; }
            if (cut > 0) logTv.setText(all.substring(cut));
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void stats(final int trades, final int wins, final int losses,
                      final double pnl, final double balance) {
        runOnUiThread(() -> {
            double winRate = trades > 0 ? 100.0 * wins / trades : 0;
            String pnlStr = String.format(Locale.US, "Session P/L: $%+.2f", pnl);
            pnlTv.setText(pnlStr);
            pnlTv.setTextColor(Color.parseColor(pnl >= 0 ? "#00ff88" : "#ff5566"));
            winRateTv.setText(String.format(Locale.US, "Win Rate: %.0f%%", winRate));
            wlTv.setText("Jeet: " + wins + " | Haar: " + losses + " | Trades: " + trades);
            // active mode ka balance card update
            boolean isDemo = modeSp.getSelectedItemPosition() == 0;
            if (isDemo) lastDemoBal = balance; else lastRealBal = balance;
            updateBalanceCard();
        });
    }

    @Override
    public void warmup(final String msg) {
        runOnUiThread(() -> warmTv.setText(msg));
    }

    @Override
    public void history(final List<BotEngine.TradeRecord> records) {
        runOnUiThread(() -> {
            if (records.isEmpty()) { histTv.setText("—"); return; }
            StringBuilder sb = new StringBuilder();
            for (BotEngine.TradeRecord r : records) {
                String color = r.profit >= 0 ? "#00ff88" : "#ff5566";
                String sign = r.profit >= 0 ? "+" : "";
                sb.append("<font color=\"#aaaaaa\">").append(r.time).append("</font> ")
                  .append(r.asset).append(" ")
                  .append(r.direction).append(" ")
                  .append(String.format(Locale.US, "$%.0f", r.amount)).append(" ")
                  .append("<font color=\"").append(color).append("\"><b>")
                  .append(sign).append(String.format(Locale.US, "%.2f", r.profit))
                  .append("</b></font><br>");
            }
            histTv.setText(Html.fromHtml(sb.toString(), Html.FROM_HTML_MODE_LEGACY));
        });
    }

    @Override
    public void finished(final String summary) {
        runOnUiThread(() -> {
            startBtn.setEnabled(true);
            stopBtn.setEnabled(false);
            warmTv.setText("");
        });
    }

    @Override
    protected void onDestroy() {
        if (engine != null && engine.isRunning()) engine.stop();
        super.onDestroy();
    }
}
