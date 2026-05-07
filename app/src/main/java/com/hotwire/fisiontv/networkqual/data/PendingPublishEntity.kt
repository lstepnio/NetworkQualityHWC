package com.hotwire.fisiontv.networkqual.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per certification result waiting to be POSTed to the backend.
 *
 * Inserted at the end of every certification run when publishing is
 * enabled; deleted on successful (or permanently-failed) publish. The
 * payload is stored as the full backend-spec JSON so the queue is
 * resilient to schema evolution between when the row is enqueued and
 * when it's drained.
 */
@Entity(tableName = "pending_publish")
data class PendingPublishEntity(
    @PrimaryKey val certificationId: String,
    val payloadJson: String,
    val deviceId: String,
    val appVersion: String,
    val schemaVersion: Int,
    val createdAtMs: Long,
    val attemptCount: Int = 0,
    val lastAttemptAtMs: Long? = null,
    val lastError: String? = null
)
