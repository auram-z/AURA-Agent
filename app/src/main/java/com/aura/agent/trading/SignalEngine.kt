package com.aura.agent.trading

import com.aura.agent.trading.data.Candle
import com.aura.agent.trading.data.MarketQuote
import com.aura.agent.trading.models.*
import kotlin.math.abs

/**
 * موتور سیگنال v2
 * - SMA Cross (کوتاه / بلند)
 * - RSIنما (مومنتوم)
 * - فیلتر حجم
 */
class SignalEngine {

    fun analyzeQuote(quote: MarketQuote): TradeSignal? {
        val candles = quote.candles
        if (candles.size < 20) return null

        val closes = candles.map { it.close }
        val smaFast = sma(closes, 7)
        val smaSlow = sma(closes, 21)
        val rsi = rsi(closes, 14)
        val price = quote.price

        if (smaFast == null || smaSlow == null || rsi == null) return null

        val prevCloses = closes.dropLast(1)
        val prevSmaFast = sma(prevCloses, 7)
        val prevSmaSlow = sma(prevCloses, 21)

        var side: OrderSide? = null
        var confidence = 0.5
        val reasons = mutableListOf<String>()

        // Golden / Death cross
        if (prevSmaFast != null && prevSmaSlow != null) {
            if (prevSmaFast <= prevSmaSlow && smaFast > smaSlow) {
                side = OrderSide.BUY
                confidence += 0.2
                reasons.add("تقاطع صعودی SMA(7/21)")
            } else if (prevSmaFast >= prevSmaSlow && smaFast < smaSlow) {
                side = OrderSide.SELL
                confidence += 0.2
                reasons.add("تقاطع نزولی SMA(7/21)")
            }
        }

        // RSI
        when {
            rsi < 30 -> {
                if (side == null) side = OrderSide.BUY
                if (side == OrderSide.BUY) confidence += 0.15
                reasons.add("RSI تشنه‌فروش (${String.format("%.0f", rsi)})")
            }
            rsi > 70 -> {
                if (side == null) side = OrderSide.SELL
                if (side == OrderSide.SELL) confidence += 0.15
                reasons.add("RSI تشنه‌خرید (${String.format("%.0f", rsi)})")
            }
            else -> reasons.add("RSI خنثی (${String.format("%.0f", rsi)})")
        }

        // هم‌راستای با تغییر ۲۴ساعته
        if (quote.changePercent24h > 3 && side == OrderSide.BUY) {
            confidence += 0.05
            reasons.add("مومنتوم مثبت 24ساعت")
        } else if (quote.changePercent24h < -3 && side == OrderSide.SELL) {
            confidence += 0.05
            reasons.add("مومنتوم منفی 24ساعت")
        }

        // حجم نسبتاً پایین
        val avgVol = candles.map { it.volume }.average()
        val lastVol = candles.last().volume
        if (avgVol > 0 && lastVol > avgVol * 1.2) {
            confidence += 0.05
            reasons.add("حجم بالاتر از میانگین")
        }

        confidence = confidence.coerceIn(0.0, 0.95)

        if (side == null || confidence < 0.62) return null

        val stopLoss = if (side == OrderSide.BUY) price * 0.98 else price * 1.02
        val takeProfit = if (side == OrderSide.BUY) price * 1.035 else price * 0.965

        val marketLabel = if (quote.symbol.market == MarketType.IRAN_STOCK) "بورس" else "کریپتو"

        return TradeSignal(
            symbol = quote.symbol.copy(price = price),
            side = side,
            confidence = confidence,
            reason = "[$marketLabel] ${reasons.joinToString(" ‌| ")}",
            suggestedPrice = price,
            stopLoss = stopLoss,
            takeProfit = takeProfit
        )
    }

    private fun sma(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        return values.takeLast(period).average()
    }

    /** RSI ساده (Wilder-style approximation) */
    private fun rsi(closes: List<Double>, period: Int = 14): Double? {
        if (closes.size <= period) return null
        var gains = 0.0
        var losses = 0.0
        for (i in closes.size - period until closes.size) {
            val diff = closes[i] - closes[i - 1]
            if (diff >= 0) gains += diff else losses += abs(diff)
        }
        val avgGain = gains / period
        val avgLoss = losses / period
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }
}
