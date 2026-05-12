package com.hotwire.fisiontv.networkqual.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.UUID

private const val TAG = "DeviceIdentity"
private const val PREFS = "fisiontv_identity"
private const val KEY_DEVICE_ID = "device_id"

object DeviceIdentityCollector {

    /**
     * Stable **per-install** UUID, persisted in app private storage.
     * Exposed so pre-cert-run callers (cert-config fetch on launch) can
     * include `X-Device-Id` without first running [collect].
     *
     * NOTE: this is NOT a per-device identifier. It identifies one
     * **app install** — a fresh UUID is minted any time the app's data
     * dir is missing this key, which happens on:
     *   - fresh install (uninstall → install),
     *   - `pm clear` / "Clear data" from Settings,
     *   - factory reset,
     *   - `adb install` without `-r` on a build whose signing cert
     *     doesn't match the previously-installed one (Android forces
     *     uninstall first).
     *
     * `adb install -r` and OTA updates preserve the data dir, so the
     * deviceId survives those. Within one install, the value is stable
     * across reboots, process restarts, and screen-off cycles.
     *
     * For "this physical STB across its lifetime", join on HSN
     * ([DeviceIdentity.hsn]) — that's the stable hardware identity and
     * what the dashboard groups on.
     */
    fun deviceId(context: Context): String = stableDeviceId(context)

    fun collect(context: Context): DeviceIdentity {
        val deviceId = stableDeviceId(context)
        val hsn = readSystemProperty("ro.product.hsnt")?.takeIf { it.isNotBlank() }
        val serial = readSystemProperty("ro.serialno")?.takeIf { it.isNotBlank() } ?: readBuildSerial()
        val ethMac = readMacFromSysfs("eth0")
        val wifiMac = readMacFromSysfs("wlan0")
        return DeviceIdentity(
            deviceId = deviceId,
            hsn = hsn,
            hardwareSerial = serial,
            ethernetMac = ethMac,
            wifiMac = wifiMac
        )
    }

    private fun stableDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
        return fresh
    }

    private fun readSystemProperty(key: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("get", String::class.java)
        (m.invoke(null, key) as? String)?.takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        Log.d(TAG, "SystemProperties.get($key) failed: ${t.message}")
        null
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun readBuildSerial(): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Build.getSerial().takeIf { it != Build.UNKNOWN }
        } else {
            @Suppress("DEPRECATION")
            Build.SERIAL.takeIf { it != Build.UNKNOWN }
        }
    } catch (t: SecurityException) {
        Log.d(TAG, "Build.getSerial() denied: ${t.message}")
        null
    } catch (t: Throwable) {
        null
    }

    private fun readMacFromSysfs(iface: String): String? = try {
        val f = File("/sys/class/net/$iface/address")
        if (f.canRead()) f.readText().trim().lowercase().takeIf { it.isNotEmpty() && it != "00:00:00:00:00:00" }
        else null
    } catch (t: Throwable) {
        Log.d(TAG, "MAC read for $iface failed: ${t.message}")
        null
    }
}
