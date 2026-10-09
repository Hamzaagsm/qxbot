package com.hamza.qxbot;

import android.util.Log;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Quotex Socket.IO (EIO=3) client over HTTP long-polling.
 * Direct Java port of the desktop Python bot (QuotexAPI/utils/curl_websocket.py
 * + services/connection.py + services/auth.py + services/trading.py + services/data.py).
 *
 * Protocol:
 *   GET  https://ws2.qxbroker.com/socket.io/?EIO=3&transport=polling   -> 0{"sid":"..."}
 *   GET  ...&sid=SID          (long-poll, ~25s)  -> <len>:<msg>...
 *   POST ...&sid=SID          body: 42["event",data]  / "3" (pong)
 *
 * Messages: "2"=ping, "40"=connected, 42["event",data], 451-[data]=ACK.
 */
public class QuotexClient {
    private static final String TAG = "QuotexClient";
    private static final String[] WS_HOSTS = {"ws2.qxbroker.com", "ws.qxbroker.com"};
    private static final MediaType TEXT = MediaType.parse("text/plain;charset=UTF-8");

    public interface EventHandler { void onEvent(JSONObject data); }
    public interface LogSink { void log(String msg); }

    public static class LoginResult {
        public final String ssid;
        public final double demoBalance;
        public final double liveBalance;
        LoginResult(String ssid, double demoBalance, double liveBalance) {
            this.ssid = ssid; this.demoBalance = demoBalance; this.liveBalance = liveBalance;
        }
    }

    private final OkHttpClient http;
    private LogSink sink;
    private volatile String sid;
    private volatile String pollBase;
    private volatile boolean running;
    private Thread pollThread;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<EventHandler>> handlers = new ConcurrentHashMap<>();

    public QuotexClient() {
        http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public void setLogSink(LogSink s) { this.sink = s; }
    private void slog(String m) {
        Log.d(TAG, m);
        if (sink != null) sink.log(m);
    }

    // ---------------- event bus ----------------
    public void subscribe(String event, EventHandler h) {
        handlers.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(h);
    }
    public void unsubscribe(String event, EventHandler h) {
        List<EventHandler> l = handlers.get(event);
        if (l != null) l.remove(h);
    }
    private void dispatch(String event, JSONObject data) {
        List<EventHandler> l = handlers.get(event);
        if (l == null) return;
        for (EventHandler h : l) {
            try { h.onEvent(data); } catch (Exception e) { Log.e(TAG, "handler err", e); }
        }
    }

    // ---------------- HTTP helpers ----------------
    private Request.Builder baseReq(String url) {
        return new Request.Builder().url(url)
                .header("Origin", "https://qxbroker.com")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache");
    }

    /** Parse Socket.IO polling payload: <len>:<msg><len>:<msg>... */
    static List<String> parsePayload(String payload) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        int i = 0;
        while (i < payload.length()) {
            int colon = payload.indexOf(':', i);
            if (colon < 0) break;
            int len;
            try { len = Integer.parseInt(payload.substring(i, colon)); }
            catch (NumberFormatException e) { break; }
            int start = colon + 1;
            int end = start + len;
            if (end > payload.length()) break;
            out.add(payload.substring(start, end));
            i = end;
        }
        return out;
    }

    // ---------------- connection ----------------
    public void connect() throws Exception {
        Exception lastErr = null;
        for (String host : WS_HOSTS) {
            try {
                String url = "https://" + host + "/socket.io/?EIO=3&transport=polling";
                slog("Connecting: " + host + " ...");
                Request req = baseReq(url).get().build();
                try (Response resp = http.newCall(req).execute()) {
                    if (resp.code() == 403) throw new IOException("HTTP 403 (blocked)");
                    if (resp.code() != 200 || resp.body() == null)
                        throw new IOException("HTTP " + resp.code());
                    String body = resp.body().string();
                    String foundSid = null;
                    for (String m : parsePayload(body)) {
                        if (m.startsWith("0{")) {
                            foundSid = new JSONObject(m.substring(1)).optString("sid", null);
                            break;
                        }
                    }
                    if (foundSid == null || foundSid.isEmpty())
                        throw new IOException("No SID in handshake");
                    this.sid = foundSid;
                    this.pollBase = url;
                    slog("Connected, SID OK");
                    startPoller();
                    return;
                }
            } catch (Exception e) {
                lastErr = e;
                slog("Host " + host + " failed: " + e.getMessage());
            }
        }
        throw new Exception("Connect failed on all hosts" + (lastErr != null ? ": " + lastErr.getMessage() : ""));
    }

