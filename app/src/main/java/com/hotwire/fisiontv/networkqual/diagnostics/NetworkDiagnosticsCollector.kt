package com.hotwire.fisiontv.networkqual.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.net.wifi.WifiInfo as PlatformWifiInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.net.Inet4Address

private const val TAG = "NetDiagnostics"

object NetworkDiagnosticsCollector {

    fun collect(context: Context): NetworkDiagnostics {
        val device = collectDevice(context)
        val identity = DeviceIdentityCollector.collect(context)
        val capabilities = CapabilitiesCollector.collect(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val link = network?.let { cm.getLinkProperties(it) }

        val networkInfo = collectNetwork(context, caps, link)
        val wifiInfo = if (networkInfo.transport == Transport.WIFI) collectWifi(context, caps) else null

        val diagnostics = NetworkDiagnostics(device, identity, capabilities, networkInfo, wifiInfo)
        Log.i(TAG, "device=$device")
        Log.i(TAG, "identity=$identity")
        Log.i(TAG, "network=$networkInfo")
        Log.i(TAG, "wifi=$wifiInfo")
        Log.i(TAG, "drm=${capabilities.drm}")
        Log.i(TAG, "display=${capabilities.display}")
        Log.i(TAG, "audio=${capabilities.audio}")
        Log.i(TAG, "thermal=${capabilities.thermal} memory=${capabilities.memory} storage=${capabilities.storage}")
        Log.i(TAG, "locale=${capabilities.locale} power=${capabilities.power} system=${capabilities.system}")
        Log.i(TAG, "bootReason=${capabilities.bootReason} bootTimeEpochMs=${capabilities.bootTimeEpochMs}")
        capabilities.videoCodecs.forEach { Log.i(TAG, "codec=$it") }
        return diagnostics
    }

    private fun collectDevice(context: Context): DeviceInfo {
        val pkg = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val versionCode: Long = pkg?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        } ?: 0L
        return DeviceInfo(
            model = Build.MODEL ?: "unknown",
            manufacturer = Build.MANUFACTURER ?: "unknown",
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            apiLevel = Build.VERSION.SDK_INT,
            buildFingerprint = Build.FINGERPRINT ?: "unknown",
            appVersion = pkg?.versionName ?: "unknown",
            appVersionCode = versionCode,
            uptimeMs = SystemClock.elapsedRealtime()
        )
    }

    private fun collectNetwork(context: Context, caps: NetworkCapabilities?, link: LinkProperties?): NetworkInfo {
        val transport = when {
            caps == null -> Transport.OTHER
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            else -> Transport.OTHER
        }
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val metered = caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } ?: false
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        val privateIp = link?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.address?.hostAddress

        val gatewayIp = link?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress

        val dnsServers = link?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()

        return NetworkInfo(
            transport = transport,
            vpnActive = vpn,
            metered = metered,
            validated = validated,
            linkDownstreamKbps = caps?.linkDownstreamBandwidthKbps,
            linkUpstreamKbps = caps?.linkUpstreamBandwidthKbps,
            privateIp = privateIp,
            gatewayIp = gatewayIp,
            dnsServers = dnsServers,
            dhcp = collectDhcp(context, transport)
        )
    }

    private fun collectDhcp(context: Context, transport: Transport): DhcpInfo? {
        if (transport != Transport.WIFI) return null
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        val d = wm.dhcpInfo ?: return null
        return DhcpInfo(
            serverAddress = intToIp(d.serverAddress),
            gateway = intToIp(d.gateway),
            netmask = intToIp(d.netmask),
            ipAddress = intToIp(d.ipAddress),
            leaseSec = d.leaseDuration.takeIf { it > 0 },
            dns1 = intToIp(d.dns1),
            dns2 = intToIp(d.dns2)
        )
    }

    private fun intToIp(addr: Int): String? {
        if (addr == 0) return null
        return "${addr and 0xff}.${(addr shr 8) and 0xff}.${(addr shr 16) and 0xff}.${(addr shr 24) and 0xff}"
    }

    private fun collectWifi(context: Context, caps: NetworkCapabilities?): WifiInfo? {
        val info: PlatformWifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            caps?.transportInfo as? PlatformWifiInfo
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
        }
        info ?: return null

        val rssi = info.rssi
        val freq = info.frequency
        val band = bandFromFrequency(freq)
        val standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mapStandard(info.wifiStandard)
        } else {
            WifiStandard.UNKNOWN
        }
        val txMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.txLinkSpeedMbps else null
        val rxMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.rxLinkSpeedMbps else null
        val maxTx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) info.maxSupportedTxLinkSpeedMbps else null
        val maxRx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) info.maxSupportedRxLinkSpeedMbps else null

        val supplicant = try { info.supplicantState?.let(::supplicantName) } catch (_: Throwable) { null }
        val hidden = try { info.hiddenSSID } catch (_: Throwable) { null }

        return WifiInfo(
            rssiDbm = rssi,
            signalLevel = WifiManager.calculateSignalLevel(rssi, 5),
            linkSpeedMbps = info.linkSpeed,
            txLinkSpeedMbps = txMbps?.takeIf { it >= 0 },
            rxLinkSpeedMbps = rxMbps?.takeIf { it >= 0 },
            maxSupportedTxLinkSpeedMbps = maxTx?.takeIf { it >= 0 },
            maxSupportedRxLinkSpeedMbps = maxRx?.takeIf { it >= 0 },
            frequencyMhz = freq,
            band = band,
            channelWidthMhz = null,
            standard = standard,
            security = WifiSecurity.UNKNOWN,
            ssid = null,
            bssid = null,
            supplicantState = supplicant,
            hiddenSsid = hidden
        )
    }

    private fun bandFromFrequency(freq: Int): WifiBand = when (freq) {
        in 2400..2500 -> WifiBand.BAND_2_4_GHZ
        in 5150..5895 -> WifiBand.BAND_5_GHZ
        in 5925..7125 -> WifiBand.BAND_6_GHZ
        else -> WifiBand.UNKNOWN
    }

    private fun mapStandard(value: Int): WifiStandard = when (value) {
        ScanResult.WIFI_STANDARD_LEGACY -> WifiStandard.UNKNOWN
        ScanResult.WIFI_STANDARD_11N -> WifiStandard.N_11N
        ScanResult.WIFI_STANDARD_11AC -> WifiStandard.AC_11AC
        ScanResult.WIFI_STANDARD_11AX -> WifiStandard.AX_11AX
        else -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                value == ScanResult.WIFI_STANDARD_11BE) WifiStandard.BE_11BE
            else WifiStandard.UNKNOWN
        }
    }

    private fun supplicantName(state: SupplicantState): String = state.name
}
