package com.aura.agent.trading

import com.aura.agent.trading.models.*

/**
 * موتور اصلی معامله‌گری AURA
 * حالت فعلی: فقط Paper Trading
 * معامله واقعی تا تأیید صریح و اتصال API خاموش است.
 */
class TradingEngine(
    initialCapital: Double = 100_000_000.0
) {
    private val riskManager = RiskManager(initialCapital = initialCapital)
    private val paperBroker = PaperBroker(cash = initialCapital)
    private val signalEngine = SignalEngine()

    private var mode: TradingMode = TradingMode.PAPER
    private var autoEnabled: Boolean = false

    fun getMode(): TradingMode = mode
    fun isAutoEnabled(): Boolean = autoEnabled

    fun setAutoTrading(enabled: Boolean): String {
        if (mode == TradingMode.LIVE && enabled) {
            return "⚠️ معامله واقعی هنوز فعال نیست. ابتدا در حالت Paper باقی می‌ماند."
        }
        autoEnabled = enabled
        return if (enabled) {
            "✅ معامله خودکار Paper فعال شد. هیچ پول واقعی جابه‌جا نمی‌شود."
        } else {
            "🛑 معامله خودکار خاموش شد."
        }
    }

    fun activateKillSwitch(reason: String = "دستور کاربر"): String {
        autoEnabled = false
        return riskManager.activateKillSwitch(reason)
    }

    fun deactivateKillSwitch(): String = riskManager.deactivateKillSwitch()

    /**
     * اجرای یک چرخه تحلیل روی لیست نظارت
     */
    fun runScan(market: MarketType): String {
        val watchlist = signalEngine.getWatchlist(market)
        val results = mutableListOf<String>()
        var executed = 0

        for (symbol in watchlist) {
            val signal = signalEngine.analyze(symbol) ?: continue
            val portfolio = paperBroker.getPortfolio()

            // حجم معامله بر اساس ریسک
            val riskAmount = portfolio.totalValue * (riskManagerConfig().maxRiskPerTradePercent / 100.0)
            val price = signal.suggestedPrice ?: symbol.price
            val stopDistance = if (signal.stopLoss != null) kotlin.math.abs(price - signal.stopLoss) else price * 0.02
            val quantity = if (stopDistance > 0) riskAmount / stopDistance else 0.0

            if (quantity <= 0) continue

            val orderValue = quantity * price
            val rejection = riskManager.validateOrder(signal, portfolio, orderValue)

            if (rejection != null) {
                results.add("❌ ${symbol.ticker}: $rejection")
                continue
            }

            if (autoEnabled && mode == TradingMode.PAPER) {
                val order = paperBroker.execute(signal, quantity)
                riskManager.recordTrade(order)
                executed++
                val sideText = if (order.side == OrderSide.BUY) "خرید" else "فروش"
                results.add("✅ ${symbol.ticker}: $sideText ${String.format("%.2f", order.quantity)} @ ${String.format("%,.0f", order.price)} | ${order.reason}")
            } else {
                val sideText = if (signal.side == OrderSide.BUY) "خرید" else "فروش"
                results.add("📊 ${symbol.ticker}: سیگنال $sideText | اعتماد ${String.format("%.0f", signal.confidence * 100)}% | ${signal.reason}")
            }
        }

        val marketName = if (market == MarketType.IRAN_STOCK) "بورس ایران" else "کریپتو"
        return buildString {
            append("🔍 اسکن $marketName تمام شد\n")
            append("حالت: ${if (mode == TradingMode.PAPER) "Paper Trading" else "LIVE"} | خودکار: ${if (autoEnabled) "روش
" else "خاموش"}\n\n")
            if (results.isEmpty()) {
                append("سیگنال قوی مجازی یافت نشد.")
            } else {
                results.forEach { append("$it\n") }
                if (executed > 0) append("\n📈 $executed معامله شبیه‌سازی اجرا شد.")
            }
        }
    }

    fun getStatus(): String {
        val portfolio = paperBroker.getPortfolio()
        return buildString {
            append("🌌 AURA Trading Status\n")
            append("━━━━━━━━━━━━━━━\n")
            append("حالت: ${if (mode == TradingMode.PAPER) "📝 Paper (بدون پول واقعی)" else "🔴 LIVE"}\n")
            append("خودکار: ${if (autoEnabled) "✅ فعال" else "⏸️ خاموش"}\n")
            append("Kill Switch: ${if (riskManager.isKillSwitchActive()) "🛑 فعال" else "✅ خاموش"}\n\n")
            append(paperBroker.summary())
            append("\n")
            append(riskManager.getDailyStats(portfolio))
        }
    }

    fun manualOrder(ticker: String, side: OrderSide, market: MarketType, quantity: Double? = null): String {
        val watchlist = signalEngine.getWatchlist(market)
        val symbol = watchlist.find { it.ticker.equals(ticker, ignoreCase = true) }
            ?: Symbol(ticker, ticker, market)

        val signal = signalEngine.analyze(symbol) ?: TradeSignal(
            symbol = symbol,
            side = side,
            confidence = 0.8,
            reason = "دستور دستی کاربر",
            suggestedPrice = symbol.price,
            stopLoss = if (side == OrderSide.BUY) symbol.price * 0.98 else symbol.price * 1.02,
            takeProfit = if (side == OrderSide.BUY) symbol.price * 1.03 else symbol.price * 0.97
        ).copy(side = side)

        val portfolio = paperBroker.getPortfolio()
        val price = signal.suggestedPrice ?: symbol.price
        val qty = quantity ?: run {
            val riskAmount = portfolio.totalValue * (riskManagerConfig().maxRiskPerTradePercent / 100.0)
            val stopDistance = kotlin.math.abs(price - (signal.stopLoss ?: price * 0.98))
            if (stopDistance > 0) riskAmount / stopDistance else 0.0
        }

        val rejection = riskManager.validateOrder(signal.copy(side = side), portfolio, qty * price)
        if (rejection != null) return "❌ $rejection"

        val order = paperBroker.execute(signal.copy(side = side), qty)
        riskManager.recordTrade(order)

        return if (order.status == OrderStatus.SIMULATED || order.status == OrderStatus.FILLED) {
            "✅ سفارش ${order.side} ${order.symbol.ticker} ثبت شد (Paper)\nتعداد: ${String.format("%.2f", order.quantity)} | قیمت: ${String.format("%,.0f", order.price)}"
        } else {
            "❌ سفارش رد شد: ${order.reason}"
        }
    }

    private fun riskManagerConfig() = RiskConfig()
}
