package com.hamza.qxbot;

import java.util.List;

/**
 * 1-minute binary strategy: EMA trend filter + RSI momentum confirmation.
 * Direct port of the desktop bot's strategy.py.
 *
 * Evaluated once per newly closed 1-minute candle:
 *   - Trend: price vs EMA(slow), and EMA(fast) vs EMA(slow) must agree.
 *   - Trigger: RSI(14) > 55 for CALL, RSI(14) < 45 for PUT.
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
    public static String signal(List<Double> closes, int emaFast, int emaSlow, int rsiPeriod) {
        int need = emaSlow + 10;
        if (closes.size() < need) return null;
        double ef = ema(closes, emaFast);
        double es = ema(closes, emaSlow);
        double r = rsi(closes, rsiPeriod);
        double price = closes.get(closes.size() - 1);

        // Chop filter: EMAs too close -> sideways, stay out
        if (Math.abs(ef - es) / price < 0.0002) return null;

        if (price > es && ef > es && r > 55) return "call";
        if (price < es && ef < es && r < 45) return "put";
        return null;
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
