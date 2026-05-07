package com.hotwire.fisiontv.networkqual.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Query("SELECT * FROM certification_history ORDER BY timestampMs DESC LIMIT 50")
    fun observeRecent(): Flow<List<HistoryEntity>>
}
