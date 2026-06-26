package com.strategy.booster.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.strategy.booster.comps.ConcurrentRangeDownloader
import com.strategy.booster.comps.MediaStoreDownloads
import com.strategy.booster.comps.NetworkManager
import com.strategy.booster.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.max
import androidx.core.net.toUri
import com.strategy.booster.notif.DownloadControl
import com.strategy.booster.notif.DownloadControlBridge
import com.strategy.booster.notif.DownloadNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

data class PathStat(val bytes: Long = 0L, val kbps: Int = 0)

data class StatsUi(
    val wifiBytes: Long,
    val cellBytes: Long,
    val totalBytes: Long,
    val finishedCount: Int,
    val queueCount: Int,
    val peakWifiKbps: Int,
    val peakCellKbps: Int,
    val peakTotalKbps: Int
)

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = DownloadRepository(app)
    val allDownloads: Flow<List<DownloadEntry>> = repo.observeAll()

    private val networkManager = NetworkManager(app)

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _wifi = MutableStateFlow(PathStat())
    val wifiStats: StateFlow<PathStat> = _wifi.asStateFlow()

    private val _cell = MutableStateFlow(PathStat())
    val cellStats: StateFlow<PathStat> = _cell.asStateFlow()

    private val _totalKbps = MutableStateFlow(0)
    val totalKbps: StateFlow<Int> = _totalKbps.asStateFlow()

    private val _checksum = MutableStateFlow<String?>(null)
    val checksum: StateFlow<String?> = _checksum.asStateFlow()

    private val titleById = mutableMapOf<Long, String>()
    private val pausedById = mutableMapOf<Long, Boolean>()

    private val lastUiBytes = mutableMapOf<Long, Long>()
    private val lastDbBytes = mutableMapOf<Long, Long>()

    private val uiEmitPeriodMs = 1000L
    private val minByteStepForDb = 64 * 1024L

    val wifiEma = EmaKbps(tauMs = 900)
    val cellEma = EmaKbps(tauMs = 900)
    var lastWifiUpdateMs = 0L
    var lastCellUpdateMs = 0L
    val resumeFreezeMs = 600L
    var freezeWifiUntil = 0L
    var freezeCellUntil = 0L

    private val ACTIVE_NONE = -1L
    private val activeId = java.util.concurrent.atomic.AtomicLong(ACTIVE_NONE)

    private fun tryAcquireSlot(id: Long): Boolean = synchronized(activeId) {
        val cur = activeId.get()
        if (cur == ACTIVE_NONE || cur == id) {
            activeId.set(id); true
        } else false
    }

    private fun releaseSlot(id: Long) = synchronized(activeId) {
        if (activeId.get() == id) activeId.set(ACTIVE_NONE)
    }

    private val settingsStore = SettingsStore(app)
    val settings = settingsStore.flow.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SettingsStore.Snapshot()
    )

    val finishedDownloads = allDownloads.map { list ->
        list.filter { it.status == DownloadStatus.FINISHED }
    }

    class EmaKbps(private val tauMs: Long = 900L) {
        private var lastTs = 0L
        private var ema = 0.0
        fun sample(nowMs: Long, bytesDelta: Long): Int {
            if (lastTs == 0L) {
                lastTs = nowMs; return ema.toInt().coerceAtLeast(0)
            }
            val dt = (nowMs - lastTs).coerceAtLeast(1L)
            lastTs = nowMs
            val instKbps = (bytesDelta * 1000.0 / dt) / 1024.0
            val alpha = 1.0 - kotlin.math.exp(-dt / tauMs.toDouble())
            ema = alpha * instKbps + (1 - alpha) * ema
            return ema.toInt().coerceAtLeast(0)
        }

        fun value(): Int = ema.toInt().coerceAtLeast(0)
        fun reset(keepValue: Boolean = true) {
            if (!keepValue) ema = 0.0; lastTs = 0L
        }
    }

    private val busyIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    private val statsStore = StatsStore(app)

    private val all = repo.observeAll()
    private val finishedCount = all.map { it.count { e -> e.status == DownloadStatus.FINISHED } }
    private val queueCount =
        all.map { it.count { e -> e.status == DownloadStatus.DOWNLOADING || e.status == DownloadStatus.PAUSED } }

    private val pausedIntentIds = MutableStateFlow<Set<Long>>(emptySet())

    val stats: StateFlow<StatsUi> = combine(
        statsStore.flow, finishedCount, queueCount
    ) { s, fCount, qCount ->
        StatsUi(
            wifiBytes = s.wifiBytes,
            cellBytes = s.cellBytes,
            totalBytes = s.totalBytes,
            finishedCount = fCount,
            queueCount = qCount,
            peakWifiKbps = s.peakWifiKbps,
            peakCellKbps = s.peakCellKbps,
            peakTotalKbps = s.peakTotalKbps
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StatsUi(0, 0, 0, 0, 0, 0, 0, 0))

    private val jobs = mutableMapOf<Long, Job>()
    private val downloaders = mutableMapOf<Long, ConcurrentRangeDownloader>()

    val queueDownloads = combine(allDownloads, pausedIntentIds) { list, pausedIds ->
        list.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PAUSED }
            .map { e ->
                if (pausedIds.contains(e.id) && e.status != DownloadStatus.FINISHED && e.status != DownloadStatus.FAILED) {
                    e.copy(status = DownloadStatus.PAUSED)
                } else e
            }
    }

    private fun emitMonotonicUiAndDb(
        id: Long,
        downloaded: Long,
        totalOrNull: Long?,
        updateSingleStats: Boolean
    ) {
        val now = android.os.SystemClock.elapsedRealtime()

        if (totalOrNull != null) {
            viewModelScope.launch {
                repo.setTotalIfMissing(id, totalOrNull)
            }
        }

        val prevDb = lastDbBytes[id] ?: -1L
        val firstDb = prevDb < 0
        if (firstDb || downloaded - prevDb >= minByteStepForDb) {
            lastDbBytes[id] = downloaded
            viewModelScope.launch {
                repo.updateProgress(id, downloaded, totalOrNull)
            }
        }

        if (!updateSingleStats) return

        val prevUi = lastUiBytes[id] ?: -1L
        val firstUi = prevUi < 0
        if (downloaded < prevUi) return

        val allowNow = firstUi || (android.os.SystemClock.elapsedRealtime() - (lastUiEmitMsCache[id]
            ?: 0L) >= uiEmitPeriodMs)

        if (!allowNow && (totalOrNull == null || downloaded < totalOrNull)) return

        lastUiBytes[id] = downloaded
        lastUiEmitMsCache[id] = now

        val pct = if (totalOrNull != null && totalOrNull > 0) {
            ((downloaded * 100) / totalOrNull).toInt().coerceIn(0, 100)
        } else {
            (((downloaded / 131072L) % 100).toInt()).coerceIn(0, 99)
        }

        val downloadedStr = human(downloaded)
        val totalStr = totalOrNull?.let { human(it) }

        _progress.value = pct
        _status.value = if (totalOrNull != null) {
            "Downloading… $pct%\n$downloadedStr / $totalStr"
        } else {
            "Downloading… $pct%\n$downloadedStr"
        }

        updateTotalSpeed(downloaded)
        viewModelScope.launch { statsStore.updatePeakTotal(_totalKbps.value) }
    }

    private val lastUiEmitMsCache = mutableMapOf<Long, Long>()

    fun setUseBoth(v: Boolean) = viewModelScope.launch { settingsStore.setUseBoth(v) }
    fun setWifiSessions(v: Int) = viewModelScope.launch { settingsStore.setWifiSessions(v) }
    fun setCellSessions(v: Int) = viewModelScope.launch { settingsStore.setCellSessions(v) }
    fun setWifiQuotaGb(v: Int) = viewModelScope.launch { settingsStore.setWifiQuotaGb(v) }
    fun setCellQuotaGb(v: Int) = viewModelScope.launch { settingsStore.setCellQuotaGb(v) }
    fun setDownloadDir(uri: String?) = viewModelScope.launch { settingsStore.setDownloadDir(uri) }
    fun setMaxRetries(v: Int) = viewModelScope.launch { settingsStore.setMaxRetries(v) }

    init {
        networkManager.start()
        viewModelScope.launch {
            repo.markAllDownloadingAsPausedOnColdStart()
        }

        DownloadControlBridge.delegate = object : DownloadControl {
            override fun pause(id: Long) {
                togglePauseResume(id)
            }

            override fun resume(id: Long) {
                togglePauseResume(id)
            }

            override fun exit(id: Long) {
                removeNow(id)
            }
        }
    }

    override fun onCleared() {
        networkManager.stop(); super.onCleared()
    }

    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }

    fun removeNow(id: Long) {
        if (!busyIds.add(id)) return
        viewModelScope.launch {
            try {
                val e = repo.getById(id) ?: return@launch
                if (e.status == DownloadStatus.DOWNLOADING || e.status == DownloadStatus.PENDING) {
                    pausedIntentIds.value = pausedIntentIds.value + id
                    repo.markPaused(id)
                    cancelAndWait(id)
                }
                DownloadNotifier.cancel(getApplication(), id)
                releaseSlot(id)
                remove(id)
            } finally {
                busyIds.remove(id)
            }
        }
    }

    private suspend fun cancelAndWait(id: Long) {
        downloaders[id]?.pause()
        downloaders[id]?.cancelActiveRequests()
        jobs[id]?.cancel()
        runCatching { jobs[id]?.join() }
        jobs.remove(id)
        downloaders.remove(id)
    }

    private fun isTerminalError(err: String?): Boolean {
        val m = err?.lowercase() ?: return false

        val http = Regex("""\bhttp\s+(\d{3})""").find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (http != null) {
            return when (http) {
                400, 401, 403, 404, 405, 406, 410, 411, 412, 413, 414, 415, 416, 417,
                422, 423, 424, 426, 451 -> true

                408, 429 -> false
                else -> false
            }
        }

        if ("unknownhost" in m || "no address associated" in m || "nxdomain" in m) return true

        if ("checksum mismatch" in m) return true

        return false
    }

    fun clearFinishedRecordsOnly() = viewModelScope.launch {
        repo.clearFinishedRecordsOnly()
    }

    fun clearFinishedWithFiles() = viewModelScope.launch {
        repo.clearFinishedWithFiles()
    }

    fun addDownloadWithOverrides(
        url: String,
        customDisplayName: String?,
        preferredDirTreeUri: Uri?
    ) {
        val ctx = getApplication<Application>()

        viewModelScope.launch {
            val s = settings.value

            val dirString: String? = preferredDirTreeUri?.toString() ?: s.downloadDirUri

            val (displayName, uri) = MediaStoreDownloads
                .createTargetInSettingsDirOrDownloads(
                    context = ctx,
                    preferredDirTreeUri = dirString,
                    fileNameFromUrl = customDisplayName
                        ?: url.substringAfterLast('/').substringBefore('?')
                            .ifBlank { "download.bin" }
                )

            val id = repo.create(url, displayName, uri.toString())

            startOrResumeInternal(
                id = id,
                url = url,
                displayName = displayName,
                targetUri = uri,
                expectedSha256 = null,
                wifiSessions = s.wifiSessions,
                cellSessions = if (s.useBoth) s.cellSessions else 0,
                updateSingleStats = true,
                wifiQuotaBytes = s.wifiQuotaBytes,
                cellQuotaBytes = s.cellQuotaBytes
            )
        }
    }

    private val speedWindow = ArrayDeque<Pair<Long, Long>>()
    private val windowMs = 500L
    private fun updateTotalSpeed(totalBytes: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        speedWindow.addLast(now to totalBytes)

        while (speedWindow.size > 1 && speedWindow.first().first < now - windowMs) {
            speedWindow.removeFirst()
        }

        if (speedWindow.size >= 2) {
            val (t0, b0) = speedWindow.first()
            val (t1, b1) = speedWindow.last()
            val dt = max(1L, t1 - t0)
            val dBytes = max(0L, b1 - b0)
            val instKbps = (dBytes * 1000.0 / dt) / 1024.0
            _totalKbps.value = instKbps.toInt().coerceAtLeast(0)
        }
    }

    fun togglePauseResume(id: Long) {
        if (!busyIds.add(id)) return

        viewModelScope.launch {
            try {
                val e = repo.getById(id) ?: return@launch
                when (e.status) {
                    DownloadStatus.PAUSED -> {
                        if (!tryAcquireSlot(id)) {
                            pausedById[id] = true
                            DownloadNotifier.showOrUpdate(
                                getApplication(),
                                id,
                                e.displayName,
                                e.downloadedBytes,
                                e.totalBytes,
                                isPaused = true
                            )
                            return@launch
                        }

                        pausedIntentIds.value = pausedIntentIds.value - id
                        repo.markDownloading(id)

                        pausedById[id] = false
                        DownloadNotifier.showOrUpdate(
                            getApplication(),
                            id,
                            e.displayName,
                            e.downloadedBytes,
                            e.totalBytes,
                            isPaused = false
                        )

                        val now = android.os.SystemClock.elapsedRealtime()
                        freezeWifiUntil = now + resumeFreezeMs
                        freezeCellUntil = now + resumeFreezeMs
                        wifiEma.reset(keepValue = true)
                        cellEma.reset(keepValue = true)

                        val s = settings.value
                        startOrResumeInternal(
                            id = id,
                            url = e.url,
                            displayName = e.displayName,
                            targetUri = e.uri.toUri(),
                            expectedSha256 = null,
                            wifiSessions = s.wifiSessions,
                            cellSessions = if (s.useBoth) s.cellSessions else 0,
                            updateSingleStats = true,
                            wifiQuotaBytes = s.wifiQuotaBytes,
                            cellQuotaBytes = s.cellQuotaBytes
                        )
                    }

                    DownloadStatus.DOWNLOADING, DownloadStatus.PENDING -> {
                        pausedIntentIds.value = pausedIntentIds.value + id
                        repo.markPaused(id)
                        pausedById[id] = true
                        DownloadNotifier.showOrUpdate(
                            getApplication(),
                            id,
                            e.displayName,
                            e.downloadedBytes,
                            e.totalBytes,
                            isPaused = true
                        )
                        cancelAndWait(id)
                        releaseSlot(id)
                    }

                    else -> Unit
                }
            } finally {
                busyIds.remove(id)
            }
        }
    }

    fun remove(id: Long) {
        viewModelScope.launch {
            val entry = repo.getById(id) ?: return@launch

            when (entry.status) {
                DownloadStatus.PAUSED, DownloadStatus.FINISHED, DownloadStatus.FAILED -> {
                    downloaders[id]?.pause()
                    downloaders[id]?.cancelActiveRequests()
                    jobs[id]?.cancel()
                    runCatching { jobs[id]?.join() }
                    jobs.remove(id)
                    downloaders.remove(id)

                    pausedIntentIds.value = pausedIntentIds.value - id

                    DownloadNotifier.cancel(getApplication(), id)

                    repo.deleteCompletely(id)
                    releaseSlot(id)
                }

                else -> {
                    // ignore
                }
            }
        }
    }

    private fun startOrResumeInternal(
        id: Long,
        url: String,
        displayName: String,
        targetUri: Uri,
        expectedSha256: String?,
        wifiSessions: Int,
        cellSessions: Int,
        updateSingleStats: Boolean,
        wifiQuotaBytes: Long,
        cellQuotaBytes: Long
    ) {
        jobs[id]?.cancel()

        val ctx = getApplication<Application>()
        val wifiNet = networkManager.wifiNetwork.value
        val cellNet = networkManager.cellNetwork.value
        val settingsSnapshot = settings.value
        val peakWifiNow = java.util.concurrent.atomic.AtomicInteger(0)
        val peakCellNow = java.util.concurrent.atomic.AtomicInteger(0)
        val peakTotalNow = java.util.concurrent.atomic.AtomicInteger(0)

        val (finalWifiSess, finalCellSess) = if (settingsSnapshot.useBoth) {
            wifiSessions.coerceAtLeast(1) to cellSessions.coerceAtLeast(1)
        } else {
            when {
                wifiNet != null && cellNet == null -> wifiSessions.coerceAtLeast(1) to 0
                cellNet != null && wifiNet == null -> 0 to cellSessions.coerceAtLeast(1)
                wifiNet != null && cellNet != null -> {
                    val activeIsCell = networkManager.activeIsCellular(ctx)
                    if (activeIsCell) 0 to cellSessions.coerceAtLeast(1)
                    else wifiSessions.coerceAtLeast(1) to 0
                }

                else -> {
                    wifiSessions.coerceAtLeast(1) to 0
                }
            }
        }

        titleById[id] = displayName
        pausedById[id] = false
        DownloadNotifier.showOrUpdate(
            getApplication(), id, displayName, 0L, null, isPaused = false
        )

        if (!tryAcquireSlot(id)) {
            pausedById[id] = true
            viewModelScope.launch {
                repo.markPaused(id)
                val e = repo.getById(id)
                DownloadNotifier.showOrUpdate(
                    getApplication(),
                    id,
                    e?.displayName ?: displayName,
                    e?.downloadedBytes ?: 0L,
                    e?.totalBytes,
                    isPaused = true
                )
            }
            return
        }

        val maxRetries = settings.value.maxRetries
        fun backoffMs(n: Int): Long = if (n <= 0) 0 else minOf(2000L shl (n - 1), 15_000L)

        val parentJob = viewModelScope.launch(Dispatchers.IO) {
            var attempt = 0
            try {
                while (true) {
                    val result = kotlinx.coroutines.CompletableDeferred<Pair<Boolean, String?>>()

                    val peakWifiNow = java.util.concurrent.atomic.AtomicInteger(0)
                    val peakCellNow = java.util.concurrent.atomic.AtomicInteger(0)
                    val peakTotalNow = java.util.concurrent.atomic.AtomicInteger(0)
                    val wifiDeltaAcc = java.util.concurrent.atomic.AtomicLong(0L)
                    val cellDeltaAcc = java.util.concurrent.atomic.AtomicLong(0L)
                    var flushJob: Job? = null

                    val d = ConcurrentRangeDownloader(
                        context = ctx,
                        url = url,
                        displayName = displayName,
                        wifiNetwork = wifiNet,
                        cellNetwork = cellNet,
                        targetUri = targetUri,
                        scope = this,
                        wifiSessions = finalWifiSess,
                        cellSessions = finalCellSess,
                        expectedSha256 = expectedSha256,
                        wifiQuotaBytes = wifiQuotaBytes,
                        cellQuotaBytes = cellQuotaBytes,
                        wifiEngineCount = 2,
                        cellEngineCount = 2,
                        useOkHttpForCell = true,
                        wifiSliceMaxBytes = 128L * 1024 * 1024,
                        cellSliceMaxBytes = 128L * 1024 * 1024
                    )
                    downloaders[id] = d

                    flushJob?.cancel()
                    flushJob = launch(Dispatchers.IO) {
                        while (isActive && downloaders.containsKey(id)) {
                            delay(1000L)
                            val w = wifiDeltaAcc.getAndSet(0L)
                            val c = cellDeltaAcc.getAndSet(0L)
                            val t = w + c
                            if (w != 0L || c != 0L) {
                                statsStore.addBatch(wifiDelta = w, cellDelta = c, totalDelta = t)
                            }
                            statsStore.updatePeaksIfHigher(
                                wifiKbps = peakWifiNow.get(),
                                cellKbps = peakCellNow.get(),
                                totalKbps = peakTotalNow.get()
                            )
                        }
                    }

                    jobs[id] = d.start(
                        onAnyProgress = { downloaded, totalOrNull ->
                            if (!downloaders.containsKey(id)) return@start
                            val pausedNow =
                                pausedIntentIds.value.contains(id) || pausedById[id] == true
                            if (pausedNow) return@start

                            updateTotalSpeed(downloaded)

                            emitMonotonicUiAndDb(
                                id = id,
                                downloaded = downloaded,
                                totalOrNull = totalOrNull,
                                updateSingleStats = updateSingleStats
                            )

                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastWifiUpdateMs > 250) {
                                var shown = wifiEma.sample(now, 0)
                                if (now < freezeWifiUntil) shown = maxOf(shown, wifiEma.value())
                                _wifi.value = _wifi.value.copy(kbps = shown)
                            }
                            if (now - lastCellUpdateMs > 250) {
                                var shown = cellEma.sample(now, 0)
                                if (now < freezeCellUntil) shown = maxOf(shown, cellEma.value())
                                _cell.value = _cell.value.copy(kbps = shown)
                            }
                            _totalKbps.value = wifiEma.value() + cellEma.value()

                            DownloadNotifier.showOrUpdate(
                                getApplication(),
                                id,
                                titleById[id] ?: displayName,
                                downloaded,
                                totalOrNull,
                                isPaused = false
                            )
                        },
                        onPathProgress = { path, bytesDelta, pathTotal ->
                            if (!downloaders.containsKey(id)) return@start
                            if (pausedIntentIds.value.contains(id)) return@start
                            if (!updateSingleStats) return@start

                            val now = android.os.SystemClock.elapsedRealtime()
                            when (path) {
                                "wifi" -> {
                                    lastWifiUpdateMs = now
                                    var shown = wifiEma.sample(now, bytesDelta)
                                    if (now < freezeWifiUntil) shown =
                                        maxOf(shown, wifiEma.value())
                                    _wifi.value = PathStat(pathTotal, shown)

                                    wifiDeltaAcc.addAndGet(bytesDelta)
                                    peakWifiNow.accumulateAndGet(shown) { a, b -> maxOf(a, b) }
                                }

                                "cell" -> {
                                    lastCellUpdateMs = now
                                    var shown = cellEma.sample(now, bytesDelta)
                                    if (now < freezeCellUntil) shown = maxOf(shown, cellEma.value())
                                    _cell.value = PathStat(pathTotal, shown)

                                    cellDeltaAcc.addAndGet(bytesDelta)
                                    peakCellNow.accumulateAndGet(shown) { a, b -> maxOf(a, b) }
                                }
                            }
                            val totKbps = _wifi.value.kbps + _cell.value.kbps
                            peakTotalNow.accumulateAndGet(totKbps) { a, b -> maxOf(a, b) }
                        },
                        onComplete = { success, error ->
                            launch {
                                val w = wifiDeltaAcc.getAndSet(0L)
                                val c = cellDeltaAcc.getAndSet(0L)
                                val t = w + c
                                if (w != 0L || c != 0L) {
                                    statsStore.addBatch(
                                        wifiDelta = w,
                                        cellDelta = c,
                                        totalDelta = t
                                    )
                                }
                                statsStore.updatePeaksIfHigher(
                                    wifiKbps = peakWifiNow.get(),
                                    cellKbps = peakCellNow.get(),
                                    totalKbps = peakTotalNow.get()
                                )
                            }
                            flushJob?.cancel()
                            flushJob = null

                            pausedIntentIds.value = pausedIntentIds.value - id

                            result.complete(success to error)
                        },
                        onChecksumReady = { sha, matches ->
                            _checksum.value = sha
                            if (updateSingleStats) {
                                _status.value = _status.value + when (matches) {
                                    null -> " • SHA-256: $sha"; true -> " • SHA-256 OK"; false -> " • SHA-256 MISMATCH"
                                }
                            }
                        }
                    )
                    val (success, error) = result.await()

                    jobs.remove(id)
                    downloaders.remove(id)
                    lastUiBytes.remove(id)
                    lastDbBytes.remove(id)
                    lastUiEmitMsCache.remove(id)

                    if (success) {
                        repo.markFinished(id, _checksum.value)
                        DownloadNotifier.cancel(ctx, id)
                        _progress.value = 100
                        _status.value = "Completed: $displayName"
                        break
                    }

                    val err = error ?: "Network/Server error"
                    if (isTerminalError(err) || attempt >= maxRetries) {
                        repo.markFailed(id, err, keepProgress = false)
                        runCatching { ctx.contentResolver.delete(targetUri, null, null) }
                        DownloadNotifier.cancel(ctx, id)
                        break
                    }

                    repo.markPausedWithError(id, err)
                    val entry = repo.getById(id)
                    DownloadNotifier.showOrUpdate(
                        ctx,
                        id,
                        entry?.displayName ?: titleById[id] ?: "Download",
                        entry?.downloadedBytes ?: 0L,
                        entry?.totalBytes,
                        isPaused = true
                    )

                    attempt++
                    val waitMs = backoffMs(attempt)
                    _status.value = "Retrying in ${waitMs / 1000}s… (attempt $attempt/$maxRetries)"

                    var waited = 0L
                    while (waited < waitMs && isActive && !pausedIntentIds.value.contains(id)) {
                        delay(200L); waited += 200L
                    }
                    if (pausedIntentIds.value.contains(id) || !isActive) break

                    DownloadNotifier.showOrUpdate(
                        ctx,
                        id,
                        displayName,
                        entry?.downloadedBytes ?: 0L,
                        entry?.totalBytes,
                        isPaused = false
                    )
                }
            } finally {
                titleById.remove(id)
                pausedById.remove(id)
                releaseSlot(id)
            }
        }

        jobs[id] = parentJob
    }
}
