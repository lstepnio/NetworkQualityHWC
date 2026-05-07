package com.hotwire.fisiontv.networkqual.cert

enum class TestStep(val label: String, val weight: Float) {
    DNS("DNS resolution", 0.05f),
    SERVER_SELECT("Selecting nearest server", 0.05f),
    LATENCY("Latency & jitter", 0.10f),
    DOWNLOAD("Sustained download", 0.30f),
    UPLOAD("Sustained upload", 0.20f),
    PLAYBACK("Real video playback", 0.30f)
}
