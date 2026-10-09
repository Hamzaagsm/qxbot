package com.nova.signaltrader;

import java.util.List;

/**
 * 1-minute binary strategy v2.0: EMA trend filter + RSI momentum confirmation
 * + STRONG-signal filters.
 *
 * Evaluated once per newly closed 1-minute candle:
 *   - Trend: price vs EMA(slow), and EMA(fast) vs EMA(slow) must agree.
 *   - RSI zone: CALL needs 55 < RSI < 72 (momentum, not overbought);
 *               PUT  needs 28 < RSI < 45 (momentum, not oversold).
 *   - Momentum ignition (strongOnly): RSI must be RISING into a CALL
 *     (or FALLING into a PUT) vs the previous candle — no flat entries.
 *   - Chop filter: skip when EMAs nearly flat (relative gap < 0.02%).
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
