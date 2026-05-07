package com.hotwire.fisiontv.networkqual.cert

import com.hotwire.fisiontv.networkqual.config.HealthAssessmentConfig
import com.hotwire.fisiontv.networkqual.test.LatencyResult
import com.hotwire.fisiontv.networkqual.test.ThroughputResult

/**
 * Computes the on-screen "Headroom" indicator. Score is the per-metric
 * minimum of the progress fraction between the achieved tier's threshold
 * and the next tier's threshold; the limiting metric drives the rating.
 *
 * At the top tier (no next), each metric is scored against a stretch
 * target driven by [HealthAssessmentConfig.topTierStretchUpFactor] /
 * [HealthAssessmentConfig.topTierStretchDownFactor].
 */
class HealthAssessor(
    private val tiers: List<TierThreshold>,
    private val cfg: HealthAssessmentConfig
) {

    fun assess(
        achieved: Tier,
        download: ThroughputResult,
        latency: LatencyResult
    ): HealthAssessment {
        if (achieved == Tier.NONE) {
            return HealthAssessment(
                headroomPct = 0,
                rating = HealthRating.FAILED,
                limitingMetric = null,
                nextTier = tiers.firstOrNull()?.tier,
                perMetric = emptyMap()
            )
        }

        val achievedThr = tiers.firstOrNull { it.tier == achieved }
            ?: return failedAssessment()
        val next = nextTier(achieved)
        val nextThr = next?.let { t -> tiers.firstOrNull { it.tier == t } }

        val downloadFrac = headroomFracMoreIsBetter(
            measured = download.steadyMbps,
            achievedFloor = achievedThr.minDownloadMbps,
            nextFloor = nextThr?.minDownloadMbps ?: (achievedThr.minDownloadMbps * cfg.topTierStretchUpFactor)
        )
        val latencyFrac = headroomFracLessIsBetter(
            measured = latency.medianMs.toDouble(),
            achievedCeiling = achievedThr.maxLatencyMs.toDouble(),
            nextCeiling = nextThr?.maxLatencyMs?.toDouble() ?: (achievedThr.maxLatencyMs * cfg.topTierStretchDownFactor)
        )
        val jitterFrac = headroomFracLessIsBetter(
            measured = latency.jitterMs.toDouble(),
            achievedCeiling = achievedThr.maxJitterMs.toDouble(),
            nextCeiling = nextThr?.maxJitterMs?.toDouble() ?: (achievedThr.maxJitterMs * cfg.topTierStretchDownFactor)
        )

        val per = linkedMapOf(
            "Download" to (downloadFrac * 100).toInt(),
            "Latency" to (latencyFrac * 100).toInt(),
            "Jitter" to (jitterFrac * 100).toInt()
        )
        val limiting = per.minByOrNull { it.value }
        val score = limiting?.value ?: 0

        val rating = when {
            score >= cfg.excellentMin -> HealthRating.EXCELLENT
            score >= cfg.strongMin -> HealthRating.STRONG
            score >= cfg.goodMin -> HealthRating.GOOD
            else -> HealthRating.MARGINAL
        }

        return HealthAssessment(
            headroomPct = score,
            rating = rating,
            limitingMetric = limiting?.key,
            nextTier = next,
            perMetric = per
        )
    }

    private fun failedAssessment() = HealthAssessment(0, HealthRating.FAILED, null, null, emptyMap())

    private fun headroomFracMoreIsBetter(measured: Double, achievedFloor: Double, nextFloor: Double): Double {
        if (nextFloor <= achievedFloor) return 1.0
        return ((measured - achievedFloor) / (nextFloor - achievedFloor)).coerceIn(0.0, 1.0)
    }

    private fun headroomFracLessIsBetter(measured: Double, achievedCeiling: Double, nextCeiling: Double): Double {
        if (achievedCeiling <= nextCeiling) return 1.0
        return ((achievedCeiling - measured) / (achievedCeiling - nextCeiling)).coerceIn(0.0, 1.0)
    }

    private fun nextTier(achieved: Tier): Tier? {
        val ordered = tiers.map { it.tier }
        val idx = ordered.indexOf(achieved)
        return if (idx == -1 || idx == ordered.lastIndex) null else ordered[idx + 1]
    }
}
