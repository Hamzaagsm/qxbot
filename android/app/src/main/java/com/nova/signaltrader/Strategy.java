package com.nova.signaltrader;

import java.util.List;

/**
 * 1-minute binary strategy v2.1: EMA trend filter + RSI momentum confirmation
 * + STRONG-signal filters + ATR volatility guard + candle confirmation.
 *
 * Evaluated once per newly closed 1-minute candle:
 *   - Trend: price vs EMA(slow), and EMA(fast) vs EMA(slow) must agree.
 *   - RSI zone: CALL needs 55 < RSI < 72 (momentum, not overbought);
 *               PUT  needs 28 < RSI < 45 (momentum, not oversold).
 *   - Momentum ignition (strongOnly): RSI must be RISING into a CALL
 *     (or FALLING into a PUT) vs the previous candle — no flat entries.
 *   - Chop filter: skip when EMAs nearly flat (relative gap < 0.02%).
 *   - v2.1 ATR guard: skip when volatility too low (dead market) or
 *     too high (news spike — unpredictable).
 *   - v2.1 Candle confirmation: last candle must close in signal direction
 *     with decent body (no doji entries).
 *
 * No indicator guarantees profit. Demo-first!
 */
public class Strategy {

    public static double ema(List<Double> values, int period) {
        if (values.size() < period)
            throw new IllegalArgumentException("need at least " + period + " values");
        double k = 2.0 / (period + 1);
        double avg = 0;
        for (int i = 0; i < period; i++) avg += values.get(i);
        avg /= period;
        for (int i = period; i < values.size(); i++)
            avg = values.get(i) * k + avg * (1 - k);
        return avg;
    }

    public static double rsi(List<Double> closes, int period) {
        if (closes.size() < period + 1)
            throw new IllegalArgumentException("need at least " + (period + 1) + " closes");
        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double d = closes.get(i) - closes.get(i - 1);
            gain += Math.max(d, 0);
            loss += Math.max(-d, 0);
        }
        double ag = gain / period, al = loss / period;
        for (int i = period + 1; i < closes.size(); i++) {
            double d = closes.get(i) - closes.get(i - 1);
            ag = (ag * (period - 1) + Math.max(d, 0)) / period;
            al = (al * (period - 1) + Math.max(-d, 0)) / period;
        }
        if (al == 0) return 100.0;
        double rs = ag / al;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /**
     * v2.1: Average True Range — volatility measure.
     * Needs highs, lows, closes (same length).
     */
    public static double atr(List<Double> highs, List<Double> lows,
                             List<Double> closes, int period) {
        int n = closes.size();
        if (n < period + 1 || highs.size() < n || lows.size() < n)
            throw new IllegalArgumentException("need at least " + (period + 1) + " candles");
        double sum = 0;
        for (int i = n - period; i < n; i++) {
            double tr = Math.max(highs.get(i) - lows.get(i),
                    Math.max(Math.abs(highs.get(i) - closes.get(i - 1)),
                             Math.abs(lows.get(i) - closes.get(i - 1))));
            sum += tr;
        }
        return sum / period;
    }