    private void startPoller() {
        running = true;
        pollThread = new Thread(this::pollLoop, "QxPoller");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void pollLoop() {
        while (running && sid != null) {
            try {
                String url = pollBase + "&sid=" + sid;
                Request req = baseReq(url).get().build();
                try (Response resp = http.newCall(req).execute()) {
                    if (resp.code() != 200 || resp.body() == null) {
                        Thread.sleep(1000);
                        continue;
                    }
                    String body = resp.body().string();
                    if (body.isEmpty()) continue;
                    for (String m : parsePayload(body)) handleMessage(m);
                }
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "poll err: " + e.getMessage());
                    try { Thread.sleep(1500); } catch (InterruptedException ie) { break; }
                }
            }
        }
    }

    private void handleMessage(String m) {
        try {
            if (m.equals("2")) { sendRaw("3"); return; }      // ping -> pong
            if (m.equals("40") || m.startsWith("0{") || m.startsWith("3")) return;
            if (m.startsWith("45") && m.contains("-")) {       // ACK: 451-[{...}]
                int dash = m.indexOf('-');
                JSONArray arr = new JSONArray(m.substring(dash + 1));
                if (arr.length() > 0 && arr.get(0) instanceof JSONObject)
                    dispatch("_ack_response", arr.getJSONObject(0));
                return;
            }
            if (!m.startsWith("42")) return;
            JSONArray payload = new JSONArray(m.substring(2));
            if (payload.length() < 1) return;
            String event = payload.getString(0);
            JSONObject data = (payload.length() > 1 && payload.get(1) instanceof JSONObject)
                    ? payload.getJSONObject(1) : new JSONObject();
            dispatch(event, data);
        } catch (Exception e) {
            Log.w(TAG, "msg parse err: " + e.getMessage());
        }
    }

    public void sendRaw(String msg) throws IOException {
        if (sid == null) throw new IOException("not connected");
        Request req = baseReq(pollBase + "&sid=" + sid)
                .post(RequestBody.create(msg, TEXT)).build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.code() != 200) throw new IOException("send HTTP " + resp.code());
        }
    }

    public void sendEvent(String event, Object data) throws Exception {
        JSONArray arr = new JSONArray();
        arr.put(event);
        if (data instanceof JSONObject) arr.put((JSONObject) data);
        else if (data instanceof String) arr.put((String) data);
        else arr.put(JSONObject.wrap(data));
        sendRaw("42" + arr.toString());
    }

    // ---------------- auth ----------------
    public LoginResult login(String email, String password, boolean isDemo) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final JSONObject[] box = new JSONObject[1];
        EventHandler h = data -> { box[0] = data; latch.countDown(); };
        subscribe("login", h);
        try {
            JSONObject d = new JSONObject();
            d.put("email", email);
            d.put("password", password);
            d.put("isDemo", isDemo ? 1 : 0);
            slog("Login bhej raha...");
            sendEvent("login", d);
            if (!latch.await(30, TimeUnit.SECONDS))
                throw new Exception("Login timeout (30s) — server ne jawab nahi diya");
            JSONObject r = box[0];
            if (r == null) throw new Exception("Login: koi jawab nahi");
            if (!r.optBoolean("isSuccessful", false))
                throw new Exception("Login failed: " + r.optString("message", "ghalat email/password?"));
            String ssid = r.optString("ssid", r.optString("session", ""));
            double demo = r.optDouble("demo_balance", r.optDouble("demoBalance", 10000));
            double live = r.optDouble("real_balance", r.optDouble("liveBalance", 0));
            slog("Login OK");
            return new LoginResult(ssid, demo, live);
        } finally {
            unsubscribe("login", h);
        }
    }

    /** Returns {demoBalance, liveBalance}. */
    public double[] getBalances() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final JSONObject[] box = new JSONObject[1];
        EventHandler h = data -> { box[0] = data; latch.countDown(); };
        subscribe("_ack_response", h);
        try {
            JSONObject d = new JSONObject();
            d.put("_placeholder", true);
            d.put("num", 0);
            sendEvent("s_balance/list", d);
            if (!latch.await(12, TimeUnit.SECONDS)) {
                slog("Balance timeout — default use kar raha");
                return new double[]{10000.0, 0.0};
            }
            JSONObject r = box[0];
            return new double[]{r.optDouble("demoBalance", 10000.0), r.optDouble("liveBalance", 0.0)};
        } finally {
            unsubscribe("_ack_response", h);
        }
    }

    // ---------------- market data ----------------
    public void followDepth(String assetOtc) throws Exception {
        sendEvent("depth/follow", assetOtc);
        slog("Candles subscribe: " + assetOtc);
    }
    public void unfollowDepth(String assetOtc) {
        try { sendEvent("depth/unfollow", assetOtc); } catch (Exception ignored) {}
    }

    // ---------------- trading ----------------
    /**
     * Place binary trade. Returns server ACK JSON.
     * Mirrors desktop: 42["orders/open",{"asset":..,"amount":int,"time":60,
     * "action":"call"/"put","isDemo":1,"tournamentId":0,"requestId":ms,"optionType":100}]
     */
    public JSONObject placeOrder(String assetOtc, double amount, String action, boolean isDemo) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final JSONObject[] box = new JSONObject[1];
        EventHandler h = data -> { box[0] = data; latch.countDown(); };
        subscribe("_ack_response", h);
        subscribe("orders/open", h);
        subscribe("order/created", h);
        try {
            JSONObject o = new JSONObject();
            o.put("asset", assetOtc);
            o.put("amount", (int) amount);
            o.put("time", 60);
            o.put("action", action);
            o.put("isDemo", isDemo ? 1 : 0);
            o.put("tournamentId", 0);
            o.put("requestId", System.currentTimeMillis());
            o.put("optionType", 100);
            sendEvent("orders/open", o);
            if (!latch.await(30, TimeUnit.SECONDS))
                throw new Exception("Order timeout — pata nahi laga order laga ya nahi. Balance check karo!");
            JSONObject r = box[0];
            if (r == null) throw new Exception("Order: koi jawab nahi");
            if (r.optBoolean("error", false) || !r.optBoolean("isSuccessful", true))
                throw new Exception("Order reject: " + r.optString("message", r.toString()));
            return r;
        } finally {
            unsubscribe("_ack_response", h);
            unsubscribe("orders/open", h);
            unsubscribe("order/created", h);
        }
    }

    public void disconnect() {
        running = false;
        if (pollThread != null) { pollThread.interrupt(); pollThread = null; }
        sid = null;
        slog("Disconnected");
    }
    public boolean isConnected() { return running && sid != null; }
}
