"""1-minute binary strategy: EMA trend filter + RSI momentum confirmation.

Logic (evaluated once per newly closed 1-minute candle):
  - Trend: price vs EMA(50), and EMA(20) vs EMA(50) must agree.
  - Trigger: RSI(14) > 55 for CALL, RSI(14) < 45 for PUT.
  - Chop filter: skip when EMA(20) and EMA(50) are nearly flat
    (relative gap < 0.02%), which usually means sideways noise.

This is a commonly used combination for short timeframes. It does NOT
guarantee profit — no indicator does. Backtest/paper-trade first.

All functions here are pure (no network), so they can be unit-tested
offline — see test_strategy.py.
"""


def ema(values, period):
    """Exponential moving average of a list of closes; returns last EMA."""
    if len(values) < period:
        raise ValueError(f"need at least {period} values, got {len(values)}")
    k = 2.0 / (period + 1)
    # seed with simple average of first `period` values
    avg = sum(values[:period]) / period
    for v in values[period:]:
        avg = v * k + avg * (1 - k)
    return avg


def rsi(closes, period=14):
    """RSI with Wilder's smoothing; returns last RSI (0-100)."""
    if len(closes) < period + 1:
        raise ValueError(f"need at least {period + 1} closes, got {len(closes)}")
    gains, losses = [], []
    for i in range(1, period + 1):
        diff = closes[i] - closes[i - 1]
        gains.append(max(diff, 0.0))
        losses.append(max(-diff, 0.0))
    avg_gain = sum(gains) / period
    avg_loss = sum(losses) / period
    for i in range(period + 1, len(closes)):
        diff = closes[i] - closes[i - 1]
        avg_gain = (avg_gain * (period - 1) + max(diff, 0.0)) / period
        avg_loss = (avg_loss * (period - 1) + max(-diff, 0.0)) / period
    if avg_loss == 0:
        return 100.0
    rs = avg_gain / avg_loss
    return 100.0 - (100.0 / (1.0 + rs))


def indicators(closes):
    """Return dict with ema20, ema50, rsi14 for the latest closed candle."""
    return {
        "ema20": ema(closes, 20),
        "ema50": ema(closes, 50),
        "rsi14": rsi(closes, 14),
        "price": closes[-1],
    }


def signal(closes):
    """Return 'call', 'put', or None for the latest closed candle."""
    if len(closes) < 60:
        return None  # not enough history
    ind = indicators(closes)
    price, e20, e50, r = ind["price"], ind["ema20"], ind["ema50"], ind["rsi14"]

    # Chop filter: EMAs too close together -> sideways market, stay out.
    if abs(e20 - e50) / price < 0.0002:
        return None

    if price > e50 and e20 > e50 and r > 55:
        return "call"
    if price < e50 and e20 < e50 and r < 45:
        return "put"
    return None