    /**
     * v2.1: Full signal with OHLC data for ATR + candle confirmation.
     * @return "call", "put", or null (no signal).
     * Needs slow+10 closes minimum.
     */
    public static String signal(List<Double> opens, List<Double> highs,
                                List<Double> lows, List<Double> closes,
                                int emaFast, int emaSlow, int rsiPeriod,
                                boolean strongOnly) {
        int need = emaSlow + 10;
        if (closes.size() < need) return null;
        double ef = ema(closes, emaFast);
        double es = ema(closes, emaSlow);
        double r = rsi(closes, rsiPeriod);
        double price = closes.get(closes.size() - 1);

        // Chop filter: EMAs too close -> sideways market, stay out
        if (Math.abs(ef - es) / price < 0.0002) return null;

        // v2.1 ATR volatility guard: skip dead markets & news spikes
        if (strongOnly && highs.size() >= need && lows.size() >= need) {
            try {
                double a = atr(highs, lows, closes, 14);
                double atrPct = a / price;
                // Too flat (< 0.015%) = dead market, too wild (> 0.15%) = news spike
                if (atrPct < 0.00015 || atrPct > 0.0015) return null;
            } catch (Exception ignored) {}
        }

        // v2.1 Candle confirmation: last candle must have real body in signal direction
        boolean candleOkCall = true, candleOkPut = true;
        if (strongOnly && opens.size() >= need) {
            double o = opens.get(opens.size() - 1);
            double c = price;
            double bodyPct = Math.abs(c - o) / price;
            // Doji ya bohat chhoti body = no trade
            if (bodyPct < 0.00005) return null;
            candleOkCall = c > o;
            candleOkPut = c < o;
        }

        // Momentum ignition: RSI direction vs previous candle
        double rPrev = 50.0;
        boolean havePrev = false;
        if (strongOnly && closes.size() > need + 1) {
            try {
                rPrev = rsi(closes.subList(0, closes.size() - 1), rsiPeriod);
                havePrev = true;
            } catch (Exception ignored) {}
        }

        // CALL: uptrend + RSI momentum zone + RSI not fading + candle confirms
        if (price > es && ef > es && r > 55 && r < 72 && candleOkCall) {
            if (strongOnly && havePrev && r < rPrev) return null; // fading momentum
            return "call";
        }
        // PUT: downtrend + RSI momentum zone + RSI not fading + candle confirms
        if (price < es && ef < es && r < 45 && r > 28 && candleOkPut) {
            if (strongOnly && havePrev && r > rPrev) return null; // fading momentum
            return "put";
        }
        return null;
    }

    /**
     * @return "call", "put", or null (no signal).
     * Needs slow+10 closes minimum.
     */
    public static String signal(List<Double> closes, int emaFast, int emaSlow,
                                int rsiPeriod, boolean strongOnly) {
        int need = emaSlow + 10;
        if (closes.size() < need) return null;
        double ef = ema(closes, emaFast);
        double es = ema(closes, emaSlow);
        double r = rsi(closes, rsiPeriod);
        double price = closes.get(closes.size() - 1);

        // Chop filter: EMAs too close -> sideways market, stay out
        if (Math.abs(ef - es) / price < 0.0002) return null;

        // Momentum ignition: RSI direction vs previous candle
        double rPrev = 50.0;
        boolean havePrev = false;
        if (strongOnly && closes.size() > need + 1) {
            try {
                rPrev = rsi(closes.subList(0, closes.size() - 1), rsiPeriod);
                havePrev = true;
            } catch (Exception ignored) {}
        }

        // CALL: uptrend + RSI momentum zone + RSI not fading (not overbought)
        if (price > es && ef > es && r > 55 && r < 72) {
            if (strongOnly && havePrev && r < rPrev) return null; // fading momentum
            return "call";
        }
        // PUT: downtrend + RSI momentum zone + RSI not fading (not oversold)
        if (price < es && ef < es && r < 45 && r > 28) {
            if (strongOnly && havePrev && r > rPrev) return null; // fading momentum
            return "put";
        }
        return null;
    }

    /** Backward-compatible: strong filters ON by default. */
    public static String signal(List<Double> closes, int emaFast, int emaSlow, int rsiPeriod) {
        return signal(closes, emaFast, emaSlow, rsiPeriod, true);
    }

    public static String describe(List<Double> closes, int emaFast, int emaSlow, int rsiPeriod) {
        try {
            return String.format("EMA%d=%.5f EMA%d=%.5f RSI=%d:%.1f",
                    emaFast, ema(closes, emaFast), emaSlow, ema(closes, emaSlow),
                    rsiPeriod, rsi(closes, rsiPeriod));
        } catch (Exception e) {
            return "n/a";
        }
    }
}
