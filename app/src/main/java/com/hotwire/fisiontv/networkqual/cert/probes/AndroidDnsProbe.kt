package com.hotwire.fisiontv.networkqual.cert.probes

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import com.hotwire.fisiontv.networkqual.cert.probes.DnsResult
import com.hotwire.fisiontv.networkqual.cert.probes.DnsSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Issues DNS queries that bypass both the JVM and netd resolver caches via
 * [DnsResolver.FLAG_NO_CACHE_LOOKUP] / [DnsResolver.FLAG_NO_CACHE_STORE] on
 * API 29+. Falls back to [InetAddress.getAllByName] (cached) on older
 * Android, which we don't actually ship to but keep for forward-compat.
 *
 * Each host in [hosts] is probed sequentially so the per-host time isn't
 * affected by concurrent socket pressure.
 */
class AndroidDnsProbe(
    private val context: Context,
    private val hosts: List<String>
) : DnsProbe {

    private val executor: Executor = Executors.newSingleThreadExecutor()

    override suspend fun run(onProgress: (Float) -> Unit): DnsResult = withContext(Dispatchers.IO) {
        val samples = mutableListOf<DnsSample>()
        hosts.forEachIndexed { idx, host ->
            samples += resolve(host)
            onProgress((idx + 1).toFloat() / hosts.size)
        }
        val ok = samples.filter { it.success }.map { it.resolveMs }.sorted()
        val median = percentile(ok, 0.50)
        val p95 = percentile(ok, 0.95)
        val max = ok.maxOrNull() ?: Long.MAX_VALUE
        val failed = samples.count { !it.success }
        Log.i(TAG, "samples=$samples median=${median}ms p95=${p95}ms max=${max}ms failed=$failed")
        DnsResult(
            medianMs = median,
            p95Ms = p95,
            maxMs = max,
            failureCount = failed,
            samples = samples
        )
    }

    private suspend fun resolve(host: String): DnsSample =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) resolveUncached(host)
        else resolveLegacy(host)

    private suspend fun resolveUncached(host: String): DnsSample = suspendCancellableCoroutine { cont ->
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val cancel = CancellationSignal()
        cont.invokeOnCancellation { cancel.cancel() }
        val start = System.nanoTime()
        try {
            DnsResolver.getInstance().query(
                network,
                host,
                DnsResolver.FLAG_NO_CACHE_LOOKUP or DnsResolver.FLAG_NO_CACHE_STORE,
                executor,
                cancel,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        val elapsed = (System.nanoTime() - start) / 1_000_000
                        if (rcode != 0) {
                            cont.resume(DnsSample(host, elapsed, false, emptyList(), "rcode=$rcode"))
                        } else {
                            cont.resume(
                                DnsSample(
                                    host = host,
                                    resolveMs = elapsed,
                                    success = true,
                                    resolvedIps = answer.mapNotNull { it.hostAddress }
                                )
                            )
                        }
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        val elapsed = (System.nanoTime() - start) / 1_000_000
                        cont.resume(DnsSample(host, elapsed, false, emptyList(), error.message ?: error::class.simpleName ?: "DnsException"))
                    }
                }
            )
        } catch (t: Throwable) {
            val elapsed = (System.nanoTime() - start) / 1_000_000
            cont.resume(DnsSample(host, elapsed, false, emptyList(), "${t::class.simpleName}: ${t.message}"))
        }
    }

    private fun resolveLegacy(host: String): DnsSample {
        val start = System.nanoTime()
        return try {
            val ips = InetAddress.getAllByName(host)
            DnsSample(host, (System.nanoTime() - start) / 1_000_000, true, ips.mapNotNull { it.hostAddress })
        } catch (t: UnknownHostException) {
            DnsSample(host, (System.nanoTime() - start) / 1_000_000, false, emptyList(), t.message ?: "UnknownHost")
        } catch (t: Throwable) {
            DnsSample(host, (System.nanoTime() - start) / 1_000_000, false, emptyList(), "${t::class.simpleName}: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "DnsProbe"
    }
}
