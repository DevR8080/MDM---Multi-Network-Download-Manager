package com.strategy.booster.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")

class SettingsStore(private val ctx: Context) {

    object Keys {
        val USE_BOTH   = booleanPreferencesKey("use_both_networks")
        val WIFI_SESS  = intPreferencesKey("wifi_sessions")
        val CELL_SESS  = intPreferencesKey("cell_sessions")

        val WIFI_QUOTA_GB = intPreferencesKey("wifi_quota_gb")
        val CELL_QUOTA_GB = intPreferencesKey("cell_quota_gb")

        val DOWNLOAD_DIR = stringPreferencesKey("download_dir_uri")
        val KEY_MAX_RETRIES = intPreferencesKey("max_retries")
    }

    data class Snapshot(
        val useBoth: Boolean = true,
        val wifiSessions: Int = 8,
        val cellSessions: Int = 8,
        val wifiQuotaBytes: Long = 0L,
        val cellQuotaBytes: Long = 0L,
        val downloadDirUri: String? = null,
        val maxRetries: Int = 3
    )

    private fun gbToBytes(gb: Int): Long {
        if (gb <= 0) return 0L
        val g = gb.toLong()
        val bytes = g * 1024L * 1024L * 1024L
        return if (bytes < 0L) Long.MAX_VALUE else bytes
    }

    val flow: Flow<Snapshot> = ctx.settingsDataStore.data.map { p ->
        Snapshot(
            useBoth = p[Keys.USE_BOTH] ?: true,
            wifiSessions = (p[Keys.WIFI_SESS] ?: 8).coerceIn(1, 32),
            cellSessions = (p[Keys.CELL_SESS] ?: 8).coerceIn(0, 32),
            wifiQuotaBytes = gbToBytes(p[Keys.WIFI_QUOTA_GB] ?: 0),
            cellQuotaBytes = gbToBytes(p[Keys.CELL_QUOTA_GB] ?: 0),
            downloadDirUri = p[Keys.DOWNLOAD_DIR],
            maxRetries = p[Keys.KEY_MAX_RETRIES] ?: 3
        )
    }

    suspend fun setUseBoth(v: Boolean) = edit { it[Keys.USE_BOTH] = v }
    suspend fun setWifiSessions(v: Int) = edit { it[Keys.WIFI_SESS] = v.coerceIn(1, 32) }
    suspend fun setCellSessions(v: Int) = edit { it[Keys.CELL_SESS] = v.coerceIn(0, 32) }

    suspend fun setWifiQuotaGb(v: Int) = edit { it[Keys.WIFI_QUOTA_GB] = v.coerceAtLeast(0) }
    suspend fun setCellQuotaGb(v: Int) = edit { it[Keys.CELL_QUOTA_GB] = v.coerceAtLeast(0) }

    suspend fun setDownloadDir(uri: String?) = edit {
        if (uri == null) it.remove(Keys.DOWNLOAD_DIR) else it[Keys.DOWNLOAD_DIR] = uri
    }

    suspend fun setMaxRetries(v: Int) = edit { it[Keys.KEY_MAX_RETRIES] = v.coerceIn(0, 10) }

    private suspend fun edit(block: (MutablePreferences) -> Unit) =
        ctx.settingsDataStore.edit(block)
}
