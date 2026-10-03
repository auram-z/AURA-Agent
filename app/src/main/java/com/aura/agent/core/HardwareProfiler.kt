package com.aura.agent.core

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * تشخیص سخت‌افزار و شبکه برای انتخاب روش اجرای مناسب.
 */
class HardwareProfiler(private val context: Context) {

    data class Profile(
        val totalRamMb: Long,
        val availRamMb: Long,
        val cpuCores: Int,
        val online: Boolean,
        val unmetered: Boolean,
        val androidApi: Int,
        val deviceModel: String
    ) {
        val canRunLightOnDevice: Boolean get() = totalRamMb >= 3072
        val canRunHeavyOnDevice: Boolean get() = totalRamMb >= 6144 && cpuCores >= 6
    }

    fun profile(): Profile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val totalMb = mem.totalMem / (1024 * 1024)
        val availMb = mem.availMem / (1024 * 1024)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

        return Profile(
            totalRamMb = totalMb,
            availRamMb = availMb,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            online = online,
            unmetered = unmetered,
            androidApi = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    fun summary(): String {
        val p = profile()
        return buildString {
            append("⚙️ سخت‌افزار:\n")
            append("• دستگاه: ${p.deviceModel}\n")
            append("• RAM: ${p.totalRamMb} MB (آزاد ≈ ${p.availRamMb})\n")
            append("• هسته CPU: ${p.cpuCores}\n")
            append("• شبکه: ${if (p.online) "آنلاین" else "آفلاین"}")
            if (p.online) append(if (p.unmetered) " · بدون محدودیت حجمی" else " · ممکن است حجمی باشد")
            append("\n• OnDevice سبک: ${if (p.canRunLightOnDevice) "بله" else "خیر"}\n")
            append("• OnDevice سنگین: ${if (p.canRunHeavyOnDevice) "بله" else "خیر"}")
        }
    }
}
