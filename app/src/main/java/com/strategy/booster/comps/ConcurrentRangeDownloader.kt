package com.strategy.booster.comps

import android.content.Context
import android.net.Network
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

class ConcurrentRangeDownloader(
    private val context: Context,
    private val url: String,
    private val displayName: String,
    private val wifiNetwork: Network?,
    private val cellNetwork: Network?,
    private val targetUri: Uri,
    private val scope: CoroutineScope,
    private val wifiSessions: Int,
    private val cellSessions: Int,
    private val expectedSha256: String? = null,
    private val wifiQuotaBytes: Long = 0,
    private val cellQuotaBytes: Long = 0,
    private val wifiEngineCount: Int,
    private val cellEngineCount: Int,
    private val useOkHttpForCell: Boolean = true,
    private val wifiSliceMaxBytes: Long,
    private val cellSliceMaxBytes: Long,
    private val autoSizeByTotal: Boolean = true,
    private val autoDivisor: Int = 15,
    private val minAutoBytes: Long = 1L * 1024 * 1024,
    private val maxAutoBytes: Long = 128L * 1024 * 1024
) {

    companion object {
        private const val KEY_UNBOUND: Long = -1L

        private val engineCache = java.util.concurrent.ConcurrentHashMap<Pair<Long, Int>, CronetEngine>()

        private fun buildEngine(ctx: Context, net: Network?): CronetEngine {
            return try {
                val cls = Class.forName("org.chromium.net.experimental.ExperimentalCronetEngine\$Builder")
                val exp = cls.getConstructor(Context::class.java).newInstance(ctx)
                cls.getMethod("enableQuic", Boolean::class.javaPrimitiveType).invoke(exp, true)
                cls.getMethod("enableBrotli", Boolean::class.javaPrimitiveType).invoke(exp, true)
                if (net != null) {
                    val setNetwork = cls.getMethod("setNetwork", java.lang.Long.TYPE)
                    setNetwork.invoke(exp, net.networkHandle)
                }
                val build = cls.getMethod("build")
                build.invoke(exp) as CronetEngine
            } catch (_: Throwable) {
                CronetEngine.Builder(ctx)
                    .enableHttp2(true)
                    .enableQuic(true)
                    .enableBrotli(true)
                    .setUserAgent("Booster/1.0")
                    .build()
            }
        }

        private fun getEngine(ctx: Context, net: Network?, idx: Int): CronetEngine {
            val key = (net?.networkHandle ?: KEY_UNBOUND) to idx
            return engineCache[key] ?: synchronized(engineCache) {
                engineCache[key] ?: buildEngine(ctx, net).also { engineCache[key] = it }
            }
        }
    }

    @Volatile
    private var completedMergedSnap: List<LongRange> = emptyList()

    private fun claimTrimmedFromQueue(
        queue: ArrayDeque<Pair<Long, Long>>,
        sliceMax: Long
    ): Pair<Long, Long>? {
        while (true) {
            val head = synchronized(queue) { if (queue.isEmpty()) null else queue.removeFirst() } ?: return null
            val base = head.first..head.second
            val cuts = completedMergedSnap
            val gaps = subtractCuts(base, cuts)
            if (gaps.isEmpty()) continue
            val g = gaps.first()
            val s = g.first
            val eCap = minOf(s + sliceMax - 1, g.last)
            val claim = s to eCap
            synchronized(queue) {
                if (eCap < g.last) queue.addFirst((eCap + 1) to g.last)
                for (i in gaps.lastIndex downTo 1) {
                    val rg = gaps[i]
                    queue.addFirst(rg.first to rg.last)
                }
            }
            return claim
        }
    }

    private fun claimWithSteal(
        own: ArrayDeque<Pair<Long, Long>>,
        other: ArrayDeque<Pair<Long, Long>>?,
        sliceMax: Long
    ): Pair<Long, Long>? {
        claimTrimmedFromQueue(own, sliceMax)?.let { return it }
        if (other != null) return claimTrimmedFromQueue(other, sliceMax)
        return null
    }

    private fun claimTrimmedSlice(
        globalQueue: ArrayDeque<Pair<Long, Long>>,
        sliceMax: Long,
    ): Pair<Long, Long>? {
        while (true) {
            val head = synchronized(globalQueue) { if (globalQueue.isEmpty()) null else globalQueue.removeFirst() }
                ?: return null

            val base = head.first..head.second

            val cuts = completedMergedSnap

            val gaps = subtractCuts(base, cuts)
            if (gaps.isEmpty()) {
                continue
            }

            val g = gaps.first()
            val s = g.first
            val eCap = minOf(s + sliceMax - 1, g.last)
            val claim = s to eCap

            synchronized(globalQueue) {
                if (eCap < g.last) {
                    globalQueue.addFirst((eCap + 1) to g.last)
                }

                for (i in gaps.lastIndex downTo 1) {
                    val rg = gaps[i]
                    globalQueue.addFirst(rg.first to rg.last)
                }
            }

            return claim
        }
    }

    private val isPaused = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)
    fun pause() {
        isPaused.set(true); isStopping.set(true); cancelActiveRequests()
    }

    private class RoundRobin<T>(private val list: List<T>) {
        private val i = java.util.concurrent.atomic.AtomicInteger(0)
        fun next(): T = list[(i.getAndIncrement() and Int.MAX_VALUE) % list.size]
    }

    private class NetworkDns(private val network: Network) : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            return network.getAllByName(hostname)?.toList() ?: emptyList()
        }
    }

    private fun buildOkHttpForNetwork(net: Network): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }
        val pool = ConnectionPool(64, 5, TimeUnit.SECONDS)

        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(pool)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .dns(NetworkDns(net))
            .socketFactory(net.socketFactory)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    private val cronetExec = Executors.newCachedThreadPool()

    private val activeRequests = Collections.synchronizedSet(mutableSetOf<UrlRequest>())

    private inline fun <T> tryResumeOnce(
        already: AtomicBoolean,
        crossinline block: () -> T,
        cont: kotlin.coroutines.Continuation<T>
    ) {
        if (already.compareAndSet(false, true)) {
            runCatching { cont.resume(block()) }
                .onFailure { /* ignore */ }
        }
    }

    fun cancelActiveRequests() {
        val snap = synchronized(activeRequests) { activeRequests.toList() }
        snap.forEach { runCatching { it.cancel() } }
    }

    private suspend fun probeHead(engine: CronetEngine): Triple<Long?, String?, String?> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)

            val callback = object : UrlRequest.Callback() {
                override fun onRedirectReceived(
                    r: UrlRequest,
                    info: UrlResponseInfo,
                    newLoc: String
                ) {
                    r.followRedirect()
                }

                override fun onResponseStarted(r: UrlRequest, info: UrlResponseInfo) {
                    val code = info.httpStatusCode
                    if (code !in 200..299) {
                        tryResumeOnce(done, { Triple(null, null, null) }, cont)
                        r.cancel(); return
                    }
                    val len = info.allHeaders["Content-Length"]?.firstOrNull()?.toLongOrNull()
                    val etag = info.allHeaders["ETag"]?.firstOrNull()
                    val lm = info.allHeaders["Last-Modified"]?.firstOrNull()
                    tryResumeOnce(done, { Triple(len, etag, lm) }, cont)
                    r.cancel()
                }

                override fun onReadCompleted(
                    r: UrlRequest,
                    info: UrlResponseInfo,
                    bb: ByteBuffer
                ) {
                }

                override fun onSucceeded(r: UrlRequest, info: UrlResponseInfo) {
                    tryResumeOnce(done, { Triple(null, null, null) }, cont)
                }

                override fun onFailed(
                    r: UrlRequest,
                    info: UrlResponseInfo?,
                    e: org.chromium.net.CronetException
                ) {
                    tryResumeOnce(done, { Triple(null, null, null) }, cont)
                }

                override fun onCanceled(r: UrlRequest, info: UrlResponseInfo?) {
                    tryResumeOnce(done, { Triple(null, null, null) }, cont)
                }
            }

            val req = engine.newUrlRequestBuilder(url, callback, cronetExec)
                .disableCache()
                .setPriority(UrlRequest.Builder.REQUEST_PRIORITY_HIGHEST)
                .setHttpMethod("HEAD")
                .build()
            req.start()
            cont.invokeOnCancellation { runCatching { req.cancel() } }
        }

    private suspend fun probeRange0(engine: CronetEngine): Triple<Long?, String?, String?> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)

            val callback = object : UrlRequest.Callback() {
                override fun onRedirectReceived(
                    r: UrlRequest,
                    info: UrlResponseInfo,
                    newLoc: String
                ) {
                    r.followRedirect()
                }

                override fun onResponseStarted(r: UrlRequest, info: UrlResponseInfo) {
                    val code = info.httpStatusCode
                    if (code != 206 && code !in 200..299) {
                        tryResumeOnce(done, { Triple(null, null, null) }, cont)
                        r.cancel(); return
                    }
                    val cr = info.allHeaders["Content-Range"]?.firstOrNull()
                    val total = cr?.substringAfter('/')?.toLongOrNull()
                        ?: info.allHeaders["Content-Length"]?.firstOrNull()?.toLongOrNull()
                    val etag = info.allHeaders["ETag"]?.firstOrNull()
                    val lm = info.allHeaders["Last-Modified"]?.firstOrNull()
                    tryResumeOnce(done, { Triple(total, etag, lm) }, cont)
                    r.cancel()
                }

                override fun onReadCompleted(
                    r: UrlRequest,
                    info: UrlResponseInfo,
                    bb: ByteBuffer
                ) {
                }

                override fun onSucceeded(r: UrlRequest, info: UrlResponseInfo) {
                    tryResumeOnce(done, { Triple(null, null, null) }, cont)
                }

                override fun onFailed(
                    r: UrlRequest,
                    info: UrlResponseInfo?,
                    e: org.chromium.net.CronetException
                ) {
                    tryResumeOnce(done, { Triple(null, null, null) }, cont)
                }

                override fun onCanceled(r: UrlRequest, info: UrlResponseInfo?) {
                    tryResumeOnce(done, { Triple(null, null, null) }, cont)
                }
            }

            val req = engine.newUrlRequestBuilder(url, callback, cronetExec)
                .disableCache()
                .setPriority(UrlRequest.Builder.REQUEST_PRIORITY_HIGHEST)
                .addHeader("Range", "bytes=0-0")
                .build()
            req.start()
            cont.invokeOnCancellation { runCatching { req.cancel() } }
        }

    private suspend fun supportsRanges(engine: CronetEngine): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)

            val callback = object : UrlRequest.Callback() {
                override fun onRedirectReceived(
                    r: UrlRequest,
                    info: UrlResponseInfo,
                    newLoc: String
                ) {
                    r.followRedirect()
                }

                override fun onResponseStarted(r: UrlRequest, info: UrlResponseInfo) {
                    val code = info.httpStatusCode
                    if (code !in 200..299 && code != 206) {
                        tryResumeOnce(done, { false }, cont)
                        r.cancel(); return
                    }
                    val ranged = info.httpStatusCode == 206 ||
                            info.allHeaders.containsKey("Accept-Ranges") ||
                            info.allHeaders.containsKey("Content-Range")
                    tryResumeOnce(done, { ranged }, cont)
                    r.cancel()
                }

                override fun onReadCompleted(
                    r: UrlRequest,
                    info: UrlResponseInfo,
                    bb: ByteBuffer
                ) {
                }

                override fun onSucceeded(r: UrlRequest, info: UrlResponseInfo) {
                    tryResumeOnce(done, { false }, cont)
                }

                override fun onFailed(
                    r: UrlRequest,
                    info: UrlResponseInfo?,
                    e: org.chromium.net.CronetException
                ) {
                    tryResumeOnce(done, { false }, cont)
                }

                override fun onCanceled(r: UrlRequest, info: UrlResponseInfo?) {
                    tryResumeOnce(done, { false }, cont)
                }
            }

            val req = engine.newUrlRequestBuilder(url, callback, cronetExec)
                .disableCache()
                .setPriority(UrlRequest.Builder.REQUEST_PRIORITY_HIGHEST)
                .addHeader("Range", "bytes=0-0")
                .build()
            req.start()
            cont.invokeOnCancellation { runCatching { req.cancel() } }
        }

    private fun stateDir(): File = File(context.filesDir, "download_states").apply { mkdirs() }
    private fun stateFile(total: Long): File = File(stateDir(), "${displayName}_${total}.state")

    private data class ResumeState(
        val url: String,
        val total: Long,
        val etag: String?,
        val lastMod: String?,
        val completed: MutableSet<LongRange>
    )

    private fun mergeRanges(ranges: Collection<LongRange>): List<LongRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<LongRange>(sorted.size)
        var curStart = sorted[0].first
        var curEnd = sorted[0].last
        for (i in 1 until sorted.size) {
            val r = sorted[i]
            if (r.first <= curEnd + 1) {
                curEnd = maxOf(curEnd, r.last)
            } else {
                out += (curStart..curEnd)
                curStart = r.first
                curEnd = r.last
            }
        }
        out += (curStart..curEnd)
        return out
    }

    private fun subtractCuts(base: LongRange, cutsSorted: List<LongRange>): List<LongRange> {
        var curStart = base.first
        val curEnd = base.last
        val res = mutableListOf<LongRange>()
        for (c in cutsSorted) {
            if (c.last < curStart) continue
            if (c.first > curEnd) break
            if (c.first > curStart) res += (curStart..(c.first - 1))
            curStart = (c.last + 1)
            if (curStart > curEnd) break
        }
        if (curStart <= curEnd) res += (curStart..curEnd)
        return res
    }

    private fun splitByMax(r: LongRange, pieceMax: Long): List<LongRange> {
        val out = ArrayList<LongRange>()
        var s = r.first
        val end = r.last
        val max = pieceMax.coerceAtLeast(1)
        while (s <= end) {
            val e = minOf(s + max - 1, end)
            out += (s..e)
            s = e + 1
        }
        return out
    }

    private fun parseState(f: File): ResumeState? {
        if (!f.exists()) return null
        var sUrl: String? = null
        var sTotal: Long? = null
        var sEtag: String? = null
        var sLm: String? = null
        val done = mutableSetOf<LongRange>()
        f.forEachLine { line ->
            when {
                line.startsWith("URL ") -> sUrl = line.removePrefix("URL ").trim()
                line.startsWith("TOTAL ") -> sTotal =
                    line.removePrefix("TOTAL ").trim().toLongOrNull()

                line.startsWith("ETAG ") -> sEtag =
                    line.removePrefix("ETAG ").trim().ifBlank { null }

                line.startsWith("LM ") -> sLm = line.removePrefix("LM ").trim().ifBlank { null }
                line.startsWith("R ") -> {
                    val parts = line.removePrefix("R ").trim().split(" ")
                    if (parts.size == 2) {
                        val a = parts[0].toLongOrNull()
                        val b = parts[1].toLongOrNull()
                        if (a != null && b != null) done += (a..b)
                    }
                }
            }
        }
        val ok = (sUrl != null && sTotal != null)
        return if (ok) ResumeState(sUrl, sTotal!!, sEtag, sLm, done) else null
    }

    private fun writeHeader(f: File, url: String, total: Long, etag: String?, lm: String?) {
        f.writeText(buildString {
            appendLine("URL $url")
            appendLine("TOTAL $total")
            appendLine("ETAG ${etag ?: ""}")
            appendLine("LM ${lm ?: ""}")
        })
    }

    private val stateLock = Any()
    private fun appendCompletedRange(f: File, start: Long, end: Long) {
        synchronized(stateLock) { f.appendText("R $start $end\n") }
    }

    private suspend fun writeRangeOkHttp(
        client: OkHttpClient,
        outCh: FileChannel,
        pathTag: String,
        start: Long,
        end: Long,
        totalDownloaded: AtomicLong,
        totalSize: Long?,
        onAnyProgress: (Long, Long?) -> Unit,
        onPathProgress: (String, Long, Long) -> Unit,
        pathCounter: AtomicLong
    ) = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            .build()

        val call = client.newCall(req)
        cont.invokeOnCancellation { runCatching { call.cancel() } }

        val buf = ByteArray(128 * 1024)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var pos = start
        var pendingDelta = 0L
        var lastUi = android.os.SystemClock.elapsedRealtime()
        val uiPeriodMs = 160L

        try {
            val resp = call.execute()
            if (!resp.isSuccessful && resp.code !in 200..299 && resp.code != 206) {
                resp.close()
                cont.resumeWithException(IllegalStateException("HTTP ${resp.code}"))
                return@suspendCancellableCoroutine
            }
            resp.body.byteStream().use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n == -1) break
                    var wrote = 0
                    while (wrote < n) {
                        val w = outCh.write(ByteBuffer.wrap(buf, wrote, n - wrote), pos + wrote)
                        if (w <= 0) break
                        wrote += w
                    }
                    pos += wrote
                    if (wrote > 0) {
                        pendingDelta += wrote
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastUi >= uiPeriodMs) {
                            val delta = pendingDelta
                            pendingDelta = 0
                            lastUi = now
                            val soFar = totalDownloaded.addAndGet(delta)
                            val pathSoFar = pathCounter.addAndGet(delta)
                            main.post {
                                onAnyProgress(soFar, totalSize)
                                onPathProgress(pathTag, delta, pathSoFar)
                            }
                        }
                    }
                    if (cont.isCancelled) break
                }
            }
            if (pendingDelta > 0) {
                val delta = pendingDelta
                pendingDelta = 0
                val soFar = totalDownloaded.addAndGet(delta)
                val pathSoFar = pathCounter.addAndGet(delta)
                main.post {
                    onAnyProgress(soFar, totalSize)
                    onPathProgress(pathTag, delta, pathSoFar)
                }
            }
            cont.resume(Unit) { cause, _, _ -> }
        } catch (t: Throwable) {
            if (cont.isCancelled) return@suspendCancellableCoroutine
            cont.resumeWithException(t)
        }
    }

    private suspend fun writeRange(
        engine: CronetEngine,
        outCh: FileChannel,
        pathTag: String,
        start: Long,
        end: Long,
        totalDownloaded: AtomicLong,
        totalSize: Long?,
        onAnyProgress: (Long, Long?) -> Unit,
        onPathProgress: (String, Long, Long) -> Unit,
        pathCounter: AtomicLong
    ) = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val buf = ByteBuffer.allocateDirect(128 * 1024)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var pos = start
        var pendingDelta = 0L
        var lastUi = android.os.SystemClock.elapsedRealtime()
        val uiPeriodMs = 160L

        val cb = object : UrlRequest.Callback() {
            override fun onRedirectReceived(r: UrlRequest, info: UrlResponseInfo, newLoc: String) {
                r.followRedirect()
            }

            override fun onResponseStarted(r: UrlRequest, info: UrlResponseInfo) {
                val code = info.httpStatusCode
                if (code != 206) {
                    activeRequests.remove(r)
                    cont.resumeWithException(IllegalStateException("Ranged request HTTP $code"))
                    r.cancel()
                    return
                }
                r.read(buf)
            }

            override fun onReadCompleted(r: UrlRequest, info: UrlResponseInfo, bb: ByteBuffer) {
                bb.flip()
                try {
                    var wrote = 0
                    while (bb.hasRemaining()) {
                        val w = outCh.write(bb, pos + wrote)
                        if (w <= 0) break
                        wrote += w
                    }
                    pos += wrote
                    if (wrote > 0) {
                        pendingDelta += wrote
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastUi >= uiPeriodMs) {
                            val delta = pendingDelta
                            pendingDelta = 0
                            lastUi = now
                            val soFar = totalDownloaded.addAndGet(delta)
                            val pathSoFar = pathCounter.addAndGet(delta)
                            main.post {
                                onAnyProgress(soFar, totalSize)
                                onPathProgress(pathTag, delta, pathSoFar)
                            }
                        }
                    }
                } finally {
                    bb.clear()
                }
                r.read(bb)
            }
            override fun onSucceeded(r: UrlRequest, info: UrlResponseInfo) {
                activeRequests.remove(r)
                if (pendingDelta > 0) {
                    val delta = pendingDelta
                    pendingDelta = 0
                    val soFar = totalDownloaded.addAndGet(delta)
                    val pathSoFar = pathCounter.addAndGet(delta)
                    main.post {
                        onAnyProgress(soFar, totalSize)
                        onPathProgress(pathTag, delta, pathSoFar)
                    }
                }
                cont.resume(Unit) { cause, _, _ -> }
            }
            override fun onFailed(r: UrlRequest, i: UrlResponseInfo?, e: org.chromium.net.CronetException) {
                activeRequests.remove(r)
                cont.resumeWithException(e)
            }
            override fun onCanceled(r: UrlRequest, i: UrlResponseInfo?) {
                activeRequests.remove(r)
                cont.cancel()
            }
        }

        val req = engine.newUrlRequestBuilder(url, cb, cronetExec)
            .addHeader("Range", "bytes=$start-$end")
            .disableCache()
            .setPriority(UrlRequest.Builder.REQUEST_PRIORITY_MEDIUM)
            .build()
        activeRequests.add(req)
        req.start()
        cont.invokeOnCancellation { runCatching { req.cancel() } }
    }

    private suspend fun streamWholeToTemp(
        engine: CronetEngine,
        outCh: FileChannel,
        totalDownloaded: AtomicLong,
        onAnyProgress: (Long, Long?) -> Unit
    ) = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val buf = ByteBuffer.allocateDirect(256 * 1024)
        var pos = 0L

        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(
                p0: UrlRequest,
                p1: UrlResponseInfo?,
                p2: String?
            ) {
                p0.followRedirect()
            }

            override fun onResponseStarted(r: UrlRequest, info: UrlResponseInfo) {
                val code = info.httpStatusCode
                if (code !in 200..299) {
                    activeRequests.remove(r)
                    cont.resumeWithException(IllegalStateException("HTTP $code"))
                    r.cancel()
                    return
                }
                r.read(buf)
            }

            override fun onReadCompleted(r: UrlRequest, info: UrlResponseInfo, bb: ByteBuffer) {
                bb.flip()
                try {
                    var wrote = 0
                    while (bb.hasRemaining()) {
                        val w = outCh.write(bb, pos + wrote)
                        if (w <= 0) break
                        wrote += w
                    }
                    pos += wrote

                    if (wrote > 0) {
                        val soFar = totalDownloaded.addAndGet(wrote.toLong())
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onAnyProgress(soFar, null)
                        }
                    }
                } finally {
                    bb.clear()
                }
                r.read(bb)
            }

            override fun onSucceeded(r: UrlRequest, info: UrlResponseInfo) {
                activeRequests.remove(r)
                cont.resume(Unit) { cause, _, _ -> null?.let { it(cause) } }
            }

            override fun onFailed(
                r: UrlRequest,
                i: UrlResponseInfo?,
                e: org.chromium.net.CronetException
            ) {
                activeRequests.remove(r)
                cont.resumeWithException(e)
            }

            override fun onCanceled(r: UrlRequest, i: UrlResponseInfo?) {
                activeRequests.remove(r)
                cont.cancel()
            }
        }

        val request = engine.newUrlRequestBuilder(url, callback, cronetExec).build()
        activeRequests.add(request)
        request.start()
        cont.invokeOnCancellation { runCatching { request.cancel() } }
    }

    fun start(
        onAnyProgress: (downloaded: Long, totalOrNull: Long?) -> Unit,
        onPathProgress: (path: String, bytesDelta: Long, pathTotal: Long) -> Unit,
        onComplete: (success: Boolean, error: String?) -> Unit,
        onChecksumReady: ((calculatedSha256: String, matchesExpected: Boolean?) -> Unit)? = null
    ) = scope.async(context = Dispatchers.IO) {

        try {
            val wifiEngines: List<CronetEngine> =
                if (wifiSessions > 0 && wifiNetwork != null) {
                    (0 until wifiEngineCount.coerceAtLeast(1)).map { getEngine(context, wifiNetwork, it) }
                } else emptyList()

            val cellEngines: List<CronetEngine> =
                if (!useOkHttpForCell && cellSessions > 0 && cellNetwork != null) {
                    (0 until cellEngineCount.coerceAtLeast(1)).map { getEngine(context, cellNetwork, it) }
                } else emptyList()

            val unboundEngines: List<CronetEngine> =
                if (wifiEngines.isEmpty() && cellEngines.isEmpty()) {
                    listOf(getEngine(context, null, 0))
                } else emptyList()

            val primary = wifiEngines.firstOrNull()
                ?: cellEngines.firstOrNull()
                ?: unboundEngines.first()

            val wifiRR = if (wifiEngines.isNotEmpty()) RoundRobin(wifiEngines) else null
            val cellRR = if (cellEngines.isNotEmpty()) RoundRobin(cellEngines) else null

            var total: Long? = null
            var etag: String? = null
            var lastMod: String? = null

            withTimeoutOrNull(1500L) {
                val (lenR, eR, lmR) = probeRange0(primary)
                if (lenR != null) {
                    total = lenR; etag = eR; lastMod = lmR
                }
            }

            if (total == null) {
                withTimeoutOrNull(1200L) {
                    val (lenH, eH, lmH) = probeHead(primary)
                    if (lenH != null) {
                        total = lenH; etag = etag ?: eH; lastMod = lastMod ?: lmH
                    }
                }
            }

            val rangesOk: Boolean = total != null && withTimeoutOrNull(1000L) { supportsRanges(primary) } == true
            if (!rangesOk) {
                val tempDir = File(context.cacheDir, "dltemp").apply { mkdirs() }
                val tempFile = File(tempDir, "${displayName}_whole.part")
                RandomAccessFile(tempFile, "rw").use { raf ->
                    val outCh = raf.channel
                    val td = AtomicLong(0L)
                    streamWholeToTemp(primary, outCh, td, onAnyProgress)
                    outCh.force(false)
                }

                val md = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openOutputStream(targetUri, "w")!!.use { os ->
                    FileInputStream(tempFile).use { fis ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = fis.read(buf); if (n == -1) break
                            os.write(buf, 0, n)
                            md.update(buf, 0, n)
                        }
                        os.flush()
                    }
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                onChecksumReady?.invoke(sha, expectedSha256?.equals(sha, ignoreCase = true))
                if (expectedSha256 != null && !expectedSha256.equals(sha, ignoreCase = true)) {
                    runCatching { context.contentResolver.delete(targetUri, null, null) }
                    runCatching { tempFile.delete() }
                    withContext(Dispatchers.Main) { onComplete(false, "checksum mismatch") }
                    return@async
                }
                MediaStoreDownloads.finishPending(context, targetUri)
                runCatching { tempFile.delete() }
                withContext(Dispatchers.Main) { onComplete(true, null) }
                return@async
            }

            val realTotal = total!!

            val unit = if (autoSizeByTotal) {
                (realTotal / autoDivisor.toLong()).coerceIn(minAutoBytes, maxAutoBytes)
            } else {
                null
            }

            val wifiSliceMax = unit ?: wifiSliceMaxBytes
            val cellSliceMax = unit ?: cellSliceMaxBytes

            val tempDir = File(context.cacheDir, "dltemp").apply { mkdirs() }
            val tempFile = File(tempDir, "${displayName}_${realTotal}.part")
            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.setLength(realTotal)
            }
            val outCh = RandomAccessFile(tempFile, "rw").channel

            val stFile = stateFile(realTotal)
            val prior = parseState(stFile)
            val canResume = prior != null && prior.url == url &&
                    (etag == null || prior.etag == null || etag == prior.etag) &&
                    (lastMod == null || prior.lastMod == null || lastMod == prior.lastMod)

            val completed: MutableSet<LongRange> =
                (prior?.completed?.toMutableSet() ?: mutableSetOf()).also { if (!canResume) it.clear() }
            var completedMerged: List<LongRange> = mergeRanges(completed)
            completedMergedSnap = completedMerged
            if (!canResume) writeHeader(stFile, url, realTotal, etag, lastMod)

            val gaps = subtractCuts(0L..(realTotal - 1), completedMerged)

            val wifiQueue = ArrayDeque<Pair<Long, Long>>()
            val cellQueue = ArrayDeque<Pair<Long, Long>>()

            var turnIsWifi = true
            for (gap in gaps) {
                var s = gap.first
                while (s <= gap.last) {
                    if (turnIsWifi) {
                        val e = min(s + wifiSliceMax - 1, gap.last)
                        wifiQueue.addLast(s to e)
                        s = e + 1
                    } else {
                        val e = min(s + cellSliceMax - 1, gap.last)
                        cellQueue.addLast(s to e)
                        s = e + 1
                    }
                    turnIsWifi = !turnIsWifi
                }
            }

            val totalDownloaded = AtomicLong(
                completedMerged.sumOf { it.last - it.first + 1 }
            )
            val wifiCounter = AtomicLong(0L)
            val cellCounter = AtomicLong(0L)

            val inflight = java.util.concurrent.atomic.AtomicInteger(0)

            suspend fun worker(
                engine: CronetEngine,
                tag: String,
                counter: AtomicLong,
                sliceMax: Long
            ) {
                while (true) {
                    if (isStopping.get()) break
                    while (isPaused.get()) delay(60)

                    if (wifiQuotaBytes > 0 && tag == "wifi" && counter.get() >= wifiQuotaBytes) break
                    if (cellQuotaBytes > 0 && tag == "cell" && counter.get() >= cellQuotaBytes) break

                    val (ownQ, otherQ) = if (tag == "wifi") wifiQueue to cellQueue else cellQueue to wifiQueue
                    val slice = claimWithSteal(
                        own = ownQ,
                        other = otherQ,
                        sliceMax = sliceMax
                    )

                    if (slice == null) {
                        if (inflight.get() == 0) break
                        delay(20)
                        continue
                    }

                    inflight.incrementAndGet()
                    try {
                        writeRange(
                            engine = engine,
                            outCh = outCh,
                            pathTag = tag,
                            start = slice.first,
                            end = slice.second,
                            totalDownloaded = totalDownloaded,
                            totalSize = realTotal,
                            onAnyProgress = onAnyProgress,
                            onPathProgress = onPathProgress,
                            pathCounter = counter
                        )
                        appendCompletedRange(stFile, slice.first, slice.second)
                        completed.add(slice.first..slice.second)
                        completedMerged = mergeRanges(completed)
                        completedMergedSnap = completedMerged
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        synchronized(wifiQueue) { wifiQueue.addFirst(slice) }
                        if (isStopping.get()) break
                        if (isPaused.get()) {
                            while (isPaused.get()) delay(120)
                        } else {
                            delay(150)
                        }
                    } finally {
                        inflight.decrementAndGet()
                    }
                }
            }

            val cellOkHttpClients: List<OkHttpClient> =
                if (useOkHttpForCell && cellNetwork != null && cellSessions > 0) {
                    val count = cellEngineCount.coerceAtLeast(1)
                    (0 until count).map { buildOkHttpForNetwork(cellNetwork) }
                } else emptyList()

            val cellOkHttpRR = if (cellOkHttpClients.isNotEmpty()) RoundRobin(cellOkHttpClients) else null

            val jobs = mutableListOf<Deferred<Unit>>()

            if (wifiRR != null && wifiSessions > 0) repeat(wifiSessions) {
                jobs += scope.async(Dispatchers.IO) {
                    val engine = wifiRR.next()
                    worker(
                        engine = engine,
                        tag = "wifi",
                        counter = wifiCounter,
                        sliceMax = wifiSliceMax
                    )
                }
            }

            if (useOkHttpForCell && cellOkHttpRR != null && cellSessions > 0) {
                repeat(cellSessions) {
                    jobs += scope.async(Dispatchers.IO) {
                        while (true) {
                            if (isStopping.get()) break
                            while (isPaused.get()) delay(60)

                            if (cellQuotaBytes > 0 && cellCounter.get() >= cellQuotaBytes) break

                            val slice = claimWithSteal(
                                own = cellQueue,
                                other = wifiQueue,
                                sliceMax = cellSliceMax
                            )

                            if (slice == null) {
                                if (inflight.get() == 0) break
                                delay(20)
                                continue
                            }

                            val client = cellOkHttpRR.next()
                            inflight.incrementAndGet()
                            try {
                                writeRangeOkHttp(
                                    client = client,
                                    outCh = outCh,
                                    pathTag = "cell",
                                    start = slice.first,
                                    end = slice.second,
                                    totalDownloaded = totalDownloaded,
                                    totalSize = realTotal,
                                    onAnyProgress = onAnyProgress,
                                    onPathProgress = onPathProgress,
                                    pathCounter = cellCounter
                                )
                                appendCompletedRange(stFile, slice.first, slice.second)
                                completed.add(slice.first..slice.second)
                                completedMerged = mergeRanges(completed)
                                completedMergedSnap = completedMerged
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                synchronized(cellQueue) { cellQueue.addFirst(slice) }
                                if (isStopping.get()) break
                                if (isPaused.get()) {
                                    while (isPaused.get()) delay(120)
                                } else {
                                    delay(150)
                                }
                            } finally {
                                inflight.decrementAndGet()
                            }
                        }
                    }
                }
            } else if (cellRR != null && cellSessions > 0) {
                repeat(cellSessions) {
                    jobs += scope.async(Dispatchers.IO) {
                        val engine = cellRR.next()
                        worker(
                            engine = engine,
                            tag = "cell",
                            counter = cellCounter,
                            sliceMax = cellSliceMax
                        )
                    }
                }
            }

            if (jobs.isEmpty()) {
                val unbound = unboundEngines.first()
                jobs += scope.async(Dispatchers.IO) { worker(unbound, "unbound", wifiCounter, sliceMax = wifiSliceMax) }
            }
            jobs.awaitAll()

            outCh.force(false)
            outCh.close()

            val md = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openOutputStream(targetUri, "w")!!.use { os ->
                FileInputStream(tempFile).use { fis ->
                    val buf = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = fis.read(buf); if (n == -1) break
                        os.write(buf, 0, n)
                        md.update(buf, 0, n)
                        copied += n
                    }
                    os.flush()
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            onChecksumReady?.invoke(sha, expectedSha256?.equals(sha, ignoreCase = true))
            if (expectedSha256 != null && !expectedSha256.equals(sha, ignoreCase = true)) {
                runCatching { context.contentResolver.delete(targetUri, null, null) }
                runCatching { tempFile.delete() }
                withContext(Dispatchers.Main) { onComplete(false, "checksum mismatch") }
                return@async
            }

            withContext(Dispatchers.Main) { onAnyProgress(realTotal, realTotal) }

            MediaStoreDownloads.finishPending(context, targetUri)
            runCatching { tempFile.delete() }
            runCatching { stateFile(realTotal).delete() }
            withContext(Dispatchers.Main) { onComplete(true, null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) { onComplete(false, e.localizedMessage ?: e.toString()) }
        }
    }
}