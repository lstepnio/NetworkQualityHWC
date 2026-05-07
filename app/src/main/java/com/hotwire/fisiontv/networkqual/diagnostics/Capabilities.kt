package com.hotwire.fisiontv.networkqual.diagnostics

data class Capabilities(
    val drm: DrmCapabilities?,
    val display: DisplayCapabilities,
    val videoCodecs: List<CodecCapability>,
    val audio: AudioCapabilities,
    val thermal: ThermalState,
    val memory: MemoryState,
    val storage: StorageState,
    val locale: LocaleInfo,
    val power: PowerState,
    val system: SystemFlags,
    val wifiSupport: WifiSupport,
    val bootReason: String?,
    val bootTimeEpochMs: Long
)

data class WifiSupport(
    val isWifiEnabled: Boolean,
    val fiveGhzSupported: Boolean,
    val sixGhzSupported: Boolean?,
    val sixtyGhzSupported: Boolean?,
    val wpa3SaeSupported: Boolean?,
    val wpa3SuiteBSupported: Boolean?,
    val easyConnectSupported: Boolean?,
    val enhancedOpenSupported: Boolean?,
    val staApConcurrencySupported: Boolean?,
    val multiStaConcurrencySupported: Boolean?,
    val tdlsSupported: Boolean?
)

data class DrmCapabilities(
    val widevineSecurityLevel: String?,
    val widevineSystemId: String?,
    val widevineHdcpLevel: String?,
    val widevineMaxHdcpLevel: String?,
    val widevineVersion: String?
)

data class DisplayCapabilities(
    val widthPx: Int,
    val heightPx: Int,
    val refreshRateHz: Float,
    val densityDpi: Int,
    val supportedModes: List<DisplayMode>,
    val hdrTypes: List<String>
)

data class DisplayMode(
    val widthPx: Int,
    val heightPx: Int,
    val refreshRateHz: Float
)

data class CodecCapability(
    val name: String,
    val mimeType: String,
    val isHardwareAccelerated: Boolean,
    val maxWidth: Int?,
    val maxHeight: Int?,
    val maxFrameRate: Int?
)

data class AudioCapabilities(
    val supportedEncodings: List<String>,
    val hdmiConnected: Boolean
)

data class ThermalState(
    val status: String
)

data class MemoryState(
    val totalMb: Long,
    val availableMb: Long,
    val thresholdMb: Long,
    val lowMemory: Boolean
)

data class StorageState(
    val dataPartitionFreeMb: Long,
    val dataPartitionTotalMb: Long
)

data class LocaleInfo(
    val locale: String,
    val timezoneId: String,
    val timezoneOffsetMin: Int
)

data class PowerState(
    val acPlugged: Boolean,
    val batteryPresent: Boolean,
    val batteryLevelPct: Int?
)

data class SystemFlags(
    val developerOptionsEnabled: Boolean,
    val adbEnabled: Boolean,
    val isDebuggable: Boolean
)
