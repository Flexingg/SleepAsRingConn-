package com.randallengineering.sleepasringconn.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface EpochDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(epochs: List<EpochEntity>): List<Long>

    @Query("SELECT * FROM epoch_records ORDER BY timestampMillis DESC LIMIT :limit")
    fun getRecentEpochs(limit: Int = 100): Flow<List<EpochEntity>>

    @Query("SELECT * FROM epoch_records WHERE timestampMillis BETWEEN :start AND :end ORDER BY timestampMillis ASC")
    suspend fun getEpochsBetween(start: Long, end: Long): List<EpochEntity>

    @Query("SELECT * FROM epoch_records WHERE timestampMillis >= :since ORDER BY timestampMillis ASC")
    suspend fun getEpochsSince(since: Long): List<EpochEntity>

    @Query("SELECT * FROM epoch_records WHERE isSyncedToHealthConnect = 0 ORDER BY timestampMillis ASC LIMIT 500")
    suspend fun getUnsyncedEpochs(): List<EpochEntity>

    @Query("UPDATE epoch_records SET isSyncedToHealthConnect = 1 WHERE counter IN (:counters)")
    suspend fun markSynced(counters: List<Long>)

    @Query("SELECT COUNT(*) FROM epoch_records")
    fun getCount(): Flow<Int>
}

@Dao
interface DeviceStatusDao {
    @Insert
    suspend fun insert(status: DeviceStatusEntity): Long

    @Query("SELECT * FROM device_status_logs ORDER BY timestampMillis DESC LIMIT 1")
    fun getLatestStatus(): Flow<DeviceStatusEntity?>

    @Query("SELECT * FROM device_status_logs ORDER BY timestampMillis DESC LIMIT :limit")
    fun getRecentStatusLogs(limit: Int = 50): Flow<List<DeviceStatusEntity>>

    @Query("SELECT * FROM device_status_logs WHERE timestampMillis >= :since ORDER BY timestampMillis ASC")
    suspend fun getStatusLogsSince(since: Long): List<DeviceStatusEntity>

    @Query("SELECT * FROM device_status_logs WHERE timestampMillis BETWEEN :start AND :end ORDER BY timestampMillis ASC")
    suspend fun getStatusLogsBetween(start: Long, end: Long): List<DeviceStatusEntity>
}

@Dao
interface SleepSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SleepSessionEntity)

    @Query("SELECT * FROM sleep_sessions ORDER BY startTimeMillis DESC")
    fun getAllSessions(): Flow<List<SleepSessionEntity>>

    @Query("SELECT * FROM sleep_sessions ORDER BY startTimeMillis DESC LIMIT 1")
    fun getLatestSession(): Flow<SleepSessionEntity?>

    @Query("SELECT * FROM sleep_sessions WHERE isSyncedToHealthConnect = 0")
    suspend fun getUnsyncedSessions(): List<SleepSessionEntity>

    @Query("UPDATE sleep_sessions SET isSyncedToHealthConnect = 1 WHERE startTimeMillis = :startTime")
    suspend fun markSynced(startTime: Long)
}

@Database(
    entities = [EpochEntity::class, DeviceStatusEntity::class, SleepSessionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun epochDao(): EpochDao
    abstract fun deviceStatusDao(): DeviceStatusDao
    abstract fun sleepSessionDao(): SleepSessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sleep_as_ringconn.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
