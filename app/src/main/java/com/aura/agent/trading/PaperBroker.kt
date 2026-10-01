package com.aura.agent.trading

import com.aura.agent.trading.models.*
import java.util.UUID

/**
 * کارگزار شبیه‌سازی (Paper Trading)
 * هیچ پول واقعی جابه‌جا نمی‌شود.
 */
class PaperBroker(
    private var cash: Double = 100_000_000.0  // 100 میلیون ریال پیش‌فرض
) {
    private val positions = mutableMapOf<String, Position>()
    private val orderHistory = mutableListOf<Order>()
    private val priceCache = mutableMapOf<String, Double>()

    fun getCash(): Double = cash

    fun getPositions(): List<Position> = positions.values.toList()

    fun updatePrice(ticker: String, price: Double) {
        priceCache[ticker] = price
        positions[ticker]?.let { pos ->
            positions[ticker] = pos.copy(currentPrice = price)
        }
    }

    fun getPortfolio(): PortfolioSnapshot {
        val posList = positions.values.map { pos ->
            val current = priceCache[pos.symbol.ticker] ?: pos.currentPrice
            pos.copy(currentPrice = current)
        }
        val positionsValue = posList.sumOf { it.currentPrice * it.quantity }
        val total = cash + positionsValue
        return PortfolioSnapshot(
            cash = cash,
            positions = posList,
            totalValue = total,
            dailyPnl = 0.0, // توسط RiskManager محاسبه می‌شود
            dailyPnlPercent = 0.0
        )
    }

    fun execute(signal: TradeSignal, quantity: Double): Order {
        val price = signal.suggestedPrice
            ?: priceCache[signal.symbol.ticker]
            ?: signal.symbol.price

        val orderValue = price * quantity
        val orderId = UUID.randomUUID().toString().take(8)

        when (signal.side) {
            OrderSide.BUY -> {
                if (orderValue > cash) {
                    return Order(
                        id = orderId,
                        symbol = signal.symbol,
                        side = signal.side,
                        quantity = quantity,
                        price = price,
                        status = OrderStatus.REJECTED,
                        mode = TradingMode.PAPER,
                        reason = "موجودی کافی نیست"
                    )
                }
                cash -= orderValue
                val existing = positions[signal.symbol.ticker]
                if (existing != null) {
                    val newQty = existing.quantity + quantity
                    val newEntry = ((existing.entryPrice * existing.quantity) + (price * quantity)) / newQty
                    positions[signal.symbol.ticker] = existing.copy(
                        quantity = newQty,
                        entryPrice = newEntry,
                        currentPrice = price,
                        stopLoss = signal.stopLoss ?: existing.stopLoss,
                        takeProfit = signal.takeProfit ?: existing.takeProfit
                    )
                } else {
                    positions[signal.symbol.ticker] = Position(
                        symbol = signal.symbol.copy(price = price),
                        quantity = quantity,
                        entryPrice = price,
                        currentPrice = price,
                        stopLoss = signal.stopLoss,
                        takeProfit = signal.takeProfit
                    )
                }
            }
            OrderSide.SELL -> {
                val existing = positions[signal.symbol.ticker]
                if (existing == null || existing.quantity < quantity) {
                    return Order(
                        id = orderId,
                        symbol = signal.symbol,
                        side = signal.side,
                        quantity = quantity,
                        price = price,
                        status = OrderStatus.REJECTED,
                        mode = TradingMode.PAPER,
                        reason = "پوزیشن کافی نیست"
                    )
                }
                cash += price * quantity
                val remaining = existing.quantity - quantity
                if (remaining <= 0.0001) {
                    positions.remove(signal.symbol.ticker)
                } else {
                    positions[signal.symbol.ticker] = existing.copy(quantity = remaining, currentPrice = price)
                }
            }
        }

        val order = Order(
            id = orderId,
            symbol = signal.symbol.copy(price = price),
            side = signal.side,
            quantity = quantity,
            price = price,
            status = OrderStatus.SIMULATED,
            mode = TradingMode.PAPER,
            stopLoss = signal.stopLoss,
            takeProfit = signal.takeProfit,
            reason = signal.reason
        )
        orderHistory.add(order)
        return order
    }

    fun getOrderHistory(): List<Order> = orderHistory.toList()

    fun summary(): String {
        val portfolio = getPortfolio()
        return buildString {
            append("💰 پورتفوی Paper:\n")
            append("• نقد: ${format(portfolio.cash)}\n")
            append("• ارزش کل: ${format(portfolio.totalValue)}\n")
            append("• پوزیشن‌ها: ${portfolio.positions.size}\n")
            if (portfolio.positions.isNotEmpty()) {
                append("\nنمادها:\n")
                portfolio.positions.forEach { p ->
                    val pnlSign = if (p.unrealizedPnl >= 0) "+" else ""
                    append("• ${p.symbol.ticker}: ${p.quantity} @ ${format(p.entryPrice)} | اکنون: ${format(p.currentPrice)} | PnL: $pnlSign${format(p.unrealizedPnl)} (${String.format("%.1f", p.unrealizedPnlPercent)}%)\n")
                }
            }
        }
    }

    private fun format(v: Double) = String.format("%,.0f", v)
}
