package com.nova.signaltrader;

import android.util.Log;

import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Fallback transport: OkHttp with DNS-over-HTTPS.
 * If the phone/ISP DNS cannot resolve ws*.qxbroker.com (Pakistan me kabhi
 * ISP block hota hai), we resolve the hostname ourselves via Google / Cloudflare
 * DoH and connect to the returned IP (SNI still uses the real hostname).
 */
public class OkHttpDohTransport implements HttpTransport {
    private static final String TAG = "OkHttpDoh";
    private static final MediaType TEXT = MediaType.parse("text/plain;charset=UTF-8");

    private final OkHttpClient http;
    // Plain client (system DNS) used ONLY for the DoH queries themselves.
    private final OkHttpClient dohHttp;

    /** DNS that falls back to DNS-over-HTTPS when system DNS fails. */
    private static class DohDns implements Dns {
        private final OkHttpClient dohHttp;
        DohDns(OkHttpClient c) { this.dohHttp = c; }

        @Override public List<InetAddress> lookup(String host) throws UnknownHostException {
            try {
                return Dns.SYSTEM.lookup(host);
            } catch (UnknownHostException first) {
                List<InetAddress> r = dohQuery("https://dns.google/resolve?name=" + host + "&type=A", host);
                if (r == null || r.isEmpty())
                    r = cloudflareQuery(host);
                if (r == null || r.isEmpty()) throw first;
                Log.i(TAG, "DoH resolved " + host + " -> " + r.get(0).getHostAddress());
                return r;
            }
        }

        private List<InetAddress> dohQuery(String url, String host) {
            try {
                Request req = new Request.Builder().url(url)
                        .header("Accept", "application/json")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
                        .get().build();
                try (Response resp = dohHttp.newCall(req).execute()) {
                    if (resp.code() != 200 || resp.body() == null) return null;
                    JSONObject j = new JSONObject(resp.body().string());
                    JSONArray ans = j.optJSONArray("Answer");
                    if (ans == null) return null;
                    List<InetAddress> out = new ArrayList<>();
                    for (int i = 0; i < ans.length(); i++) {
                        JSONObject a = ans.optJSONObject(i);
                        if (a == null) continue;
                        if (a.optInt("type", 0) != 1) continue; // A records only
                        String ip = a.optString("data", "");
                        if (ip.isEmpty() || ip.contains(":")) continue; // skip IPv6
                        try { out.add(InetAddress.getByName(ip)); }
                        catch (Exception ignored) {}
                    }
                    return out;
                }
            } catch (Exception e) {
                Log.w(TAG, "DoH query failed: " + e.getMessage());
                return null;
            }
        }

        private List<InetAddress> cloudflareQuery(String host) {
            try {
                Request req = new Request.Builder()
                        .url("https://cloudflare-dns.com/dns-query?name=" + host + "&type=A")
                        .header("Accept", "application/dns-json")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
                        .get().build();
                try (Response resp = dohHttp.newCall(req).execute()) {
                    if (resp.code() != 200 || resp.body() == null) return null;
                    JSONObject j = new JSONObject(resp.body().string());
                    JSONArray ans = j.optJSONArray("Answer");
                    if (ans == null) return null;
                    List<InetAddress> out = new ArrayList<>();
                    for (int i = 0; i < ans.length(); i++) {
                        JSONObject a = ans.optJSONObject(i);
                        if (a == null || a.optInt("type", 0) != 1) continue;
                        String ip = a.optString("data", "");
                        if (ip.isEmpty() || ip.contains(":")) continue;
                        try { out.add(InetAddress.getByName(ip)); }
                        catch (Exception ignored) {}
                    }
                    return out;
                }
            } catch (Exception e) {
                Log.w(TAG, "CF DoH failed: " + e.getMessage());
                return null;
            }
        }
    }

    public OkHttpDohTransport() {
        dohHttp = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build();
        http = new OkHttpClient.Builder()
                .dns(new DohDns(dohHttp))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    @Override public String name() { return "OkHttp+DoH"; }

    private Request.Builder req(String url, Map<String, String> headers) {
        Request.Builder b = new Request.Builder().url(url);
        if (headers != null)
            for (Map.Entry<String, String> h : headers.entrySet())
                b.header(h.getKey(), h.getValue());
        return b;
    }

    @Override public String get(String url, Map<String, String> headers) throws IOException {
        try (Response resp = http.newCall(req(url, headers).get().build()).execute()) {
            if (resp.code() < 200 || resp.code() >= 300)
                throw new IOException("HTTP " + resp.code());
            return resp.body() != null ? resp.body().string() : "";
        }
    }

    @Override public String post(String url, String body, Map<String, String> headers) throws IOException {
        RequestBody rb = RequestBody.create(body == null ? "" : body, TEXT);
        try (Response resp = http.newCall(req(url, headers).post(rb).build()).execute()) {
            if (resp.code() < 200 || resp.code() >= 300)
                throw new IOException("HTTP " + resp.code());
            return resp.body() != null ? resp.body().string() : "";
        }
    }

    @Override public void shutdown() {
        try { http.dispatcher().executorService().shutdown(); } catch (Exception ignored) {}
        try { http.connectionPool().evictAll(); } catch (Exception ignored) {}
        try { dohHttp.dispatcher().executorService().shutdown(); } catch (Exception ignored) {}
        try { dohHttp.connectionPool().evictAll(); } catch (Exception ignored) {}
    }
}
