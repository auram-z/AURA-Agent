package com.aura.agent.ai.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * سیستم ابزارهای AURA — قابل توسعه برای MCP و ابزارهای بیشتر
 */
class AuraTools(private val context: Context) {

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
        val batteryPct = if (level != -1 && scale != -1) {
            (level * 100 / scale.toFloat()).toInt()
        } else {
            -1
        }

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return if (batteryPct >= 0) {
            "سطح باتری: $batteryPct% ${if (isCharging) "(در حال شارژ)" else ""}"
        } else {
            "اطلاعات باتری در دسترس نیست"
        }
    }

    fun getDeviceInfo(): String {
        return """
اطلاعات دستگاه:
- مدل: ${Build.MODEL}
- سازنده: ${Build.MANUFACTURER}
- نسخه اندروید: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
        """.trim()
    }

    /**
     * ابزارهای قابل گسترش توسط AI
     * در نسخه‌های بعدی با Function Calling واقعی متصل می‌شود
     */
    fun getAvailableToolsDescription(): String {
        return """
ابزارهای در دسترس AURA:
1. get_time - دریافت زمان و تاریخ فعلی
2. get_battery - وضعیت باتری دستگاه
3. get_device_info - اطلاعات سخت‌افزاری دستگاه
        """.trim()
    }

    fun executeTool(toolName: String): String {
        return when (toolName.lowercase()) {
            "get_time", "time", "زمان" -> getCurrentTime()
            "get_battery", "battery", "باتری" -> getBatteryInfo()
            "get_device_info", "device", "دستگاه" -> getDeviceInfo()
            else -> "ابزار «$toolName» شناخته است."
        }
    }
}
