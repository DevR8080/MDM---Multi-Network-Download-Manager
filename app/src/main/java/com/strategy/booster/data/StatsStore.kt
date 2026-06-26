package com.strategy.booster.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.statsDataStore by preferencesDataStore("download_stats")

class StatsStore(private val ctx: Context) {

    private object Keys {
        val WIFI_BYTES = longPreferencesKey("wifi_bytes")
        val CELL_BYTES = longPreferencesKey("cell_bytes")
        val TOTAL_BYTES = longPreferencesKey("total_bytes")
        val PEAK_WIFI_KBPS = intPreferencesKey("peak_wifi_kbps")
        val PEAK_CELL_KBPS = intPreferencesKey("peak_cell_kbps")
        val PEAK_TOTAL_KBPS = intPreferencesKey("peak_total_kbps")
    }

    data class Snapshot(
        val wifiBytes: Long = 0,
        val cellBytes: Long = 0,
        val totalBytes: Long = 0,
        val peakWifiKbps: Int = 0,
        val peakCellKbps: Int = 0,
        val peakTotalKbps: Int = 0
    )

    val flow: Flow<Snapshot> = ctx.statsDataStore.data.map { p ->
        Snapshot(
            wifiBytes = p[Keys.WIFI_BYTES] ?: 0L,
            cellBytes = p[Keys.CELL_BYTES] ?: 0L,
            totalBytes = p[Keys.TOTAL_BYTES] ?: 0L,
            peakWifiKbps = p[Keys.PEAK_WIFI_KBPS] ?: 0,
            peakCellKbps = p[Keys.PEAK_CELL_KBPS] ?: 0,
            peakTotalKbps = p[Keys.PEAK_TOTAL_KBPS] ?: 0
        )
    }

    suspend fun updatePeakTotal(kbps: Int) = ctx.statsDataStore.edit {
        val cur = it[Keys.PEAK_TOTAL_KBPS] ?: 0
        if (kbps > cur) it[Keys.PEAK_TOTAL_KBPS] = kbps
    }

    suspend fun addBatch(wifiDelta: Long, cellDelta: Long, totalDelta: Long) {
        if (wifiDelta == 0L && cellDelta == 0L && totalDelta == 0L) return
        ctx.statsDataStore.edit {
            if (wifiDelta != 0L) it[Keys.WIFI_BYTES] = (it[Keys.WIFI_BYTES] ?: 0L) + wifiDelta
            if (cellDelta != 0L) it[Keys.CELL_BYTES] = (it[Keys.CELL_BYTES] ?: 0L) + cellDelta
            if (totalDelta != 0L) it[Keys.TOTAL_BYTES] = (it[Keys.TOTAL_BYTES] ?: 0L) + totalDelta
        }
    }

    suspend fun updatePeaksIfHigher(wifiKbps: Int?, cellKbps: Int?, totalKbps: Int?) {
        ctx.statsDataStore.edit {
            wifiKbps?.let { v -> if (v > (it[Keys.PEAK_WIFI_KBPS] ?: 0)) it[Keys.PEAK_WIFI_KBPS] = v }
            cellKbps?.let { v -> if (v > (it[Keys.PEAK_CELL_KBPS] ?: 0)) it[Keys.PEAK_CELL_KBPS] = v }
            totalKbps?.let { v -> if (v > (it[Keys.PEAK_TOTAL_KBPS] ?: 0)) it[Keys.PEAK_TOTAL_KBPS] = v }
        }
    }
}
