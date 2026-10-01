package com.aura.agent.trading

import com.aura.agent.trading.data.Candle
import com.aura.agent.trading.data.MarketDataProvider
import com.aura.agent.trading.data.MarketQuote
import com.aura.agent.trading.models.MarketType
import com.aura.agent.trading.models.OrderSide
import com.aura.agent.trading.models.Symbol
import com.aura.agent.trading.models.TradeSignal
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.max

data class BacktestTrade(
    val ticker: String,
    val side: OrderSide,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val pnl: Double,
    val pnlPercent: Double,
    val reason: String,
    val entryIndex: Int,
    val exitIndex: Int
)

data class BacktestResult(
    val ticker: String,
    val market: MarketType,
    val candlesUsed: Int,
    val trades: List<BacktestTrade>,
    val initialCapital: Double,
    val finalCapital: Double,
    val totalPnlPercent: Double,
    val winRate: Double,
    val maxDrawdownPercent: Double,
    val wins: Int,
    val losses: Int
) {
    fun summary(): String {
        val pnlSign = if (totalPnlPercent >= 0) "+" else ""
        return buildString {
            append("📊 بک‌تست ${ticker}\n")
            append("━━━━━━━━━━━━━━━\n")
            append("• کندل‌ها: $candlesUsed\n")
            append("• تعداد معاملات: ${trades.size} (برد: $wins | باخت: $losses)\n")
            append("• نرخ برد: ${String.format("%.1f", winRate)}%\n")
            append("• بازده کل: $pnlSign${String.format("%.2f", totalPnlPercent)}%\n")
            append("• حداکثر افت سرمایه: ${String.format("%.2f", maxDrawdownPercent)}%\n")
            append("• سرمایه اول: ${formatMoney(initialCapital)}\n")
            append("• سرمایه نهایی: ${formatMoney(finalCapital)}\n")
            if (trades.isNotEmpty()) {
                append("\nآخرین معاملات:\n")
                trades.takeLast(5).forEach { t ->
                    val s = if (t.pnl >= 0) "+" else ""
                    val sideFa = if (t.side == OrderSide.BUY) "لنگ" else "شورت"
                    append("  • $sideFa @ ${fmt(t.entryPrice)} → ${fmt(t.exitPrice)} | $s${String.format("%.2f", t.pnlPercent)}%\n")
                }
            }
        }
    }

    private fun formatMoney(v: Double) = String.format("%,.0f", v)
    private fun fmt(v: Double) = if (v >= 100) String.format("%,.2f", v) else String.format("%,.4f", v)
}

/**
 * بک‌تست walk-forward روی کندل‌ها
 * همان استراتژی SMA+RSI و قوانین ریسک 1.5% / معامله
 */
