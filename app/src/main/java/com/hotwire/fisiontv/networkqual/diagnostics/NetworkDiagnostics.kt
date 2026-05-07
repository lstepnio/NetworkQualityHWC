package com.hotwire.fisiontv.networkqual.diagnostics

data class NetworkDiagnostics(
    val device: DeviceInfo,
    val identity: DeviceIdentity,
    val capabilities: Capabilities,
    val network: NetworkInfo,
    val wifi: WifiInfo?
)

data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val apiLevel: Int,
    val buildFingerprint: String,
    val appVersion: String,
    val appVersionCode: Long,
    val uptimeMs: Long
)

data class DeviceIdentity(
    val deviceId: String,
    val hsn: String?,
    val hardwareSerial: String?,
    val ethernetMac: String?,
    val wifiMac: String?
)

enum class Transport { WIFI, ETHERNET, CELLULAR, VPN, OTHER }

data class NetworkInfo(
    val transport: Transport,
    val vpnActive: Boolean,
    val metered: Boolean,
    val validated: Boolean,
    val linkDownstreamKbps: Int?,
    val linkUpstreamKbps: Int?,
    val privateIp: String?,
    val gatewayIp: String?,
    val dnsServers: List<String>,
    val dhcp: DhcpInfo?
)

data class DhcpInfo(
    val serverAddress: String?,
    val gateway: String?,
    val netmask: String?,
    val ipAddress: String?,
    val leaseSec: Int?,
    val dns1: String?,
    val dns2: String?
)

enum class WifiBand { BAND_2_4_GHZ, BAND_5_GHZ, BAND_6_GHZ, UNKNOWN }
enum class WifiStandard { LEGACY_11A, LEGACY_11B, LEGACY_11G, N_11N, AC_11AC, AX_11AX, BE_11BE, UNKNOWN }
enum class WifiSecurity { OPEN, WEP, WPA, WPA2_PSK, WPA2_EAP, WPA3, UNKNOWN }

data class WifiInfo(
    val rssiDbm: Int,
    val signalLevel: Int,
    val linkSpeedMbps: Int,
    val txLinkSpeedMbps: Int?,
    val rxLinkSpeedMbps: Int?,
    val maxSupportedTxLinkSpeedMbps: Int?,
    val maxSupportedRxLinkSpeedMbps: Int?,
    val frequencyMhz: Int,
    val band: WifiBand,
    val channelWidthMhz: Int?,
    val standard: WifiStandard,
    val security: WifiSecurity,
    val ssid: String?,
    val bssid: String?,
    val supplicantState: String?,
    val hiddenSsid: Boolean?
)
