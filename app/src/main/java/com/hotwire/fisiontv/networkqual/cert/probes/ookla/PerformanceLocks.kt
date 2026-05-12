package com.hotwire.fisiontv.networkqual.cert.probes.ookla

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * Holds the OS-level "I'm doing something throughput-sensitive — don't
 * power-save the radio or the CPU" locks for the duration of an Ookla
 * speedtest. Crucial for stable throughput on Android TV STBs:
 *
 * - **Wi-Fi power save**: Android's default policy puts the 802.11 radio
 *   into PS/PSPoll between TX bursts. On a Wi-Fi 6 STB at idle this is
 *   barely noticeable for normal traffic, but during a speedtest the
 *   doze cycles steal scheduling windows and introduce 5–20% throughput
 *   variance plus retransmits. `WifiManager.WIFI_MODE_FULL_HIGH_PERF`
 *   tells the framework to keep the radio fully on.
 *
 * - **CPU**: a `PARTIAL_WAKE_LOCK` keeps the CPU running even if the
 *   screen turns off mid-test. On a tech-install workflow the screen
 *   might never sleep, but on a customer install (auto-cert in
 *   background) it absolutely will.
 *
 * Locks are reference-counted=false so we own a single acquire/release
 * cycle and can't accidentally pile up on retried tests.
 */
interface PerformanceLocks {

    /** Acquire all locks; return a handle that releases them on close. */
    fun acquire(): Handle

    /** Release-on-close handle for try/finally / use(). */
    fun interface Handle : AutoCloseable {
        override fun close()
    }

    companion object {
        /** No-op impl for tests that don't need real locks. */
        val NOOP: PerformanceLocks = object : PerformanceLocks {
            override fun acquire(): Handle = Handle { /* no-op */ }
        }
    }
}

class AndroidPerformanceLocks(context: Context) : PerformanceLocks {

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(WifiManager::class.java)
    private val powerManager: PowerManager? =
        context.applicationContext.getSystemService(PowerManager::class.java)

    override fun acquire(): PerformanceLocks.Handle {
        val wifiLock: WifiManager.WifiLock? = try {
            wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG)?.also {
                it.setReferenceCounted(false)
                it.acquire()
                Log.i(TAG, "wifi high-perf lock acquired")
            }
        } catch (t: Throwable) {
            // Wi-Fi disabled / no service — proceed without; surface in logs.
            Log.w(TAG, "failed to acquire wifi lock: ${t::class.simpleName}: ${t.message}")
            null
        }
        val wakeLock: PowerManager.WakeLock? = try {
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)?.also {
                it.setReferenceCounted(false)
                // Belt-and-suspenders: time-bound the acquire so a buggy
                // release path can't keep the CPU pinned awake forever.
                // Two minutes is longer than any single cert phase.
                it.acquire(WAKE_TIMEOUT_MS)
                Log.i(TAG, "partial wake lock acquired (${WAKE_TIMEOUT_MS}ms cap)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "failed to acquire wake lock: ${t::class.simpleName}: ${t.message}")
            null
        }
        return PerformanceLocks.Handle {
            try { wifiLock?.release() } catch (t: Throwable) {
                Log.w(TAG, "wifi lock release: ${t::class.simpleName}: ${t.message}")
            }
            try { wakeLock?.release() } catch (t: Throwable) {
                Log.w(TAG, "wake lock release: ${t::class.simpleName}: ${t.message}")
            }
            Log.i(TAG, "perf locks released")
        }
    }

    companion object {
        private const val TAG = "PerfLocks"
        private const val WIFI_TAG = "fision-cert-ookla-wifi"
        private const val WAKE_TAG = "fision-cert-ookla-wake"
        private const val WAKE_TIMEOUT_MS = 2L * 60L * 1000L
    }
}
