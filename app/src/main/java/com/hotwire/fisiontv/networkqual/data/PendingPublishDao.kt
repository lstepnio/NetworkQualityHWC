package com.hotwire.fisiontv.networkqual.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingPublishDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingPublishEntity)

    @Query("SELECT * FROM pending_publish ORDER BY createdAtMs ASC")
    suspend fun all(): List<PendingPublishEntity>

    @Query("SELECT COUNT(*) FROM pending_publish")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM pending_publish WHERE certificationId = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE pending_publish SET attemptCount = attemptCount + 1, lastAttemptAtMs = :now, lastError = :error WHERE certificationId = :id")
    suspend fun recordAttempt(id: String, now: Long, error: String?)

    @Query("DELETE FROM pending_publish WHERE attemptCount >= :maxAttempts")
    suspend fun deleteExhausted(maxAttempts: Int)
}
