package com.hotwire.fisiontv.networkqual.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "certification_history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val tier: String,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val latencyMs: Long,
    val jitterMs: Long,
    val rebufferCount: Int,
    val peakHeight: Int
)
