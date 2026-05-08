package com.hotwire.fisiontv.networkqual.cert.probes

/**
 * Linearly-interpolated percentile of a sorted long list. Stable for
 * small samples where the rank falls between two integer indices.
 *
 *   percentile([10, 20, 30], 0.50) == 20
 *   percentile([10, 20, 30], 0.95) == 29
 *
 * Returns Long.MAX_VALUE on empty input — callers can treat that as
 * "no data" without a separate null check.
 */
internal fun percentile(sorted: List<Long>, p: Double): Long {
    if (sorted.isEmpty()) return Long.MAX_VALUE
    if (sorted.size == 1) return sorted[0]
    val rank = p * (sorted.size - 1)
    val lo = rank.toInt()
    val hi = (lo + 1).coerceAtMost(sorted.size - 1)
    val frac = rank - lo
    return (sorted[lo] * (1 - frac) + sorted[hi] * frac).toLong()
}
