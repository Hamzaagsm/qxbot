package com.nova.signaltrader;

import android.content.Context;
import android.util.Log;

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Primary transport: Google Cronet (the real Chromium network stack).
 * TLS fingerprint is identical to Chrome on Android -> Quotex/Cloudflare
 * sees a normal browser, not a bot library.
 */
public class CronetTransport implements HttpTransport {
    private static final String TAG = "CronetTransport";
    private final CronetEngine engine;
    private final java.util.concurrent.Executor executor =
            Executors.newSingleThreadExecutor();

    public CronetTransport(Context ctx) {
        CronetEngine.Builder b = new CronetEngine.Builder(ctx.getApplicationContext());
        b.enableHttp2(true);
        b.enableQuic(false); // keep it plain HTTPS/2 like normal page loads
        this.engine = b.build();
    }

    @Override public String name() { return "Cronet/Chrome"; }

    private static class SyncCallback extends UrlRequest.Callback {
        final CountDownLatch latch = new CountDownLatch(1);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final AtomicReference<CronetException> error = new AtomicReference<>();
        volatile int statusCode = -1;

        @Override public void onRedirectReceived(UrlRequest req, UrlResponseInfo info, String newUrl) {
            req.followRedirect();
        }
        @Override public void onResponseStarted(UrlRequest req, UrlResponseInfo info) {
            statusCode = info.getHttpStatusCode();
            req.read(ByteBuffer.allocateDirect(32 * 1024));
        }
        @Override public void onReadCompleted(UrlRequest req, UrlResponseInfo info, ByteBuffer buf) {
            buf.flip();
            byte[] chunk = new byte[buf.remaining()];
            buf.get(chunk);
            out.write(chunk, 0, chunk.length);
            buf.clear();
            req.read(buf);
        }
        @Override public void onSucceeded(UrlRequest req, UrlResponseInfo info) {
            latch.countDown();
        }
        @Override public void onFailed(UrlRequest req, UrlResponseInfo info, CronetException e) {
            error.set(e);
            latch.countDown();
        }
    }

    private String execute(String url, String method, String body, Map<String, String> headers)
            throws IOException {
        SyncCallback cb = new SyncCallback();
        UrlRequest.Builder rb = engine.newUrlRequestBuilder(url, cb, executor)
                .setHttpMethod(method)
                .setPriority(UrlRequest.Builder.REQUEST_PRIORITY_MEDIUM);
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet())
                rb.addHeader(h.getKey(), h.getValue());
        }
        if ("POST".equals(method)) {
            byte[] bytes;
            try { bytes = body.getBytes("UTF-8"); }
            catch (Exception e) { bytes = body.getBytes(); }
            rb.setUploadDataProvider(
                    org.chromium.net.UploadDataProviders.create(bytes), executor);
            rb.addHeader("Content-Type", "text/plain;charset=UTF-8");
        }
        UrlRequest req = rb.build();
        req.start();
        try {
            if (!cb.latch.await(45, TimeUnit.SECONDS))
                throw new IOException("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        CronetException err = cb.error.get();
        if (err != null) {
            String m = err.getMessage();
            throw new IOException(m != null && !m.isEmpty() ? m : "network error");
        }
        if (cb.statusCode < 200 || cb.statusCode >= 300)
            throw new IOException("HTTP " + cb.statusCode);
        try { return cb.out.toString("UTF-8"); }
        catch (Exception e) { return cb.out.toString(); }
    }

    @Override public String get(String url, Map<String, String> headers) throws IOException {
        return execute(url, "GET", null, headers);
    }

    @Override public String post(String url, String body, Map<String, String> headers) throws IOException {
        return execute(url, "POST", body == null ? "" : body, headers);
    }

    @Override public void shutdown() {
        try { engine.shutdown(); } catch (Exception e) { Log.w(TAG, "shutdown", e); }
        try { ((java.util.concurrent.ExecutorService) executor).shutdownNow(); }
        catch (Exception ignored) {}
    }
}
