package com.hotwire.fisiontv.networkqual.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecList
import android.media.MediaDrm
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private const val TAG = "Capabilities"
private val WIDEVINE_UUID = UUID.fromString("EDEF8BA9-79D6-4ACE-A3C8-27DCD51D21ED")

object CapabilitiesCollector {

    fun collect(context: Context): Capabilities {
        return Capabilities(
            drm = collectDrm(),
            display = collectDisplay(context),
            videoCodecs = collectVideoCodecs(),
            audio = collectAudio(context),
            thermal = collectThermal(context),
            memory = collectMemory(context),
            storage = collectStorage(context),
            locale = collectLocale(),
            power = collectPower(context),
            system = collectSystemFlags(context),
            wifiSupport = collectWifiSupport(context),
            bootReason = collectBootReason(),
            bootTimeEpochMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        )
    }

    private fun collectDrm(): DrmCapabilities? {
        return try {
            val drm = MediaDrm(WIDEVINE_UUID)
            try {
                DrmCapabilities(
                    widevineSecurityLevel = safeProp(drm, "securityLevel"),
                    widevineSystemId = safeProp(drm, "systemId"),
                    widevineHdcpLevel = safeProp(drm, "hdcpLevel"),
                    widevineMaxHdcpLevel = safeProp(drm, "maxHdcpLevel"),
                    widevineVersion = safeProp(drm, "version")
                )
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) drm.close() else @Suppress("DEPRECATION") drm.release()
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Widevine MediaDrm not available: ${t.message}")
            null
        }
    }

    private fun safeProp(drm: MediaDrm, key: String): String? = try {
        drm.getPropertyString(key).takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        null
    }

