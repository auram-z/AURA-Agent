package com.aura.agent.ai.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import com.aura.agent.trading.BacktestEngine
import com.aura.agent.trading.TradingEngine
import com.aura.agent.trading.models.MarketType
import com.aura.agent.trading.models.OrderSide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AuraTools(private val context: Context) {

    private val tradingEngine = TradingEngine()
    private val backtestEngine = BacktestEngine()

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

    /** وضعیت اتصال به شبکه سراسری اینترنت جهانی */
    fun networkStatus(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        if (network == null) {
            return "🌐 وضعیت شبکه:\n• اتصال: ❌ آفلاین\n• اینترنت جهانی: در دسترس نیست"
        }
        val caps = cm.getNetworkCapabilities(network)
        if (caps == null) {
            return "🌐 وضعیت شبکه:\n• اتصال: ⚠️ نامشخص"
        }

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "داده موبایل (Cellular)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "سایر"
        }

        val downKbps = caps.linkDownstreamBandwidthKbps
        val upKbps = caps.linkUpstreamBandwidthKbps

        return buildString {
            append("🌐 وضعیت شبکه سراسری:\n")
            append("• نوع: $type\n")
            append("• اینترنت: ${if (hasInternet) "✅ فعال" else "❌ غیرفعال"}\n")
            append("• اعتبارسنجی جهانی: ${if (validated) "✅ متصل به اینترنت جهانی" else "⚠️ در حال بررسی / محدود"}\n")
            append("• بدون محدودیت حجمی: ${if (unmetered) "بله" else "خیر (ممکن است حجمی باشد)"}\n")
            if (downKbps > 0) append("• پهنای باند تقریبی ↓: ${downKbps} kbps\n")
            if (upKbps > 0) append("• پهنای باند تقریبی ↑: ${upKbps} kbps\n")
            append("• دسترسی APIهای جهانی (Binance / xAI): ${if (validated) "آماده" else "نیاز به اتصال پایدار"}")
        }
    }

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

    fun backtestCrypto(): String = backtestEngine.runCryptoBasket()

    fun backtestCryptoSymbol(ticker: String): String {
        val result = backtestEngine.runCrypto(ticker)
        return result.summary()
    }

    fun backtestIran(): String = backtestEngine.runIranBasket()

    fun getAvailableToolsDescription(): String {
        return """
ابزارهای AURA:
1. زمان / باتری / دستگاه
2. وضعیت شبکه / اینترنت (اتصال جهانی)
3. وضعیت پورتفو
4. اسکن بورس / اسکن کریپتو
5. معامله خودکار روشن/خاموش
6. کیل سوییچ
7. بک‌تست کریپتو — بک‌تست SMA+RSI روی کندل‌های واقعی
8. بک‌تست بورس — بک‌تست روی داده نمونه بورس
9. بک‌تست BTC / ETH / ... — بک‌تست یک نماد
10. شنیدن مداوم / توقف شنیدن — گفتار بدون محدودیت

⚠️ همه معاملات زنده فقط Paper هستند.
گفتار: بدون محدودیت طول پاسخ — شنیدن مداوم پشتیبانی می‌شود.
شبکه: دسترسی به اینترنت جهانی (HTTPS).
        """.trim()
    }

    fun executeTool(toolName: String, arg: String = ""): String {
        return when (toolName.lowercase()) {
            "get_time", "time", "زمان" -> getCurrentTime()
            "get_battery", "battery", "باتری" -> getBatteryInfo()
            "get_device_info", "device", "دستگاه" -> getDeviceInfo()
            "network", "network_status", "وضعیت شبکه", "اینترنت" -> networkStatus()
            "trading_status", "status", "وضعیت", "پورتفو" -> tradingStatus()
            "scan_iran", "اسکن بورس", "بورس" -> scanIranStock()
            "scan_crypto", "اسکن کریپتو", "کریپتو" -> scanCrypto()
            "auto_on" -> enableAutoTrading()
            "auto_off" -> disableAutoTrading()
            "kill_switch", "توقف" -> killSwitch(arg.ifBlank { "دستور کاربر" })
            "release_kill", "ادامه" -> releaseKillSwitch()
            "backtest_crypto", "بک‌تست کریپتو" -> backtestCrypto()
            "backtest_iran", "بک‌تست بورس" -> backtestIran()
            else -> "ابزار «$toolName» شناخته نشد."
        }
    }
}
