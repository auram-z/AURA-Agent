package com.aura.agent.ai.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.aura.agent.trading.TradingEngine
import com.aura.agent.trading.models.MarketType
import com.aura.agent.trading.models.OrderSide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AuraTools(private val context: Context) {

    private val tradingEngine = TradingEngine()

    fun getCurrentTime(): String {
        val sdf = SimpleDateFormat("HH:mm — EEEE d MMMM yyyy", Locale("fa"))
        return "زمان فعلی: ${sdf.format(Date())}"
    }

    fun getBatteryInfo(): String {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else -1
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return if (batteryPct >= 0) {
            "سطح باتری: $batteryPct% ${if (isCharging) "(در حال شارژ)" else ""}"
        } else "اطلاعات باتری در دسترس نیست"
    }

    fun getDeviceInfo(): String {
        return "اطلاعات دستگاه:\n- مدل: ${Build.MODEL}\n- سازنده: ${Build.MANUFACTURER}\n- اندروید: ${Build.VERSION.RELEASE}"
    }

    // ─── Trading Tools ───

    fun tradingStatus(): String = tradingEngine.getStatus()

    fun scanIranStock(): String = tradingEngine.runScan(MarketType.IRAN_STOCK)

    fun scanCrypto(): String = tradingEngine.runScan(MarketType.CRYPTO)

    fun enableAutoTrading(): String = tradingEngine.setAutoTrading(true)

    fun disableAutoTrading(): String = tradingEngine.setAutoTrading(false)

    fun killSwitch(reason: String = "دستور کاربر"): String = tradingEngine.activateKillSwitch(reason)

    fun releaseKillSwitch(): String = tradingEngine.deactivateKillSwitch()

    fun buy(ticker: String, market: String = "crypto"): String {
        val m = if (market.contains("ایران") || market.contains("stock")) MarketType.IRAN_STOCK else MarketType.CRYPTO
        return tradingEngine.manualOrder(ticker, OrderSide.BUY, m)
    }

    fun sell(ticker: String, market: String = "crypto"): String {
        val m = if (market.contains("ایران") || market.contains("stock")) MarketType.IRAN_STOCK else MarketType.CRYPTO
        return tradingEngine.manualOrder(ticker, OrderSide.SELL, m)
    }

    fun getAvailableToolsDescription(): String {
        return """
ابزارهای AURA:
1. get_time / زمان
2. get_battery / باتری
3. get_device_info / دستگاه
4. trading_status / وضعیت معاملات
5. scan_iran / اسکن بورس ایران
6. scan_crypto / اسکن کریپتو
7. auto_on / فعال‌سازی معامله خودکار (Paper)
8. auto_off / خاموش معامله خودکار
9. kill_switch / توقف اورژانسی همه معاملات
10. buy / sell - سفارش دستی (Paper)

⚠️ همه معاملات فعلاً در حالت Paper Trading هستند (بدون پول واقعی).
        """.trim()
    }

    fun executeTool(toolName: String, arg: String = ""): String {
        return when (toolName.lowercase()) {
            "get_time", "time", "زمان" -> getCurrentTime()
            "get_battery", "battery", "باتری" -> getBatteryInfo()
            "get_device_info", "device", "دستگاه" -> getDeviceInfo()
            "trading_status", "status", "وضعیت", "پورتفو" -> tradingStatus()
            "scan_iran", "اسکن بورس", "بورس" -> scanIranStock()
            "scan_crypto", "اسکن کریپتو", "کریپتو" -> scanCrypto()
            "auto_on", "خودکار روش
" -> enableAutoTrading()
            "auto_off", "خودکار خاموش" -> disableAutoTrading()
            "kill_switch", "توقف" -> killSwitch(arg.ifBlank { "دستور کاربر" })
            "release_kill", "ادامه" -> releaseKillSwitch()
            else -> "ابزار «$toolName» شناخته است."
        }
    }
}
