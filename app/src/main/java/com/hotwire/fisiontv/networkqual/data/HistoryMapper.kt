package com.hotwire.fisiontv.networkqual.data

import com.hotwire.fisiontv.networkqual.cert.CertificationResult

fun CertificationResult.toEntity(): HistoryEntity = HistoryEntity(
    timestampMs = timestampMs,
    tier = achievedTier.name,
    downloadMbps = download.steadyMbps,
    uploadMbps = upload.steadyMbps,
    latencyMs = latency.medianMs,
    jitterMs = latency.jitterMs,
    rebufferCount = playback.rebufferCount,
    peakHeight = playback.peakHeight
)