class BacktestEngine(
    private val initialCapital: Double = 100_000_000.0,
    private val riskPerTrade: Double = 0.015,
    private val minConfidence: Double = 0.62
) {
    private val marketData = MarketDataProvider()
    private val signalEngine = SignalEngine()

    fun runCrypto(ticker: String = "BTCUSDT", candleLimit: Int = 200): BacktestResult {
        return runBlocking {
            val quote = marketData.getCryptoQuote(ticker)
            val candles = quote?.candles ?: emptyList()
            // اگر کندل کم بود، دوباره با حداکثر بیشتر دریافت کن
            val series = if (candles.size >= 30) {
                candles
            } else {
                marketData.getCryptoWatchlist().find {
                    it.symbol.ticker.contains(ticker.replace("USDT", ""), ignoreCase = true)
                }?.candles ?: candles
            }
            runOnCandles(
                ticker = quote?.symbol?.ticker ?: ticker,
                market = MarketType.CRYPTO,
                candles = series
            )
        }
    }

    fun runCryptoBasket(): String {
        return runBlocking {
            val quotes = marketData.getCryptoWatchlist()
            if (quotes.isEmpty()) return@runBlocking "❌ داده کریپتو دریافت نشد."

            val results = quotes.map { q ->
                runOnCandles(q.symbol.ticker, MarketType.CRYPTO, q.candles)
            }

            buildString {
                append("📈 بک‌تست سبد کریپتو (SMA+RSI)\n")
                append("سرمایه اول هر نماد: ${String.format("%,.0f", initialCapital)}\n\n")
                results.forEach { r ->
                    val sign = if (r.totalPnlPercent >= 0) "+" else ""
                    append("${r.ticker}: $sign${String.format("%.2f", r.totalPnlPercent)}% | ")
                    append("WR ${String.format("%.0f", r.winRate)}% | ")
                    append("${r.trades.size} trades | DD ${String.format("%.1f", r.maxDrawdownPercent)}%\n")
                }
                val avg = results.map { it.totalPnlPercent }.average()
                append("\nمیانگین بازده: ${if (avg >= 0) "+" else ""}${String.format("%.2f", avg)}%")
            }
        }
    }

    fun runIranBasket(): String {
        return runBlocking {
            val quotes = marketData.getIranWatchlist()
            val results = quotes.map { q ->
                runOnCandles(q.symbol.ticker, MarketType.IRAN_STOCK, q.candles)
            }
            buildString {
                append("📈 بک‌تست سبد بورس (داده نمونه)\n\n")
                results.forEach { r ->
                    val sign = if (r.totalPnlPercent >= 0) "+" else ""
                    append("${r.ticker}: $sign${String.format("%.2f", r.totalPnlPercent)}% | WR ${String.format("%.0f", r.winRate)}% | ${r.trades.size} trades\n")
                }
            }
        }
    }

    fun runOnCandles(ticker: String, market: MarketType, candles: List<Candle>): BacktestResult {
        if (candles.size < 30) {
            return BacktestResult(
                ticker = ticker,
                market = market,
                candlesUsed = candles.size,
                trades = emptyList(),
                initialCapital = initialCapital,
                finalCapital = initialCapital,
                totalPnlPercent = 0.0,
                winRate = 0.0,
                maxDrawdownPercent = 0.0,
                wins = 0,
                losses = 0
            )
        }

        var capital = initialCapital
        var peak = capital
        var maxDd = 0.0
        val trades = mutableListOf<BacktestTrade>()

        var openSide: OrderSide? = null
        var entryPrice = 0.0
        var entryQty = 0.0
        var entryIndex = 0
        var stopLoss = 0.0
        var takeProfit = 0.0
        var entryReason = ""

        // از ایندیس 21 شروع (نیاز SMA بلند)
        for (i in 21 until candles.size) {
            val window = candles.subList(0, i + 1)
            val price = candles[i].close

            // مدیریت پوزیشن باز
            if (openSide != null) {
                var exit = false
                var exitPx = price

                when (openSide) {
                    OrderSide.BUY -> {
                        if (price <= stopLoss) { exit = true; exitPx = stopLoss }
                        else if (price >= takeProfit) { exit = true; exitPx = takeProfit }
                    }
                    OrderSide.SELL -> {
                        if (price >= stopLoss) { exit = true; exitPx = stopLoss }
                        else if (price <= takeProfit) { exit = true; exitPx = takeProfit }
                    }
                    null -> {}
                }

                // سیگنال مخالف در آخر کندل‌ها
                if (!exit && i == candles.size - 1) {
                    exit = true
                    exitPx = price
                }

                if (!exit) {
                    val quote = MarketQuote(
                        symbol = Symbol(ticker, ticker, market, price),
                        price = price,
                        candles = window
                    )
                    val sig = signalEngine.analyzeQuote(quote)
                    if (sig != null && sig.side != openSide && sig.confidence >= minConfidence) {
                        exit = true
                        exitPx = price
                    }
                }

                if (exit) {
                    val pnl = when (openSide) {
                        OrderSide.BUY -> (exitPx - entryPrice) * entryQty
                        OrderSide.SELL -> (entryPrice - exitPx) * entryQty
                        null -> 0.0
                    }
                    val pnlPct = if (entryPrice > 0) {
                        when (openSide) {
                            OrderSide.BUY -> ((exitPx - entryPrice) / entryPrice) * 100
                            OrderSide.SELL -> ((entryPrice - exitPx) / entryPrice) * 100
                            null -> 0.0
                        }
                    } else 0.0

                    capital += pnl
                    peak = max(peak, capital)
                    val dd = if (peak > 0) ((peak - capital) / peak) * 100 else 0.0
                    if (dd > maxDd) maxDd = dd

                    trades.add(
                        BacktestTrade(
                            ticker = ticker,
                            side = openSide!!,
                            entryPrice = entryPrice,
                            exitPrice = exitPx,
                            quantity = entryQty,
                            pnl = pnl,
                            pnlPercent = pnlPct,
                            reason = entryReason,
                            entryIndex = entryIndex,
                            exitIndex = i
                        )
                    )
                    openSide = null
                }
            }

            // سیگنال ورود (فقط اگر پوزیشن باز نداریم)
            if (openSide == null && i < candles.size - 1) {
                val quote = MarketQuote(
                    symbol = Symbol(ticker, ticker, market, price),
                    price = price,
                    candles = window
                )
                val sig = signalEngine.analyzeQuote(quote)
                if (sig != null && sig.confidence >= minConfidence) {
                    val stopDist = abs(price - (sig.stopLoss ?: price * 0.98))
                    if (stopDist > 0) {
                        val riskAmount = capital * riskPerTrade
                        val qty = riskAmount / stopDist
                        if (qty > 0 && qty * price <= capital * 0.2) {
                            openSide = sig.side
                            entryPrice = price
                            entryQty = qty
                            entryIndex = i
                            stopLoss = sig.stopLoss ?: if (sig.side == OrderSide.BUY) price * 0.98 else price * 1.02
                            takeProfit = sig.takeProfit ?: if (sig.side == OrderSide.BUY) price * 1.035 else price * 0.965
                            entryReason = sig.reason
                        }
                    }
                }
            }
        }

        val wins = trades.count { it.pnl > 0 }
        val losses = trades.count { it.pnl <= 0 }
        val winRate = if (trades.isNotEmpty()) (wins.toDouble() / trades.size) * 100 else 0.0
        val totalPnlPct = if (initialCapital > 0) ((capital - initialCapital) / initialCapital) * 100 else 0.0

        return BacktestResult(
            ticker = ticker,
            market = market,
            candlesUsed = candles.size,
            trades = trades,
            initialCapital = initialCapital,
            finalCapital = capital,
            totalPnlPercent = totalPnlPct,
            winRate = winRate,
            maxDrawdownPercent = maxDd,
            wins = wins,
            losses = losses
        )
    }
}
