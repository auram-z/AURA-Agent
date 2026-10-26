package com.aura.agent.trading

import com.aura.agent.trading.data.MarketDataProvider
import com.aura.agent.trading.models.*

/**
 * موتور معامله‌گری AURA v2
 * - داده واقعی کریپتو (Binance)
 * - تحلیل SMA + RSI
 * - فقط Paper Trading
 *
 * همه متدهای عمومی suspend هستند تا از قفل شدن نخ UI جلوگیری شود.
 */
class TradingEngine(
    initialCapital: Double = 100_000_000.0
) {
    private val riskManager = RiskManager(initialCapital = initialCapital)
    private val paperBroker = PaperBroker(cash = initialCapital)
    private val signalEngine = SignalEngine()
    private val marketData = MarketDataProvider()

    private var mode: TradingMode = TradingMode.PAPER
    private var autoEnabled: Boolean = false

    fun getMode(): TradingMode = mode
    fun isAutoEnabled(): Boolean = autoEnabled

    fun setAutoTrading(enabled: Boolean): String {
        if (mode == TradingMode.LIVE && enabled) {
            return "⚠️ معامله واقعی هنوز فعال نیست. فقط Paper Trading."
        }
        autoEnabled = enabled
        return if (enabled) {
            "✅ معامله خودکار Paper فعال شد. پول واقعی جابه‌جا نمی‌شود."
        } else {
            "🛑 معامله خودکار خاموش شد."
        }
    }

    fun activateKillSwitch(reason: String = "دستور کاربر"): String {
        autoEnabled = false
        return riskManager.activateKillSwitch(reason)
    }

    fun deactivateKillSwitch(): String = riskManager.deactivateKillSwitch()

    suspend fun runScan(market: MarketType): String {
        val quotes = when (market) {
            MarketType.CRYPTO -> marketData.getCryptoWatchlist()
            MarketType.IRAN_STOCK -> marketData.getIranWatchlist()
        }

        if (quotes.isEmpty()) {
            return "❌ داده بازار دریافت نشد. اتصال اینترنت را بررسی کنید."
        }

        val results = mutableListOf<String>()
        var executed = 0

        for (quote in quotes) {
            // به‌روزرسانی قیمت در PaperBroker
            paperBroker.updatePrice(quote.symbol.ticker, quote.price)

            val signal = signalEngine.analyzeQuote(quote)
            val priceLine = "${quote.symbol.ticker}: ${formatPrice(quote.price)} (${String.format("%+.1f", quote.changePercent24h)}%)"

            if (signal == null) {
                results.add("⏸️ $priceLine — بدون سیگنال")
                continue
            }

            val portfolio = paperBroker.getPortfolio()
            val riskAmount = portfolio.totalValue * 0.015
            val price = signal.suggestedPrice ?: quote.price
            val stopDistance = kotlin.math.abs(price - (signal.stopLoss ?: price * 0.98))
            val quantity = if (stopDistance > 0) riskAmount / stopDistance else 0.0
            if (quantity <= 0) continue

            val orderValue = quantity * price
            val rejection = riskManager.validateOrder(signal, portfolio, orderValue)

            if (rejection != null) {
                results.add("❌ $priceLine — $rejection")
                continue
            }

            if (autoEnabled && mode == TradingMode.PAPER) {
                val order = paperBroker.execute(signal, quantity)
                riskManager.recordTrade(order)
                executed++
                val sideText = if (order.side == OrderSide.BUY) "خرید" else "فروش"
                results.add("✅ $priceLine → $sideText ${String.format("%.4f", order.quantity)} | ${signal.reason}")
            } else {
                val sideText = if (signal.side == OrderSide.BUY) "خرید" else "فروش"
                results.add("📊 $priceLine → سیگنال $sideText (${String.format("%.0f", signal.confidence * 100)}%) | ${signal.reason}")
            }
        }

        val marketName = if (market == MarketType.IRAN_STOCK) "بورس ایران" else "کریپتو"
        return buildString {
            append("🔍 اسکن $marketName\n")
            append("داده: ${if (market == MarketType.CRYPTO) "Binance Live" else "نمونه/ساختاری"} | Paper | خودکار: ${if (autoEnabled) "روشن" else "خاموش"}\n\n")
            results.forEach { append("$it\n") }
            if (executed > 0) append("\n📈 $executed معامله شبیه‌سازی اجرا شد.")
        }
    }

    fun getStatus(): String {
        val portfolio = paperBroker.getPortfolio()
        return buildString {
            append("🌌 AURA Trading Status\n")
            append("━━━━━━━━━━━━━━━\n")
            append("حالت: 📝 Paper (بدون پول واقعی)\n")
            append("خودکار: ${if (autoEnabled) "✅ فعال" else "⏸️ خاموش"}\n")
            append("Kill Switch: ${if (riskManager.isKillSwitchActive()) "🛑 فعال" else "✅ خاموش"}\n")
            append("داده کریپتو: Binance Public API\n")
            append("تحلیل: SMA(7/21) + RSI(14)\n\n")
            append(paperBroker.summary())
            append("\n")
            append(riskManager.getDailyStats(portfolio))
        }
    }

    suspend fun manualOrder(
        ticker: String,
        side: OrderSide,
        market: MarketType,
        quantity: Double? = null
    ): String {
        val quote = when (market) {
            MarketType.CRYPTO -> marketData.getCryptoQuote(ticker)
            MarketType.IRAN_STOCK -> marketData.getIranWatchlist().find {
                it.symbol.ticker.equals(ticker, ignoreCase = true)
            }
        }

        val symbol = quote?.symbol ?: Symbol(ticker, ticker, market)
        val price = quote?.price ?: symbol.price
        if (quote != null) paperBroker.updatePrice(symbol.ticker, price)

        val signal = (quote?.let { signalEngine.analyzeQuote(it) }) ?: TradeSignal(
            symbol = symbol.copy(price = price),
            side = side,
            confidence = 0.75,
            reason = "دستور دستی کاربر",
            suggestedPrice = price,
            stopLoss = if (side == OrderSide.BUY) price * 0.98 else price * 1.02,
            takeProfit = if (side == OrderSide.BUY) price * 1.03 else price * 0.97
        ).copy(side = side)

        val portfolio = paperBroker.getPortfolio()
        val px = signal.suggestedPrice ?: price
        val qty = quantity ?: run {
            val riskAmount = portfolio.totalValue * 0.015
            val stopDistance = kotlin.math.abs(px - (signal.stopLoss ?: px * 0.98))
            if (stopDistance > 0) riskAmount / stopDistance else 0.0
        }

        val rejection = riskManager.validateOrder(signal.copy(side = side), portfolio, qty * px)
        if (rejection != null) return "❌ $rejection"

        val order = paperBroker.execute(signal.copy(side = side), qty)
        riskManager.recordTrade(order)

        return if (order.status == OrderStatus.SIMULATED || order.status == OrderStatus.FILLED) {
            "✅ سفارش ${order.side} ${order.symbol.ticker} (Paper)\nتعداد: ${String.format("%.4f", order.quantity)} | قیمت: ${formatPrice(order.price)}"
        } else {
            "❌ سفارش رد شد: ${order.reason}"
        }
    }

    private fun formatPrice(v: Double): String {
        return if (v >= 100) String.format("%,.2f", v) else String.format("%,.4f", v)
    }
}
