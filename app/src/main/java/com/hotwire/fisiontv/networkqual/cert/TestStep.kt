package com.hotwire.fisiontv.networkqual.cert

/**
 * Phases the certification engine drives, in order.
 *
 * The Ookla embedded binary internally runs server selection, ping,
 * download, and upload as a single contiguous test — so we expose a
 * single SPEEDTEST phase rather than four separate ones. Sub-progress
 * within Ookla still drives smooth UI updates.
 *
 * Weights are the fraction of overall progress each phase represents.
 * Must sum to 1.0.
 */
enum class TestStep(val label: String, val weight: Float) {
    DNS("DNS resolution", 0.05f),
    SPEEDTEST("Speedtest (latency, download, upload)", 0.65f),
    PLAYBACK("Real video playback", 0.30f)
}
