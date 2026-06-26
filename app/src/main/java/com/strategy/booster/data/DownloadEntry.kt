package com.strategy.booster.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val displayName: String,
    val uri: String,
    val totalBytes: Long? = null,
    val downloadedBytes: Long = 0L,
    val sha256: String? = null,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastError: String? = null
)