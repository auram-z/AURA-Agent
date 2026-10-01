package com.aura.agent.trading.models

enum class MarketType {
    IRAN_STOCK,
    CRYPTO
}

enum class OrderSide {
    BUY, SELL
}

enum class OrderStatus {
    PENDING, FILLED, CANCELLED, REJECTED, SIMULATED
}

enum class TradingMode {
    PAPER,
    LIVE
}

data class Symbol(
    val ticker: String,
    val name: String,
    val market: MarketType,
    val price: Double = 0.0
)

data class TradeSignal(
    val symbol: Symbol,
    val side: OrderSide,
    val confidence: Double,
    val reason: String,
    val suggestedPrice: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class Order(
    val id: String,
    val symbol: Symbol,
    val side: OrderSide,
    val quantity: Double,
    val price: Double,
    val status: OrderStatus,
    val mode: TradingMode,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val reason: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class Position(
    val symbol: Symbol,
    val quantity: Double,
    val entryPrice: Double,
    val currentPrice: Double,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null
) {
    val unrealizedPnl: Double
        get() = (currentPrice - entryPrice) * quantity

    val unrealizedPnlPercent: Double
        get() = if (entryPrice > 0) ((currentPrice - entryPrice) / entryPrice) * 100 else 0.0
}

data class PortfolioSnapshot(
    val cash: Double,
    val positions: List<Position>,
    val totalValue: Double,
    val dailyPnl: Double,
    val dailyPnlPercent: Double
)

data class RiskConfig(
    val maxRiskPerTradePercent: Double = 1.5,
    val maxDailyLossPercent: Double = 3.0,
    val maxOpenPositions: Int = 5,
    val maxPositionPercent: Double = 20.0,
    val minConfidence: Double = 0.65,
    val enableKillSwitch: Boolean = true
)