    private fun collectDisplay(context: Context): DisplayCapabilities {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display.mode
        val modes = display.supportedModes.map {
            DisplayMode(it.physicalWidth, it.physicalHeight, it.refreshRate)
        }
        val hdrTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            display.hdrCapabilities?.supportedHdrTypes?.map { hdrTypeName(it) } ?: emptyList()
        } else emptyList()
        val metrics = context.resources.displayMetrics
        return DisplayCapabilities(
            widthPx = mode.physicalWidth,
            heightPx = mode.physicalHeight,
            refreshRateHz = mode.refreshRate,
            densityDpi = metrics.densityDpi,
            supportedModes = modes,
            hdrTypes = hdrTypes
        )
    }

    private fun hdrTypeName(value: Int): String = when (value) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DOLBY_VISION"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10_PLUS"
        else -> "UNKNOWN_$value"
    }

    private fun collectVideoCodecs(): List<CodecCapability> {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            list.codecInfos.asSequence()
                .filter { !it.isEncoder }
                .flatMap { codec ->
                    codec.supportedTypes.asSequence()
                        .filter { it.startsWith("video/") }
                        .mapNotNull { mime ->
                            val caps = try { codec.getCapabilitiesForType(mime) } catch (_: Throwable) { null }
                            val video = caps?.videoCapabilities
                            CodecCapability(
                                name = codec.name,
                                mimeType = mime,
                                isHardwareAccelerated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) codec.isHardwareAccelerated else !codec.name.startsWith("OMX.google", ignoreCase = true) && !codec.name.startsWith("c2.android", ignoreCase = true),
                                maxWidth = video?.supportedWidths?.upper,
                                maxHeight = video?.supportedHeights?.upper,
                                maxFrameRate = video?.supportedFrameRates?.upper
                            )
                        }
                }
                .toList()
        } catch (t: Throwable) {
            Log.d(TAG, "codec enumeration failed: ${t.message}")
            emptyList()
        }
    }

    private fun collectAudio(context: Context): AudioCapabilities {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hdmi = devices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_HDMI ||
            it.type == android.media.AudioDeviceInfo.TYPE_HDMI_ARC ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == android.media.AudioDeviceInfo.TYPE_HDMI_EARC)
        }
        val encodings = (hdmi?.encodings ?: intArrayOf()).map(::audioEncodingName).toSet().toList()
        return AudioCapabilities(
            supportedEncodings = encodings,
            hdmiConnected = hdmi != null
        )
    }

    private fun audioEncodingName(value: Int): String = when (value) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM_16BIT"
        AudioFormat.ENCODING_PCM_8BIT -> "PCM_8BIT"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
        AudioFormat.ENCODING_AC3 -> "AC3"
        AudioFormat.ENCODING_E_AC3 -> "EAC3"
        AudioFormat.ENCODING_E_AC3_JOC -> "EAC3_JOC"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS_HD"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "DOLBY_TRUEHD"
        AudioFormat.ENCODING_AC4 -> "AC4"
        AudioFormat.ENCODING_DOLBY_MAT -> "DOLBY_MAT"
        else -> "ENCODING_$value"
    }

    private fun collectThermal(context: Context): ThermalState {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { thermalStatusName(pm.currentThermalStatus) } catch (_: Throwable) { "UNAVAILABLE" }
        } else "UNAVAILABLE"
        return ThermalState(status)
    }

    private fun thermalStatusName(value: Int): String = when (value) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN_$value"
    }

    private fun collectMemory(context: Context): MemoryState {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return MemoryState(
            totalMb = mi.totalMem / 1_048_576,
            availableMb = mi.availMem / 1_048_576,
            thresholdMb = mi.threshold / 1_048_576,
            lowMemory = mi.lowMemory
        )
    }

    private fun collectStorage(context: Context): StorageState = try {
        val stat = StatFs(context.filesDir.absolutePath)
        StorageState(
            dataPartitionFreeMb = stat.availableBytes / 1_048_576,
            dataPartitionTotalMb = stat.totalBytes / 1_048_576
        )
    } catch (t: Throwable) {
        StorageState(0, 0)
    }

    private fun collectLocale(): LocaleInfo {
        val locale = Locale.getDefault()
        val tz = TimeZone.getDefault()
        return LocaleInfo(
            locale = locale.toLanguageTag(),
            timezoneId = tz.id,
            timezoneOffsetMin = tz.getOffset(System.currentTimeMillis()) / 60_000
        )
    }

    private fun collectPower(context: Context): PowerState {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val present = intent?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) ?: false
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else null
        return PowerState(
            acPlugged = plugged != 0,
            batteryPresent = present,
            batteryLevelPct = pct
        )
    }

    private fun collectSystemFlags(context: Context): SystemFlags {
        val devEnabled = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (_: Throwable) { false }
        val adb = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (_: Throwable) { false }
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return SystemFlags(devEnabled, adb, debuggable)
    }

    private fun collectWifiSupport(context: Context): WifiSupport {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) {
            return WifiSupport(
                isWifiEnabled = false,
                fiveGhzSupported = false,
                sixGhzSupported = null, sixtyGhzSupported = null,
                wpa3SaeSupported = null, wpa3SuiteBSupported = null,
                easyConnectSupported = null, enhancedOpenSupported = null,
                staApConcurrencySupported = null, multiStaConcurrencySupported = null,
                tdlsSupported = null
            )
        }
        val sdk = Build.VERSION.SDK_INT
        return WifiSupport(
            isWifiEnabled = safeBool { wm.isWifiEnabled } ?: false,
            fiveGhzSupported = safeBool { wm.is5GHzBandSupported } ?: false,
            sixGhzSupported = if (sdk >= Build.VERSION_CODES.R) safeBool { wm.is6GHzBandSupported } else null,
            sixtyGhzSupported = if (sdk >= Build.VERSION_CODES.S) safeBool { wm.is60GHzBandSupported } else null,
            wpa3SaeSupported = if (sdk >= Build.VERSION_CODES.R) safeBool { wm.isWpa3SaeSupported } else null,
            wpa3SuiteBSupported = if (sdk >= Build.VERSION_CODES.R) safeBool { wm.isWpa3SuiteBSupported } else null,
            easyConnectSupported = if (sdk >= Build.VERSION_CODES.Q) safeBool { wm.isEasyConnectSupported } else null,
            enhancedOpenSupported = if (sdk >= Build.VERSION_CODES.Q) safeBool { wm.isEnhancedOpenSupported } else null,
            staApConcurrencySupported = if (sdk >= Build.VERSION_CODES.R) safeBool { wm.isStaApConcurrencySupported } else null,
            multiStaConcurrencySupported = if (sdk >= Build.VERSION_CODES.S) safeBool { wm.isStaConcurrencyForLocalOnlyConnectionsSupported } else null,
            tdlsSupported = if (sdk >= Build.VERSION_CODES.R) safeBool { wm.isTdlsSupported } else null
        )
    }

    private inline fun safeBool(block: () -> Boolean): Boolean? = try { block() } catch (_: Throwable) { null }

    private fun collectBootReason(): String? {
        return readSystemProperty("sys.boot.reason")
            ?: readSystemProperty("ro.boot.bootreason")
    }

    private fun readSystemProperty(key: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("get", String::class.java)
        (m.invoke(null, key) as? String)?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }
}
