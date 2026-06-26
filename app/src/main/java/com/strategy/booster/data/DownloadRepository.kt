package com.strategy.booster.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import java.io.File
import androidx.core.net.toUri

class DownloadRepository(private val context: Context) {
    private val dao = AppDatabase.get(context).downloadDao()

    fun observeAll(): Flow<List<DownloadEntry>> = dao.observeAll()

    suspend fun create(url: String, displayName: String, uri: String): Long {
        return dao.insert(
            DownloadEntry(
                url = url,
                displayName = displayName,
                uri = uri,
                status = DownloadStatus.PENDING
            )
        )
    }

    suspend fun updateProgress(id: Long, downloaded: Long, total: Long?) {
        val old = dao.getById(id) ?: return
        val newStatus = when (old.status) {
            DownloadStatus.PAUSED,
            DownloadStatus.FINISHED,
            DownloadStatus.FAILED -> old.status
            else -> DownloadStatus.DOWNLOADING
        }
        dao.update(
            old.copy(
                downloadedBytes = downloaded,
                totalBytes = total ?: old.totalBytes,
                status = newStatus,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun markDownloading(id: Long) {
        val old = dao.getById(id) ?: return
        if (old.status != DownloadStatus.FINISHED && old.status != DownloadStatus.FAILED) {
            dao.update(old.copy(status = DownloadStatus.DOWNLOADING, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun deleteCompletely(id: Long) {
        val entry = dao.getById(id) ?: return

        runCatching {
            context.contentResolver.delete(entry.uri.toUri(), null, null)
        }

        runCatching {
            val total = entry.totalBytes
            if (total != null) {
                File(context.filesDir, "download_states")
                    .resolve("${entry.displayName}_${total}.state")
                    .delete()
            }
        }

        runCatching {
            val total = entry.totalBytes
            if (total != null) {
                File(context.cacheDir, "dltemp")
                    .resolve("${entry.displayName}_${total}.part")
                    .delete()
            }
        }

        dao.deleteById(id)
    }

    suspend fun getById(id: Long): DownloadEntry? = dao.getById(id)

    suspend fun markPaused(id: Long) {
        val old = dao.getById(id) ?: return
        dao.update(old.copy(status = DownloadStatus.PAUSED, updatedAt = System.currentTimeMillis()))
    }

    suspend fun markFinished(id: Long, sha256: String?) {
        val old = dao.getById(id) ?: return
        dao.update(
            old.copy(
                status = DownloadStatus.FINISHED,
                sha256 = sha256 ?: old.sha256,
                updatedAt = System.currentTimeMillis(),
                downloadedBytes = old.totalBytes ?: old.downloadedBytes,
                lastError = null
            )
        )
    }

    suspend fun markAllDownloadingAsPausedOnColdStart() {
        dao.markAllDownloadingToPaused(System.currentTimeMillis())
    }

    suspend fun markFailed(id: Long, error: String? = null, keepProgress: Boolean = true) {
        val old = dao.getById(id) ?: return
        dao.update(
            old.copy(
                status = DownloadStatus.FAILED,
                updatedAt = System.currentTimeMillis(),
                downloadedBytes = if (keepProgress) old.downloadedBytes else 0,
                lastError = error
            )
        )
    }

    suspend fun setTotalIfMissing(id: Long, total: Long?) {
        if (total == null) return
        val old = dao.getById(id) ?: return
        if (old.totalBytes == null) {
            dao.update(old.copy(totalBytes = total, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun listFinished(): List<DownloadEntry> = dao.listByStatus(DownloadStatus.FINISHED)

    suspend fun clearFinishedRecordsOnly() {
        listFinished().forEach { e ->
            dao.deleteById(e.id)
        }
    }

    suspend fun clearFinishedWithFiles() {
        listFinished().forEach { e ->
            deleteCompletely(e.id)
        }
    }

    suspend fun markPausedWithError(id: Long, error: String?) {
        val old = dao.getById(id) ?: return
        dao.update(
            old.copy(
                status = DownloadStatus.PAUSED,
                updatedAt = System.currentTimeMillis(),
                lastError = error
            )
        )
    }

}