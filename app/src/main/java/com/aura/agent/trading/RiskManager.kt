package com.aura.agent.trading

import com.aura.agent.trading.models.*

/**
 * مدیریت ریسک سخت‌گیرانه AURA
 * هیچ معامله‌ای بدون عبور از این لایه اجرا نمی‌شود.
 */
class RiskManager(
    private var config: RiskConfig = RiskConfig(),
    private var initialCapital: Double = 100_000_000.0  // ریال — قابل تنظیم
) {
    private var dailyStartEquity: Double = initialCapital
    private var killSwitchActive: Boolean = false
    private val todayTrades = mutableListOf<Order>()

    fun updateConfig(newConfig: RiskConfig) {
        config = newConfig
    }

    fun setInitialCapital(amount: Double) {
        initialCapital = amount
        dailyStartEquity = amount
    }

    fun resetDaily(currentEquity: Double) {
        dailyStartEquity = currentEquity
        todayTrades.clear()
        // kill switch روزانه ریست نمی‌شود مگر کاربر خاموشش کرده باشد
    }

    fun activateKillSwitch(reason: String): String {
        killSwitchActive = true
        return "🛑 Kill Switch فعال شد. دلیل: $reason. هیچ معامله جدیدی اجرا نمی‌شود."
    }

    fun deactivateKillSwitch(): String {
        killSwitchActive = false
        return "✅ Kill Switch خاموش شد. معامله مجدداً امکان‌پذیر است."
    }

    fun isKillSwitchActive(): Boolean = killSwitchActive

    /**
     * بررسی کامل قبل از اجرای هر سفارش
     * @return null اگر مجاز ، در غیر این صورت پیام خطا
     */
    fun validateOrder(
        signal: TradeSignal,
        portfolio: PortfolioSnapshot,
        orderValue: Double
    ): String? {
        if (config.enableKillSwitch && killSwitchActive) {
            return "Kill Switch فعال است. معامله متوقف شده."
        }

        if (signal.confidence < config.minConfidence) {
            return "اعتماد سیگنال (${String.format("%.0f", signal.confidence * 100)}%) کمتر از حداقل مجاز (${String.format("%.0f", config.minConfidence * 100)}%) است."
        }

        // سقف ضرر روزانه
        val dailyLossPercent = if (dailyStartEquity > 0) {
            ((dailyStartEquity - portfolio.totalValue) / dailyStartEquity) * 100
        } else 0.0

        if (dailyLossPercent >= config.maxDailyLossPercent) {
            activateKillSwitch("سقف ضرر روزانه (${String.format("%.1f", dailyLossPercent)}%) رسید")
            return "سقف ضرر روزانه رسیده. Kill Switch فعال شد."
        }

        // تعداد پوزیشن‌های باز
        if (signal.side == OrderSide.BUY && portfolio.positions.size >= config.maxOpenPositions) {
            return "تعداد پوزیشن‌های باز به حداکثر (${config.maxOpenPositions}) رسیده."
        }

        // حداکثر سرمایه روی یک نماد
        val positionPercent = if (portfolio.totalValue > 0) (orderValue / portfolio.totalValue) * 100 else 100.0
        if (positionPercent > config.maxPositionPercent) {
            return "حجم سفارش (${String.format("%.1f", positionPercent)}% پورتفو) بیش از سقف مجاز (${config.maxPositionPercent}%) است."
        }

        // ریسک هر معامله با استفاده از Stop Loss
        if (signal.stopLoss != null && signal.suggestedPrice != null && signal.suggestedPrice > 0) {
            val riskAmount = kotlin.math.abs(signal.suggestedPrice - signal.stopLoss) * (orderValue / signal.suggestedPrice)
            val riskPercent = if (portfolio.totalValue > 0) (riskAmount / portfolio.totalValue) * 100 else 100.0
            if (riskPercent > config.maxRiskPerTradePercent) {
                return "ریسک معامله (${String.format("%.2f", riskPercent)}%) بیش از حداکثر مجاز (${config.maxRiskPerTradePercent}%) است."
            }
        }

        // موجودی نقد
        if (signal.side == OrderSide.BUY && orderValue > portfolio.cash) {
            return "موجودی نقدی کافی نیست. نیاز: ${formatMoney(orderValue)} | موجود: ${formatMoney(portfolio.cash)}"
        }

        return null // مجاز است
    }

    fun recordTrade(order: Order) {
        todayTrades.add(order)
    }

    fun getDailyStats(portfolio: PortfolioSnapshot): String {
        val dailyPnl = portfolio.totalValue - dailyStartEquity
        val dailyPct = if (dailyStartEquity > 0) (dailyPnl / dailyStartEquity) * 100 else 0.0
        return buildString {
            append("📊 آمار روزانه:\n")
            append("• سرمایه اول روز: ${formatMoney(dailyStartEquity)}\n")
            append("• ارزش فعلی: ${formatMoney(portfolio.totalValue)}\n")
            append("• سود/ضرر روز: ${formatMoney(dailyPnl)} (${String.format("%.2f", dailyPct)}%)\n")
            append("• تعداد معاملات: ${todayTrades.size}\n")
            append("• Kill Switch: ${if (killSwitchActive) "🛑 فعال" else "✅ خاموش"}\n")
            append("• سقف ضرر روزانه: ${config.maxDailyLossPercent}%\n")
            append("• ریسک هر معامله: ${config.maxRiskPerTradePercent}%")
        }
    }

    private fun formatMoney(value: Double): String {
        return String.format("%,.0f", value)
    }
}
