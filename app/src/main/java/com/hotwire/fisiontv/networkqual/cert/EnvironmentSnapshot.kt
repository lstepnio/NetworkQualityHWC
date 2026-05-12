package com.hotwire.fisiontv.networkqual.cert

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.File

/**
 * Point-in-time view of the device + radio state right before / after the
 * speedtest phase. Captured into the cert payload so support can correlate
 * throughput variance with thermal throttling, CPU frequency, and Wi-Fi
 * link state instead of guessing.
 *
 * Every field is nullable — collection is best-effort. A device that won't
 * expose its thermal HAL, or a build that doesn't have
 * [PowerManager.getCurrentThermalStatus] (API 29+), simply records null
 * for the missing dimension; the rest still lands.
 *
 * Field meanings:
 *  - [thermalStatus]: 0..6 enum from [PowerManager.getCurrentThermalStatus].
 *    0 NONE / 1 LIGHT / 2 MODERATE / 3 SEVERE / 4 CRITICAL / 5 EMERGENCY /
 *    6 SHUTDOWN. ≥1 means the platform is actively limiting performance.
 *  - [thermalStatusName]: human-readable form for the same.
 *  - [cpu0FreqKhz]: current scaling frequency for cpu0. Compared against
 *    that core's max tells us whether schedutil has clocked us up.
 *  - [socTempMilliC]: SoC temperature in milli-degrees C (e.g. 56300 = 56.3°C),
 *    read from `/sys/class/thermal/thermal_zone0/temp`. Null if not readable.
 *  - [rssiDbm], [linkSpeedTxMbps], [linkSpeedRxMbps]: from the current
 *    Wi-Fi connection. Null on Ethernet / no Wi-Fi service.
 *  - [wifiStandard]: 11n/11ac/11ax/etc. (API 30+).
 */
data class EnvironmentSnapshot(
    val takenAtMs: Long,
    val thermalStatus: Int?,
    val thermalStatusName: String?,
    val cpu0FreqKhz: Long?,
    val socTempMilliC: Long?,
    val rssiDbm: Int?,
    val linkSpeedTxMbps: Int?,
    val linkSpeedRxMbps: Int?,
    val wifiStandard: String?
)

class EnvironmentSnapshotCollector(context: Context) {

    private val appContext = context.applicationContext
    private val powerManager: PowerManager? = appContext.getSystemService(PowerManager::class.java)
    private val wifiManager: WifiManager? = appContext.getSystemService(WifiManager::class.java)

    fun snapshot(): EnvironmentSnapshot {
        val thermal: Int? = readThermalStatus()
        @Suppress("DEPRECATION")
        val wifi: WifiInfo? = try { wifiManager?.connectionInfo } catch (t: Throwable) {
            Log.w(TAG, "wifi info: ${t::class.simpleName}: ${t.message}"); null
        }
        val rssi: Int? = wifi?.rssi?.let { if (it != Int.MIN_VALUE) it else null }
        val tx: Int? = wifi?.txLinkSpeedMbps?.let { if (it >= 0) it else null }
        val rx: Int? = wifi?.rxLinkSpeedMbps?.let { if (it >= 0) it else null }
        return EnvironmentSnapshot(
            takenAtMs = System.currentTimeMillis(),
            thermalStatus = thermal,
            thermalStatusName = thermal?.let { thermalStatusName(it) },
            cpu0FreqKhz = readCpuFreqKhz(0),
            socTempMilliC = readSocTempMilliC(),
            rssiDbm = rssi,
            linkSpeedTxMbps = tx,
            linkSpeedRxMbps = rx,
            wifiStandard = wifiStandardOrNull(wifi)
        )
    }

    private fun readThermalStatus(): Int? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus
        } else null
    } catch (t: Throwable) {
        Log.w(TAG, "thermal status: ${t::class.simpleName}: ${t.message}")
        null
    }

    private fun thermalStatusName(s: Int): String = when (s) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN($s)"
    }

    private fun readCpuFreqKhz(cpu: Int): Long? = try {
        val f = File("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq")
        if (f.exists()) f.readText().trim().toLongOrNull() else null
    } catch (t: Throwable) {
        Log.w(TAG, "cpu$cpu freq: ${t::class.simpleName}: ${t.message}")
        null
    }

    private fun readSocTempMilliC(): Long? = try {
        // thermal_zone0 is conventionally the primary SoC sensor on Amlogic /
        // most ARM SoCs. If the device exposes it as Celsius (some kernels
        // do — value < 1000), we promote to milli-C.
        val f = File("/sys/class/thermal/thermal_zone0/temp")
        val raw = if (f.exists()) f.readText().trim().toLongOrNull() else null
        when {
            raw == null -> null
            raw in 1..200 -> raw * 1000L
            else -> raw
        }
    } catch (t: Throwable) {
        Log.w(TAG, "soc temp: ${t::class.simpleName}: ${t.message}")
        null
    }

    private fun wifiStandardOrNull(wifi: WifiInfo?): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (wifi?.wifiStandard) {
                ScanResult.WIFI_STANDARD_LEGACY -> "legacy"
                ScanResult.WIFI_STANDARD_11N -> "11n"
                ScanResult.WIFI_STANDARD_11AC -> "11ac"
                ScanResult.WIFI_STANDARD_11AX -> "11ax"
                ScanResult.WIFI_STANDARD_11AD -> "11ad"
                ScanResult.WIFI_STANDARD_11BE -> "11be"
                else -> null
            }
        } else null
    } catch (t: Throwable) {
        Log.w(TAG, "wifi standard: ${t::class.simpleName}: ${t.message}")
        null
    }

    companion object {
        private const val TAG = "EnvSnapshot"
    }
}
