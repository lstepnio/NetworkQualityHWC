package com.hotwire.fisiontv.networkqual.cert

import android.util.Log
import com.hotwire.fisiontv.networkqual.diagnostics.NetworkDiagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds a JSON payload matching contract/openapi.yaml for POST to
 * /v1/certifications. Uses org.json so there's no extra dependency on
 * kotlinx.serialization/moshi. Field names and shapes mirror the spec
 * exactly — when the backend exists, this object's output drops in.
 */
object CertificationPayload {

    private const val SCHEMA_VERSION = 1
    private const val TAG = "CertPayload"

    fun toJson(result: CertificationResult): JSONObject {
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("configVersion", result.configVersion)
            put("certificationId", result.certificationId)
            put("deviceId", result.diagnostics.identity.deviceId)
            put("startedAt", iso(result.startedAtMs))
            put("completedAt", iso(result.timestampMs))

            put("device", deviceJson(result.diagnostics))
            put("identity", identityJson(result.diagnostics))
            put("capabilities", capabilitiesJson(result.diagnostics))
            put("network", networkJson(result.diagnostics))
            put("wifi", wifiJson(result.diagnostics))

            put("result", resultJson(result))
            put("metrics", metricsJson(result))
        }
    }

    fun logJson(result: CertificationResult) {
        val json = toJson(result).toString(2)
        // logcat truncates at ~4 KB per line; chunk the JSON.
        json.chunked(3500).forEachIndexed { i, chunk ->
            Log.i(TAG, "payload[$i]:\n$chunk")
        }
    }

    /**
     * Stamps a frozen payload with submission metadata at POST time.
     *
     * The persisted payload (built by [toJson] at enqueue time) carries
     * [CertificationResult.startedAtMs] and [CertificationResult.timestampMs]
     * — the moments the certification began and completed on the STB.
     * When the publish API is down those bytes can sit in the queue for
     * hours or days before a successful POST, so the moment the backend
     * receives the request bears no relation to when the cert actually
     * ran.
     *
     * This function adds two POST-time fields without disturbing the
     * frozen measurement payload:
     *
     *  - `submittedAt`: when this specific POST attempt was made
     *  - `enqueuedAt`:  when the row first entered the queue
     *
     * Together with the existing `startedAt`/`completedAt`, the backend
     * can reconstruct the full timeline (ran → queued → submitted) and
     * key its own storage off the cert's actual completion time rather
     * than the request-received time.
     *
     * Failure mode: if the payload won't parse (corrupted row, partial
     * write), the original string is returned unchanged so the row still
     * has a chance to POST. The backend's own validation will catch the
     * malformed body.
     */
    fun stampSubmission(payloadJson: String, submittedAtMs: Long, enqueuedAtMs: Long): String {
        return try {
            JSONObject(payloadJson).apply {
                put("submittedAt", iso(submittedAtMs))
                put("enqueuedAt", iso(enqueuedAtMs))
            }.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "stampSubmission failed (${t::class.simpleName}: ${t.message}); sending unstamped payload")
            payloadJson
        }
    }

    private fun deviceJson(d: NetworkDiagnostics): JSONObject = JSONObject().apply {
        put("model", d.device.model)
        put("manufacturer", d.device.manufacturer)
        put("androidVersion", d.device.androidVersion)
        put("apiLevel", d.device.apiLevel)
        put("buildFingerprint", d.device.buildFingerprint)
        put("appVersion", d.device.appVersion)
        put("appVersionCode", d.device.appVersionCode)
        put("uptimeMs", d.device.uptimeMs)
    }

    private fun identityJson(d: NetworkDiagnostics): JSONObject = JSONObject().apply {
        put("deviceId", d.identity.deviceId)
        putOrNull("hsn", d.identity.hsn)
        putOrNull("hardwareSerial", d.identity.hardwareSerial)
        putOrNull("ethernetMac", d.identity.ethernetMac)
        putOrNull("wifiMac", d.identity.wifiMac)
    }

    private fun capabilitiesJson(d: NetworkDiagnostics): JSONObject = JSONObject().apply {
        d.capabilities.drm?.let {
            put("drm", JSONObject().apply {
                putOrNull("widevineSecurityLevel", it.widevineSecurityLevel)
                putOrNull("widevineSystemId", it.widevineSystemId)
                putOrNull("widevineHdcpLevel", it.widevineHdcpLevel)
                putOrNull("widevineMaxHdcpLevel", it.widevineMaxHdcpLevel)
                putOrNull("widevineVersion", it.widevineVersion)
            })
        } ?: put("drm", JSONObject.NULL)

        put("display", JSONObject().apply {
            put("widthPx", d.capabilities.display.widthPx)
            put("heightPx", d.capabilities.display.heightPx)
            put("refreshRateHz", d.capabilities.display.refreshRateHz.toDouble())
            put("densityDpi", d.capabilities.display.densityDpi)
            put("supportedModes", JSONArray().apply {
                d.capabilities.display.supportedModes.forEach { m ->
                    put(JSONObject().apply {
                        put("widthPx", m.widthPx)
                        put("heightPx", m.heightPx)
                        put("refreshRateHz", m.refreshRateHz.toDouble())
                    })
                }
            })
            put("hdrTypes", JSONArray(d.capabilities.display.hdrTypes))
        })

        put("videoCodecs", JSONArray().apply {
            d.capabilities.videoCodecs.forEach { c ->
                put(JSONObject().apply {
                    put("name", c.name)
                    put("mimeType", c.mimeType)
                    put("isHardwareAccelerated", c.isHardwareAccelerated)
                    putOrNull("maxWidth", c.maxWidth)
                    putOrNull("maxHeight", c.maxHeight)
                    putOrNull("maxFrameRate", c.maxFrameRate)
                })
            }
        })

        put("audio", JSONObject().apply {
            put("supportedEncodings", JSONArray(d.capabilities.audio.supportedEncodings))
            put("hdmiConnected", d.capabilities.audio.hdmiConnected)
        })

        put("thermal", JSONObject().apply { put("status", d.capabilities.thermal.status) })
        put("memory", JSONObject().apply {
            put("totalMb", d.capabilities.memory.totalMb)
            put("availableMb", d.capabilities.memory.availableMb)
            put("thresholdMb", d.capabilities.memory.thresholdMb)
            put("lowMemory", d.capabilities.memory.lowMemory)
        })
        put("storage", JSONObject().apply {
            put("dataPartitionFreeMb", d.capabilities.storage.dataPartitionFreeMb)
            put("dataPartitionTotalMb", d.capabilities.storage.dataPartitionTotalMb)
        })
        put("locale", JSONObject().apply {
            put("locale", d.capabilities.locale.locale)
            put("timezoneId", d.capabilities.locale.timezoneId)
            put("timezoneOffsetMin", d.capabilities.locale.timezoneOffsetMin)
        })
        put("power", JSONObject().apply {
            put("acPlugged", d.capabilities.power.acPlugged)
            put("batteryPresent", d.capabilities.power.batteryPresent)
            putOrNull("batteryLevelPct", d.capabilities.power.batteryLevelPct)
        })
        put("system", JSONObject().apply {
            put("developerOptionsEnabled", d.capabilities.system.developerOptionsEnabled)
            put("adbEnabled", d.capabilities.system.adbEnabled)
            put("isDebuggable", d.capabilities.system.isDebuggable)
        })
        put("wifiSupport", JSONObject().apply {
            val w = d.capabilities.wifiSupport
            put("isWifiEnabled", w.isWifiEnabled)
            put("fiveGhzSupported", w.fiveGhzSupported)
            putOrNull("sixGhzSupported", w.sixGhzSupported)
            putOrNull("sixtyGhzSupported", w.sixtyGhzSupported)
            putOrNull("wpa3SaeSupported", w.wpa3SaeSupported)
            putOrNull("wpa3SuiteBSupported", w.wpa3SuiteBSupported)
            putOrNull("easyConnectSupported", w.easyConnectSupported)
            putOrNull("enhancedOpenSupported", w.enhancedOpenSupported)
            putOrNull("staApConcurrencySupported", w.staApConcurrencySupported)
            putOrNull("multiStaConcurrencySupported", w.multiStaConcurrencySupported)
            putOrNull("tdlsSupported", w.tdlsSupported)
        })
        putOrNull("bootReason", d.capabilities.bootReason)
        put("bootTimeEpochMs", d.capabilities.bootTimeEpochMs)
    }

    private fun networkJson(d: NetworkDiagnostics): JSONObject = JSONObject().apply {
        put("transport", d.network.transport.name)
        put("vpnActive", d.network.vpnActive)
        put("metered", d.network.metered)
        put("validated", d.network.validated)
        putOrNull("linkDownstreamKbps", d.network.linkDownstreamKbps)
        putOrNull("linkUpstreamKbps", d.network.linkUpstreamKbps)
        putOrNull("privateIp", d.network.privateIp)
        putOrNull("gatewayIp", d.network.gatewayIp)
        put("dnsServers", JSONArray(d.network.dnsServers))
        d.network.dhcp?.let {
            put("dhcp", JSONObject().apply {
                putOrNull("serverAddress", it.serverAddress)
                putOrNull("gateway", it.gateway)
                putOrNull("netmask", it.netmask)
                putOrNull("ipAddress", it.ipAddress)
                putOrNull("leaseSec", it.leaseSec)
                putOrNull("dns1", it.dns1)
                putOrNull("dns2", it.dns2)
            })
        } ?: put("dhcp", JSONObject.NULL)
    }

    private fun wifiJson(d: NetworkDiagnostics): Any {
        val w = d.wifi ?: return JSONObject.NULL
        return JSONObject().apply {
            put("rssiDbm", w.rssiDbm)
            put("signalLevel", w.signalLevel)
            put("linkSpeedMbps", w.linkSpeedMbps)
            putOrNull("txLinkSpeedMbps", w.txLinkSpeedMbps)
            putOrNull("rxLinkSpeedMbps", w.rxLinkSpeedMbps)
            putOrNull("maxSupportedTxLinkSpeedMbps", w.maxSupportedTxLinkSpeedMbps)
            putOrNull("maxSupportedRxLinkSpeedMbps", w.maxSupportedRxLinkSpeedMbps)
            put("frequencyMhz", w.frequencyMhz)
            put("band", w.band.name)
            putOrNull("channelWidthMhz", w.channelWidthMhz)
            put("standard", w.standard.name)
            put("security", w.security.name)
            putOrNull("ssid", w.ssid)
            putOrNull("bssid", w.bssid)
            putOrNull("supplicantState", w.supplicantState)
            putOrNull("hiddenSsid", w.hiddenSsid)
        }
    }

    private fun resultJson(r: CertificationResult): JSONObject = JSONObject().apply {
        put("achievedTier", tierId(r.achievedTier))
        put("playbackAchievedTier", tierId(r.playbackAchievedTier))
        putOrNull("marginalMetric", r.health.limitingMetric)
        put("tierBreakdown", JSONArray().apply {
            r.tierBreakdown.forEach { e ->
                put(JSONObject().apply {
                    put("tierId", tierId(e.tier))
                    put("passed", e.passed)
                    put("failedChecks", JSONArray(e.failingReasons))
                })
            }
        })
        put("health", JSONObject().apply {
            put("headroomPct", r.health.headroomPct)
            put("rating", r.health.rating.name)
            putOrNull("limitingMetric", r.health.limitingMetric)
            putOrNull("nextTier", r.health.nextTier?.let(::tierId))
            put("perMetric", JSONObject(r.health.perMetric.mapValues { it.value as Any }))
        })
        r.wifiLink?.let { link ->
            put("wifiLink", JSONObject().apply {
                put("rating", link.rating.name)
                put("rssiDbm", link.rssiDbm)
                put("band", link.band.name)
                put("linkSpeedMbps", link.linkSpeedMbps)
                putOrNull("maxSupportedMbps", link.maxSupportedMbps)
                put("rateAdaptationDegraded", link.rateAdaptationDegraded)
                put("advice", link.advice)
            })
        } ?: put("wifiLink", JSONObject.NULL)
    }

    private fun metricsJson(r: CertificationResult): JSONObject = JSONObject().apply {
        // Environment snapshots captured immediately before + after the
        // speedtest phase. Used for post-hoc diagnosis of throughput
        // variance — thermal status shifts, CPU frequency drift, Wi-Fi
        // RSSI / link-rate changes during the measurement window.
        // Null when no collector wired (tests).
        r.environmentAtSpeedtestStart?.let { put("environmentAtSpeedtestStart", envJson(it)) }
        r.environmentAtSpeedtestEnd?.let   { put("environmentAtSpeedtestEnd",   envJson(it)) }

        put("selectedServer", JSONObject().apply {
            put("id", r.selectedServer.id)
            put("name", r.selectedServer.name)
            put("host", r.selectedServer.host)
            put("rttMs", r.selectedServerRttMs)
        })
        put("serverProbes", JSONArray().apply {
            r.serverProbes.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("host", p.host)
                    put("rttMs", p.rttMs)
                    put("ok", p.ok)
                    put("selected", p.selected)
                })
            }
        })
        put("download", JSONObject().apply {
            put("steadyMbps", r.download.steadyMbps)
            put("peakMbps", r.download.peakMbps)
            put("durationSec", r.download.durationSec)
        })
        put("upload", JSONObject().apply {
            put("steadyMbps", r.upload.steadyMbps)
            put("peakMbps", r.upload.peakMbps)
            put("durationSec", r.upload.durationSec)
        })
        put("latency", JSONObject().apply {
            put("medianMs", r.latency.medianMs)
            put("p95Ms", r.latency.p95Ms)
            put("lossPct", r.latency.lossPct)
            put("attempted", r.latency.attempted)
            put("jitterMs", r.latency.jitterMs)
            put("samples", JSONArray(r.latency.samples))
        })
        put("dns", JSONObject().apply {
            put("medianMs", r.dns.medianMs)
            put("p95Ms", r.dns.p95Ms)
            put("maxMs", r.dns.maxMs)
            put("failureCount", r.dns.failureCount)
            put("samples", JSONArray().apply {
                r.dns.samples.forEach { s ->
                    put(JSONObject().apply {
                        put("host", s.host)
                        put("resolveMs", s.resolveMs)
                        put("success", s.success)
                        put("resolvedIps", JSONArray(s.resolvedIps))
                        putOrNull("error", s.error)
                    })
                }
            })
        })
        put("playback", JSONObject().apply {
            put("startupMs", r.playback.timeToFirstFrameMs)
            put("rebufferCount", r.playback.rebufferCount)
            put("totalRebufferMs", r.playback.totalRebufferMs)
            put("peakBitrateKbps", r.playback.peakBitrateKbps)
            put("peakHeight", r.playback.peakHeight)
            put("bitrateSwitchCount", r.playback.bitrateSwitchCount)
            put("playedSec", r.playback.playedSec)
        })
    }

    private fun tierId(t: Tier): String = when (t) {
        Tier.SD -> "sd"
        Tier.HD -> "hd"
        Tier.UHD_4K -> "uhd"
        Tier.UHD_4K_HDR -> "uhd_hdr"
        Tier.NONE -> "none"
    }

    private val isoFormat: SimpleDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private fun iso(epochMs: Long): String = isoFormat.format(Date(epochMs))

    private fun JSONObject.putOrNull(key: String, value: Any?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun envJson(s: EnvironmentSnapshot): JSONObject = JSONObject().apply {
        put("takenAt", iso(s.takenAtMs))
        putOrNull("thermalStatus", s.thermalStatus)
        putOrNull("thermalStatusName", s.thermalStatusName)
        putOrNull("cpu0FreqKhz", s.cpu0FreqKhz)
        putOrNull("socTempMilliC", s.socTempMilliC)
        putOrNull("rssiDbm", s.rssiDbm)
        putOrNull("linkSpeedTxMbps", s.linkSpeedTxMbps)
        putOrNull("linkSpeedRxMbps", s.linkSpeedRxMbps)
        putOrNull("wifiStandard", s.wifiStandard)
    }
}
