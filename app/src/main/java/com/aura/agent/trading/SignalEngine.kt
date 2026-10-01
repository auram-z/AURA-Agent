package com.aura.agent.trading

import com.aura.agent.trading.models.*
import kotlin.random.Random

/**
 * موتور تولید سیگنال
 * نسخه ۱: قوانین + قواعد ساده (برای Paper Trading)
 * در نسخه‌های بعدی می‌توان به استراتژی‌های واقعی (تکنیکال، RSI، ...) متصل شود.
 */
class SignalEngine {

    /**
     * تحلیل ساده یک نماد — برای دمو و تست سیستم
     * در تولید واقعی باید با داده بازار جایگزین شود.
     */
    fun analyze(symbol: Symbol, marketBias: String = "neutral"): TradeSignal? {
        // شبیه‌سازی ساده — فقط برای اینکه جریان سیستم قابل تست باشد
        val roll = Random.nextDouble()
        val confidence = 0.55 + Random.nextDouble() * 0.35

        if (confidence < 0.65) return null

        val side = when {
            marketBias == "bullish" -> OrderSide.BUY
            marketBias == "bearish" -> OrderSide.SELL
            roll > 0.5 -> OrderSide.BUY
            else -> OrderSide.SELL
        }

        val price = if (symbol.price > 0) symbol.price else randomPrice(symbol.market)
        val stopLoss = if (side == OrderSide.BUY) price * 0.98 else price * 1.02
        val takeProfit = if (side == OrderSide.BUY) price * 1.03 else price * 0.97

        val reason = buildString {
            append("تحلیل ${if (symbol.market == MarketType.IRAN_STOCK) "بورس" else "کریپتو"}: ")
            append(if (side == OrderSide.BUY) "سیگنال خرید" else "سیگنال فروش")
            append(" | اعتماد: ${String.format("%.0f", confidence * 100)}%")
            append(" (Paper — استراتژی واقعی هنوز وصل نشده)")
        }

        return TradeSignal(
            symbol = symbol.copy(price = price),
            side = side,
            confidence = confidence,
            reason = reason,
            suggestedPrice = price,
            stopLoss = stopLoss,
            takeProfit = takeProfit
        )
    }

    fun getWatchlist(market: MarketType): List<Symbol> {
        return when (market) {
            MarketType.IRAN_STOCK -> listOf(
                Symbol("وامید", "بانک ملت", MarketType.IRAN_STOCK, 3500.0),
                Symbol("شپنا", "گروه پتروشیمی", MarketType.IRAN_STOCK, 12800.0),
                Symbol("خودرو", "خودرو", MarketType.IRAN_STOCK, 4200.0),
                Symbol("فولاد", "فولاد مبارکه", MarketType.IRAN_STOCK, 8900.0),
                Symbol("وبصادر", "بصادر", MarketType.IRAN_STOCK, 2100.0)
            )
            MarketType.CRYPTO -> listOf(
                Symbol("BTC/USDT", "Bitcoin", MarketType.CRYPTO, 65000.0),
                Symbol("ETH/USDT", "Ethereum", MarketType.CRYPTO, 3500.0),
                Symbol("SOL/USDT", "Solana", MarketType.CRYPTO, 150.0),
                Symbol("BNB/USDT", "BNB", MarketType.CRYPTO, 580.0),
                Symbol("XRP/USDT", "XRP", MarketType.CRYPTO, 0.55)
            )
        }
    }

    private fun randomPrice(market: MarketType): Double {
        return when (market) {
            MarketType.IRAN_STOCK -> 1000.0 + Random.nextDouble() * 15000
            MarketType.CRYPTO -> 10.0 + Random.nextDouble() * 5000
        }
    }
}
