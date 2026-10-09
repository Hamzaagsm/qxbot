package com.hamza.qxbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.util.List;
import java.util.Locale;

/** Main bot screen: settings, START/STOP, live log, balance, trade history. */
public class MainActivity extends Activity implements BotEngine.Ui {

    private Spinner modeSp, assetSp;
    private EditText stakeEt, maxEt, emaFEt, emaSEt, rsiEt;
    private Button startBtn, stopBtn;
    private TextView statsTv, warmTv, logTv, histTv, balTv;
    private ScrollView logScroll;
    private BotEngine engine;
    private boolean realConfirmed = false;

    private static final String[] ASSETS =
            {"EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "EURJPY", "BTCUSD", "EURGBP", "USDCHF", "GBPJPY", "AUDJPY"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0a0a14"));
        root.setPadding(28, 28, 28, 28);

        TextView title = new TextView(this);
        title.setText("🤖 HAMZA QX BOT");
        title.setTextSize(22);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextColor(Color.parseColor("#00d4aa"));
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        balTv = new TextView(this);
        balTv.setText("Balance: -");
        balTv.setTextSize(16);
        balTv.setTypeface(balTv.getTypeface(), Typeface.BOLD);
        balTv.setTextColor(Color.parseColor("#00d4aa"));
        balTv.setGravity(Gravity.CENTER);
        root.addView(balTv);
        root.addView(spacer(10));

        // ---- settings ----
        root.addView(sectionLabel("⚙️ Settings"));
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);

        grid.addView(row("Mode:", modeSp = spinner(new String[]{"DEMO", "REAL (Asli paisa!)"})));
        grid.addView(row("Asset:", assetSp = spinner(ASSETS)));
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

        statsTv = new TextView(this);
        statsTv.setText("Trades: 0 | P/L: $0.00 | Wins: 0");
        statsTv.setTextColor(Color.parseColor("#00d4aa"));
        statsTv.setGravity(Gravity.CENTER);
        root.addView(statsTv);

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

        log("Bot tayyar! Pehle DEMO par test karo. ⚠️");

        modeSp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == 1 && !realConfirmed) askRealConfirm();
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });

        startBtn.setOnClickListener(v -> startBot());
        stopBtn.setOnClickListener(v -> stopBot());
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

        BotEngine.Config cfg = new BotEngine.Config();
        cfg.email = email; cfg.password = pw;
        cfg.isDemo = isDemo;
        cfg.asset = ASSETS[assetSp.getSelectedItemPosition()];
        cfg.stake = stake; cfg.maxTrades = maxT;
        cfg.emaFast = ef; cfg.emaSlow = es; cfg.rsiPeriod = rp;

        log("🚀 Bot start: " + cfg.asset + " | $" + stake + " x" + maxT
                + " | " + (isDemo ? "DEMO" : "⚠️ REAL"));
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
            // trim to ~400 lines
            String all = logTv.getText().toString();
            int lines = 0, cut = -1;
            for (int i = 0; i < all.length(); i++)
                if (all.charAt(i) == '\n' && ++lines > 400) { cut = i + 1; break; }
            if (cut > 0) logTv.setText(all.substring(cut));
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void stats(final int trades, final double pnl, final double balance, final int wins) {
        runOnUiThread(() -> {
            statsTv.setText(String.format(Locale.US,
                    "Trades: %d | P/L: $%+.2f | Wins: %d", trades, pnl, wins));
            balTv.setText(String.format(Locale.US, "Balance: $%.2f", balance));
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
                sb.append(String.format(Locale.US, "%s %s %s $%.0f %s%.2f\n",
                        r.time, r.asset, r.direction, r.amount,
                        r.profit >= 0 ? "+" : "", r.profit));
            }
            histTv.setText(sb.toString());
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
