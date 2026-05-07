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
