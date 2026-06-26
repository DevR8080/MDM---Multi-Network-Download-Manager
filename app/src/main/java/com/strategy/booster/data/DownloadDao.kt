package com.strategy.booster.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DownloadEntry): Long

    @Update
    suspend fun update(entry: DownloadEntry)

    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DownloadEntry>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntry?

    @Query("SELECT * FROM downloads WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): DownloadEntry?

    @Delete
    suspend fun delete(entry: DownloadEntry)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE downloads SET status = :paused, updatedAt = :now WHERE status = :downloading")
    suspend fun markAllDownloadingToPaused(
        now: Long,
        paused: DownloadStatus = DownloadStatus.PAUSED,
        downloading: DownloadStatus = DownloadStatus.DOWNLOADING
    )

    @Query("SELECT * FROM downloads WHERE status = :status")
    suspend fun listByStatus(status: DownloadStatus): List<DownloadEntry>
}