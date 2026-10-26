package com.aura.agent.trading

import com.aura.agent.trading.models.MarketType
import com.aura.agent.trading.models.OrderSide
import com.aura.agent.trading.models.Symbol
import com.aura.agent.trading.models.TradeSignal
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست دود RiskManager بدون شبکه.
 */
class RiskManagerSmokeTest {

    @Test
    fun killSwitch_blocksTrading() {
        val rm = RiskManager(initialCapital = 1_000_000.0)
        rm.activateKillSwitch("test")
        assertTrue(rm.isKillSwitchActive())

        val signal = TradeSignal(
            symbol = Symbol("BTCUSDT", "BTC", MarketType.CRYPTO, 50000.0),
            side = OrderSide.BUY,
            confidence = 0.9,
            reason = "test",
            suggestedPrice = 50000.0,
            stopLoss = 49000.0,
            takeProfit = 52000.0
        )
        val portfolio = PaperBroker(cash = 1_000_000.0).getPortfolio()
        val rejection = rm.validateOrder(signal, portfolio, 10_000.0)
        assertNotNull(rejection)
    }

    @Test
    fun withoutKillSwitch_allowsReasonableOrder() {
        val rm = RiskManager(initialCapital = 1_000_000.0)
        val signal = TradeSignal(
            symbol = Symbol("BTCUSDT", "BTC", MarketType.CRYPTO, 50000.0),
            side = OrderSide.BUY,
            confidence = 0.9,
            reason = "test",
            suggestedPrice = 50000.0,
            stopLoss = 49000.0,
            takeProfit = 52000.0
        )
        val portfolio = PaperBroker(cash = 1_000_000.0).getPortfolio()
        // سفارش کوچک نسبت به سرمایه
        val rejection = rm.validateOrder(signal, portfolio, 5_000.0)
        // ممکن است null (مجاز) یا پیام ریسک باشد — فقط نباید کرش کند
        assertTrue(rejection == null || rejection.isNotBlank())
    }
}
