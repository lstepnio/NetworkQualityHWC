package com.hotwire.fisiontv.networkqual.fixtures

import com.hotwire.fisiontv.networkqual.diagnostics.AudioCapabilities
import com.hotwire.fisiontv.networkqual.diagnostics.Capabilities
import com.hotwire.fisiontv.networkqual.diagnostics.CodecCapability
import com.hotwire.fisiontv.networkqual.diagnostics.DeviceIdentity
import com.hotwire.fisiontv.networkqual.diagnostics.DeviceInfo
import com.hotwire.fisiontv.networkqual.diagnostics.DhcpInfo
import com.hotwire.fisiontv.networkqual.diagnostics.DisplayCapabilities
import com.hotwire.fisiontv.networkqual.diagnostics.DisplayMode
import com.hotwire.fisiontv.networkqual.diagnostics.DrmCapabilities
import com.hotwire.fisiontv.networkqual.diagnostics.LocaleInfo
import com.hotwire.fisiontv.networkqual.diagnostics.MemoryState
import com.hotwire.fisiontv.networkqual.diagnostics.NetworkDiagnostics
import com.hotwire.fisiontv.networkqual.diagnostics.NetworkInfo
import com.hotwire.fisiontv.networkqual.diagnostics.PowerState
import com.hotwire.fisiontv.networkqual.diagnostics.StorageState
import com.hotwire.fisiontv.networkqual.diagnostics.SystemFlags
import com.hotwire.fisiontv.networkqual.diagnostics.ThermalState
import com.hotwire.fisiontv.networkqual.diagnostics.Transport
import com.hotwire.fisiontv.networkqual.diagnostics.WifiBand
import com.hotwire.fisiontv.networkqual.diagnostics.WifiInfo
import com.hotwire.fisiontv.networkqual.diagnostics.WifiSecurity
import com.hotwire.fisiontv.networkqual.diagnostics.WifiStandard
import com.hotwire.fisiontv.networkqual.diagnostics.WifiSupport

object FakeDiagnostics {
    fun build(transport: Transport = Transport.WIFI): NetworkDiagnostics = NetworkDiagnostics(
        device = DeviceInfo(
            model = "FakeBox",
            manufacturer = "FakeCorp",
            androidVersion = "12",
            apiLevel = 31,
            buildFingerprint = "fake/build/1",
            appVersion = "0.0.0-test",
            appVersionCode = 1L,
            uptimeMs = 60_000L
        ),
        identity = DeviceIdentity(
            deviceId = "00000000-0000-0000-0000-000000000001",
            hsn = "TEST-HSN-1",
            hardwareSerial = "TEST-SERIAL-1",
            ethernetMac = null,
            wifiMac = null
        ),
        capabilities = Capabilities(
            drm = DrmCapabilities("L1", "1234", "HDCP-2.2", "HDCP-2.2", "1.0.0"),
            display = DisplayCapabilities(
                widthPx = 3840, heightPx = 2160, refreshRateHz = 60f, densityDpi = 320,
                supportedModes = listOf(DisplayMode(3840, 2160, 60f)),
                hdrTypes = listOf("HDR10", "DOLBY_VISION")
            ),
            videoCodecs = listOf(
                CodecCapability("c2.android.hevc.decoder", "video/hevc", true, 4096, 4096, 60)
            ),
            audio = AudioCapabilities(listOf("PCM_16BIT", "EAC3"), hdmiConnected = true),
            thermal = ThermalState("NONE"),
            memory = MemoryState(2048, 1024, 256, lowMemory = false),
            storage = StorageState(8000, 16000),
            locale = LocaleInfo("en-US", "America/New_York", -300),
            power = PowerState(acPlugged = true, batteryPresent = false, batteryLevelPct = null),
            system = SystemFlags(developerOptionsEnabled = false, adbEnabled = false, isDebuggable = false),
            wifiSupport = WifiSupport(
                isWifiEnabled = true,
                fiveGhzSupported = true,
                sixGhzSupported = false, sixtyGhzSupported = false,
                wpa3SaeSupported = true, wpa3SuiteBSupported = false,
                easyConnectSupported = true, enhancedOpenSupported = true,
                staApConcurrencySupported = false, multiStaConcurrencySupported = false,
                tdlsSupported = true
            ),
            bootReason = "reboot",
            bootTimeEpochMs = 1_700_000_000_000L
        ),
        network = NetworkInfo(
            transport = transport,
            vpnActive = false, metered = false, validated = true,
            linkDownstreamKbps = 100_000, linkUpstreamKbps = 50_000,
            privateIp = "192.168.1.10", gatewayIp = "192.168.1.1",
            dnsServers = listOf("1.1.1.1"),
            dhcp = if (transport == Transport.WIFI) DhcpInfo(
                serverAddress = "192.168.1.1", gateway = "192.168.1.1",
                netmask = null, ipAddress = "192.168.1.10",
                leaseSec = 86400, dns1 = "1.1.1.1", dns2 = null
            ) else null
        ),
        wifi = if (transport == Transport.WIFI) WifiInfo(
            rssiDbm = -55, signalLevel = 4,
            linkSpeedMbps = 433, txLinkSpeedMbps = 433, rxLinkSpeedMbps = null,
            maxSupportedTxLinkSpeedMbps = 866, maxSupportedRxLinkSpeedMbps = 866,
            frequencyMhz = 5180, band = WifiBand.BAND_5_GHZ,
            channelWidthMhz = null, standard = WifiStandard.AC_11AC, security = WifiSecurity.UNKNOWN,
            ssid = null, bssid = null,
            supplicantState = "COMPLETED", hiddenSsid = false
        ) else null
    )
}
