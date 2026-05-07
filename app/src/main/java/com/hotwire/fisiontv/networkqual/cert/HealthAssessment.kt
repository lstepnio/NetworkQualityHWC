package com.hotwire.fisiontv.networkqual.cert

data class HealthAssessment(
    val headroomPct: Int,
    val rating: HealthRating,
    val limitingMetric: String?,
    val nextTier: Tier?,
    val perMetric: Map<String, Int>
)

enum class HealthRating(val displayName: String) {
    EXCELLENT("Excellent"),
    STRONG("Strong"),
    GOOD("Good"),
    MARGINAL("Marginal"),
    FAILED("Did not certify")
}
