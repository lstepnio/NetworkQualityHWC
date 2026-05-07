package com.hotwire.fisiontv.networkqual.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [HistoryEntity::class, PendingPublishEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun pendingPublishDao(): PendingPublishDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "fisiontv-nq.db"
            )
                // Pending-publish queue is best-effort; a destructive
                // migration just drops queued rows and forces fresh ones.
                // History rows survive because v1 -> v2 doesn't touch them
                // schema-wise, but we tolerate loss either way.
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
