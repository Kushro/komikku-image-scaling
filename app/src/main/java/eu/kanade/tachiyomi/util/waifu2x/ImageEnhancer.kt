package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.graphics.BitmapFactory
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.data.coil.chapterId
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.data.coil.enhanced
import eu.kanade.tachiyomi.data.coil.mangaId
import eu.kanade.tachiyomi.data.coil.pageIndex
import eu.kanade.tachiyomi.data.coil.pageVariant
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.GLUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

data class EnhancerState(
    val queueSize: Int = 0,
    val activePage: Int = -1,
    val sessionCompleted: Int = 0,
    val sessionFailed: Int = 0,
    val lastError: String? = null,
)

enum class PageUpscaleStatus { QUEUED, PROCESSING, DONE, FAILED }

data class PageUpscaleRecord(
    val pageIndex: Int,
    val pageVariant: String = "",
    val status: PageUpscaleStatus,
    val enqueuedMs: Long = 0L,
    val startMs: Long = 0L,
    val durationMs: Long = 0L,
    val model: String = "",
    val batchId: Int = 0,
    val batchSize: Int = 1,
    val error: String? = null,
)

object ImageEnhancer {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pendingRequests = ConcurrentHashMap<String, Unit>()
    private val preferences by lazy { Injekt.get<ReaderPreferences>() }
    private val getManga by lazy { Injekt.get<GetManga>() }
    private val sourceManager by lazy { Injekt.get<SourceManager>() }

    private val _enhancerState = MutableStateFlow(EnhancerState())
    val enhancerState: StateFlow<EnhancerState> = _enhancerState.asStateFlow()

    private val pageRecordMap = ConcurrentHashMap<String, PageUpscaleRecord>()
    private val _pageRecords = MutableStateFlow<List<PageUpscaleRecord>>(emptyList())
    val pageRecords: StateFlow<List<PageUpscaleRecord>> = _pageRecords.asStateFlow()

    private val batchIdCounter = AtomicInteger(0)

    private val sessionCompleted = AtomicInteger(0)
    private val sessionFailed = AtomicInteger(0)

    private fun setPageRecord(key: String, record: PageUpscaleRecord) {
        pageRecordMap[key] = record
        _pageRecords.value = pageRecordMap.values.sortedWith(compareBy({ it.pageIndex }, { it.pageVariant }))
    }

    // Priority Queue order:
    // 1. Current visible primary page
    // 2. Current visible secondary page in double-page mode
    // 3. Other promoted/high-priority requests
    // 4. Normal preload requests
    // Then Distance from Target ASC, Seq ASC
    private val queue = PriorityBlockingQueue<EnhanceRequest>()
    private val seqGenerator = AtomicInteger(0)

    @Volatile
    private var lastResetTime = 0L

    @Volatile
    private var isFirstRequestAfterReset = false

    @Volatile
    private var initialTargetEnqueued = false

    // Current page the user is viewing. Used to prioritize requests closest to this page.
    @Volatile
    var targetPageIndex: Int = 0

    @Volatile
    private var targetPageVariant: String = ""

    @Volatile
    private var targetSecondaryPageIndex: Int = -1

    @Volatile
    private var targetSecondaryPageVariant: String = ""

    data class EnhanceRequest(
        val context: Context,
        val mangaId: Long,
        val chapterId: Long,
        val pageIndex: Int,
        val pageVariant: String,
        val data: Any,
        val priority: Int, // 1 = promoted/high priority, 0 = preload
        val seq: Int = 0,
        val fallbackStream: (() -> InputStream)? = null,
    ) : Comparable<EnhanceRequest> {
        private fun effectivePriority(): Int {
            return when {
                pageIndex == targetPageIndex && pageVariant == targetPageVariant -> 3
                pageIndex == targetSecondaryPageIndex && pageVariant == targetSecondaryPageVariant -> 2
                priority > 0 -> 1
                else -> 0
            }
        }

        override fun compareTo(other: EnhanceRequest): Int {
            // 1. Effective priority based on current visible spread and promotion state.
            val p = other.effectivePriority().compareTo(effectivePriority()) // Descending
            if (p != 0) return p

            // 2. Distance from Target Page (Closer > Farther)
            // Even if multiple pages are "High Priority", the one closest to user focus wins.
            val currentTarget = targetPageIndex
            val dist1 = kotlin.math.abs(pageIndex - currentTarget)
            val dist2 = kotlin.math.abs(other.pageIndex - currentTarget)

            val d = dist1.compareTo(dist2) // Ascending (0 distance is best)
            if (d != 0) return d

            // 3. Fallback: FIFO (Older seq first)
            return seq.compareTo(other.seq)
        }
    }

    init {
        // Worker Loop
        scope.launch {
            while (true) {
                try {
                    if (isFirstRequestAfterReset) {
                        val elapsed = System.currentTimeMillis() - lastResetTime
                        if (elapsed < 700) {
                            kotlinx.coroutines.delay(700 - elapsed)
                        }
                        isFirstRequestAfterReset = false
                    }

                    val req = runInterruptible { queue.take() }
                    // Remote batch/URL strategies are driven here: instead of routing each page
                    // through Coil/the decoder one at a time, gather a window of queued pages and
                    // upscale them in a single server request, writing each result to the cache.
                    val strategy = remoteStrategyOrNull()
                    if (strategy != null && strategy != RemoteUpscaleStrategy.IMAGE) {
                        val batch = mutableListOf(req)
                        if (strategy == RemoteUpscaleStrategy.BATCH_IMAGE || strategy == RemoteUpscaleStrategy.BATCH_URL) {
                            // KMK --> group up to 4 queued requests into one server call so a single
                            // batch can't block the queue for too long
                            val window = 4
                            // KMK <--
                            if (window > 1) queue.drainTo(batch, window - 1)
                        }
                        processBatchRequests(batch)
                    } else {
                        processRequest(req)
                    }
                } catch (e: Exception) {
                    if (e !is InterruptedException) {
                        logcat(LogPriority.ERROR, e) { "ImageEnhancer: Worker loop error" }
                    }
                }
            }
        }
    }

    fun enhance(context: Context, page: ReaderPage, highPriority: Boolean = false) {
        val mangaId = page.chapter.chapter.manga_id ?: -1L
        val chapterId = page.chapter.chapter.id ?: -1L

        if (mangaId == -1L || chapterId == -1L) return

        // For URL strategies, send the source URL so the server downloads it directly. Falls
        // back to the image bytes below when there's no usable http URL (local/downloaded
        // chapters, or localhost placeholders) — the batch worker then routes it as image data.
        val strategy = remoteStrategyOrNull()
        if (strategy == RemoteUpscaleStrategy.URL || strategy == RemoteUpscaleStrategy.BATCH_URL) {
            val usableUrl = page.imageUrl?.takeIf { it.isUsableRemoteUrl() }
            if (usableUrl != null) {
                val fallbackStream = page.enhancementStream ?: page.stream
                enhance(context, mangaId, chapterId, page.index, usableUrl, highPriority, page.enhancementKeySuffix, fallbackStream)
                return
            }
        }

        // Prioritize stream over imageUrl. For online manga, imageUrl can be a placeholder
        // (e.g., https://127.0.0.1/...) while the actual image data is in the stream.
        val data: Any = page.enhancementStream?.let { streamFn ->
            try {
                Buffer().readFrom(streamFn())
            } catch (e: Exception) {
                null
            }
        } ?: page.stream?.let { streamFn ->
            try {
                Buffer().readFrom(streamFn())
            } catch (e: Exception) {
                null
            }
        } ?: page.imageUrl?.takeIf { it.isUsableRemoteUrl() } ?: return

        enhance(context, mangaId, chapterId, page.index, data, highPriority, page.enhancementKeySuffix)
    }

    fun enhance(context: Context, mangaId: Long, chapterId: Long, pageIndex: Int, data: Any, highPriority: Boolean, pageVariant: String = "", fallbackStream: (() -> InputStream)? = null) {
        val isInitialTargetRequest = !initialTargetEnqueued && pageIndex == targetPageIndex
        if (!highPriority && !initialTargetEnqueued && !isInitialTargetRequest) {
            logcat(LogPriority.DEBUG) {
                "ImageEnhancer: Deferring page $pageIndex/$pageVariant until initial target $targetPageIndex starts"
            }
            return
        }

        val effectiveHighPriority = highPriority || isInitialTargetRequest
        val requestKey = "${mangaId}_${chapterId}_${pageIndex}_$pageVariant"

        if (pendingRequests.containsKey(requestKey)) {
            if (effectiveHighPriority) {
                // Upgrade priority: Remove existing (likely Low) and re-add as High
                val removed = queue.removeIf {
                    it.mangaId == mangaId && it.chapterId == chapterId && it.pageIndex == pageIndex && it.pageVariant == pageVariant
                }
                if (removed) {
                    logcat(LogPriority.DEBUG) { "ImageEnhancer: Upgrading page $pageIndex/$pageVariant to High Priority" }
                    pendingRequests.remove(requestKey)
                    // Proceed to add below
                } else {
                    // Already processing or failed to remove, skip
                    return
                }
            } else {
                // Already pending and we are Low priority, so skip
                return
            }
        }

        if (pendingRequests.putIfAbsent(requestKey, Unit) != null) return

        if (!pageRecordMap.containsKey(requestKey)) {
            setPageRecord(
                requestKey,
                PageUpscaleRecord(
                    pageIndex = pageIndex,
                    pageVariant = pageVariant,
                    status = PageUpscaleStatus.QUEUED,
                    enqueuedMs = System.currentTimeMillis(),
                    model = if (preferences.enhancementMode().get() == EnhancementMode.REMOTE) {
                        "Remote"
                    } else {
                        UpscaleModels.displayName(preferences.realCuganModel().get())
                    },
                ),
            )
        }

        if (isInitialTargetRequest) {
            initialTargetEnqueued = true
        }

        val priorityLevel = if (effectiveHighPriority) 1 else 0
        val req = EnhanceRequest(context, mangaId, chapterId, pageIndex, pageVariant, data, priorityLevel, seqGenerator.getAndIncrement(), fallbackStream)
        queue.offer(req)

        logcat(LogPriority.DEBUG) { "ImageEnhancer: Enqueued page $pageIndex/$pageVariant (priority=$priorityLevel)" }
    }

    fun reset(initialPageIndex: Int = 0) {
        queue.clear()
        pendingRequests.clear()
        targetPageIndex = initialPageIndex
        targetPageVariant = ""
        targetSecondaryPageIndex = -1
        targetSecondaryPageVariant = ""
        seqGenerator.set(0)
        lastResetTime = System.currentTimeMillis()
        isFirstRequestAfterReset = true
        initialTargetEnqueued = false
        sessionCompleted.set(0)
        sessionFailed.set(0)
        batchIdCounter.set(0)
        _enhancerState.value = EnhancerState()
        pageRecordMap.clear()
        _pageRecords.value = emptyList()
        // KMK -->
        UpscaleStats.resetSession()
        // KMK <--
        logcat(LogPriority.DEBUG) { "ImageEnhancer: Resetting state to page $initialPageIndex" }
    }

    fun reprioritizeAround(
        pageIndex: Int,
        pageVariant: String = "",
        secondaryPageIndex: Int? = null,
        secondaryPageVariant: String = "",
    ) {
        targetPageIndex = pageIndex
        targetPageVariant = pageVariant
        targetSecondaryPageIndex = secondaryPageIndex ?: -1
        targetSecondaryPageVariant = if (secondaryPageIndex != null) secondaryPageVariant else ""
        val snapshot = mutableListOf<EnhanceRequest>()
        queue.drainTo(snapshot)
        if (snapshot.isNotEmpty()) {
            queue.addAll(snapshot)
            logcat(LogPriority.DEBUG) {
                "ImageEnhancer: Reprioritized ${snapshot.size} queued pages around target=$pageIndex/$pageVariant secondary=$targetSecondaryPageIndex/$targetSecondaryPageVariant"
            }
        }
    }

    fun hasRequest(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = ""): Boolean {
        return pendingRequests.containsKey("${mangaId}_${chapterId}_${pageIndex}_$pageVariant")
    }

    fun isFocusedTarget(pageIndex: Int, pageVariant: String = ""): Boolean {
        return (pageIndex == targetPageIndex && pageVariant == targetPageVariant) ||
            (pageIndex == targetSecondaryPageIndex && pageVariant == targetSecondaryPageVariant)
    }

    // KMK --> Holder/queue deduplication
    /**
     * Wait for the prefetch queue to produce the cached result of a page it owns, so page
     * holders don't fire a duplicate server request for work the batch worker is already
     * doing (each bound holder used to upscale its page individually *in addition to* the
     * queue's batch — doubling server GPU load for every preloaded page).
     *
     * Returns the cached file once the queue's worker writes it, or null when the caller
     * should upscale the page itself:
     * - the page is (or becomes) the focused target — visible pages keep the fast
     *   individual path instead of waiting out a whole batch round-trip;
     * - the queue never claimed the page within [graceMs] (e.g. non-library manga, or the
     *   request was pruned);
     * - the queue finished/dropped the request without producing a cache entry;
     * - [timeoutMs] elapsed.
     */
    suspend fun awaitQueuedResult(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        configHash: String,
        pageVariant: String = "",
        graceMs: Long = 1_500,
        timeoutMs: Long = 45_000,
    ): File? {
        // The queue never accepts pages without DB ids (see enhance()).
        if (mangaId == -1L || chapterId == -1L) return null

        fun cached(): File? =
            ImageEnhancementCache.getCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant)

        if (isFocusedTarget(pageIndex, pageVariant)) return cached()

        // Grace phase: a holder can bind before the ViewModel's prefetch enqueues the
        // page — give the queue a moment to claim it before concluding nobody will.
        val graceDeadline = System.currentTimeMillis() + graceMs
        while (!hasRequest(mangaId, chapterId, pageIndex, pageVariant)) {
            cached()?.let { return it }
            if (isFocusedTarget(pageIndex, pageVariant)) return null
            if (System.currentTimeMillis() >= graceDeadline) return null
            delay(250)
        }

        // Wait phase: the queue owns the page — poll for its cache write.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            cached()?.let { return it }
            // The user scrolled onto this page mid-batch: bail out so the caller gives it
            // the fast individual treatment instead of keeping the original on screen.
            if (isFocusedTarget(pageIndex, pageVariant)) return null
            if (!hasRequest(mangaId, chapterId, pageIndex, pageVariant)) {
                // Finished or pruned. The cache write happens before the pending flag is
                // cleared, so one last look settles whether it succeeded.
                return cached()
            }
            delay(500)
        }
        return null
    }
    // KMK <--

    private suspend fun processRequest(req: EnhanceRequest) {
        val key = "${req.mangaId}_${req.chapterId}_${req.pageIndex}_${req.pageVariant}"
        val startMs = System.currentTimeMillis()
        try {
            _enhancerState.value = _enhancerState.value.copy(activePage = req.pageIndex, queueSize = queue.size)
            pageRecordMap[key]?.let { setPageRecord(key, it.copy(status = PageUpscaleStatus.PROCESSING, startMs = startMs)) }
            logcat(LogPriority.DEBUG) { "ImageEnhancer: Processing page ${req.pageIndex}/${req.pageVariant} (priority=${req.priority})" }
            val request = ImageRequest.Builder(req.context)
                .data(req.data)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .customDecoder(true)
                .enhanced(true)
                .mangaId(req.mangaId)
                .chapterId(req.chapterId)
                .pageIndex(req.pageIndex)
                .pageVariant(req.pageVariant)
                .build()

            SingletonImageLoader.get(req.context).enqueue(request).job.await()
            val durationMs = System.currentTimeMillis() - startMs
            sessionCompleted.incrementAndGet()
            pageRecordMap[key]?.let { setPageRecord(key, it.copy(status = PageUpscaleStatus.DONE, durationMs = durationMs)) }
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startMs
            sessionFailed.incrementAndGet()
            _enhancerState.value = _enhancerState.value.copy(lastError = e.message)
            pageRecordMap[key]?.let { setPageRecord(key, it.copy(status = PageUpscaleStatus.FAILED, durationMs = durationMs, error = e.message)) }
            throw e
        } finally {
            pendingRequests.remove(key)
            _enhancerState.value = _enhancerState.value.copy(
                activePage = -1,
                queueSize = queue.size,
                sessionCompleted = sessionCompleted.get(),
                sessionFailed = sessionFailed.get(),
            )
        }
    }

    // KMK --> Remote batch/URL strategies (only used when enhancementMode == REMOTE)
    /** The active remote upscale strategy, or null when remote mode isn't selected. */
    private fun remoteStrategyOrNull(): Int? =
        if (preferences.enhancementMode().get() == EnhancementMode.REMOTE) preferences.remoteUpscaleStrategy().get() else null

    private suspend fun getSourceHeaders(mangaId: Long): Map<String, String> {
        val manga = getManga.await(mangaId) ?: return emptyMap()
        val source = sourceManager.get(manga.source) as? HttpSource ?: return emptyMap()
        return buildMap { for (i in 0 until source.headers.size) put(source.headers.name(i), source.headers.value(i)) }
    }

    /**
     * Upscale a window of queued pages in a single server request and write each result to the
     * disk cache (which the page holders and decoder read on their fast path). URL-typed requests
     * go to /upscale/batch/url; byte-typed ones (image strategy, or URL fallback) to /upscale/batch.
     */
    private suspend fun processBatchRequests(batch: List<EnhanceRequest>) {
        val context = batch.first().context
        val host = preferences.remoteUpscalerHost().get()
        val port = preferences.remoteUpscalerPort().get()
        ImageEnhancementCache.init(context)
        val configHash = ImageEnhancementCache.getRemoteConfigHash(host, port)

        // Track the batch as "actively processing" so UI status checks reflect it.
        val first = batch.first()
        _enhancerState.value = _enhancerState.value.copy(activePage = first.pageIndex, queueSize = queue.size)
        val batchId = batchIdCounter.incrementAndGet()
        val batchStartMs = System.currentTimeMillis()
        val completedBatchKeys = mutableSetOf<String>()
        batch.forEach { req ->
            val k = "${req.mangaId}_${req.chapterId}_${req.pageIndex}_${req.pageVariant}"
            pageRecordMap[k]?.let { setPageRecord(k, it.copy(status = PageUpscaleStatus.PROCESSING, startMs = batchStartMs, batchId = batchId, batchSize = batch.size)) }
        }
        try {
            logcat(LogPriority.DEBUG) { "ImageEnhancer: Batch-processing ${batch.size} page(s) via remote strategy" }

            // KMK -->
            val batchStart = System.currentTimeMillis()
            var batchSuccessCount = 0
            var batchBytesOut = 0L
            // KMK <--

            val sourceHeaders = getSourceHeaders(first.mangaId)

            val urlReqs = batch.filter { it.data is String }
            val byteReqs = batch.filter { it.data is Buffer }

            if (urlReqs.isNotEmpty()) {
                val results = RemoteUpscaler.processBatchUrl(urlReqs.map { it.data as String }, host, port, sourceHeaders)
                val fallbackByteReqs = mutableListOf<Pair<EnhanceRequest, ByteArray>>()
                urlReqs.forEachIndexed { i, req ->
                    val bitmap = results.getOrNull(i)
                    if (bitmap != null) {
                        // KMK -->
                        val bytes = saveBatchResult(req, configHash, bitmap)
                        if (bytes > 0L) {
                            batchSuccessCount++
                            batchBytesOut += bytes
                            val k = "${req.mangaId}_${req.chapterId}_${req.pageIndex}_${req.pageVariant}"
                            completedBatchKeys.add(k)
                            pageRecordMap[k]?.let { setPageRecord(k, it.copy(status = PageUpscaleStatus.DONE, durationMs = System.currentTimeMillis() - batchStartMs)) }
                        }
                        // KMK <--
                    } else if (req.fallbackStream != null) {
                        try {
                            val bytes = Buffer().readFrom(req.fallbackStream.invoke()).readByteArray()
                            fallbackByteReqs.add(req to bytes)
                        } catch (e: Exception) {
                            logcat(LogPriority.WARN) { "ImageEnhancer: page ${req.pageIndex}: fallback stream expired or unreadable, skipping" }
                        }
                    }
                }
                if (fallbackByteReqs.isNotEmpty()) {
                    logcat(LogPriority.WARN) { "ImageEnhancer: ${fallbackByteReqs.size} URL item(s) failed server-side download, retrying with bytes" }
                    val fallbackResults = RemoteUpscaler.processBatch(fallbackByteReqs.map { it.second }, host, port)
                    fallbackByteReqs.forEachIndexed { i, (req, _) ->
                        // KMK -->
                        fallbackResults.getOrNull(i)?.let {
                            val bytes = saveBatchResult(req, configHash, it)
                            if (bytes > 0L) {
                                batchSuccessCount++
                                batchBytesOut += bytes
                                val k = "${req.mangaId}_${req.chapterId}_${req.pageIndex}_${req.pageVariant}"
                                completedBatchKeys.add(k)
                                pageRecordMap[k]?.let { r -> setPageRecord(k, r.copy(status = PageUpscaleStatus.DONE, durationMs = System.currentTimeMillis() - batchStartMs)) }
                            }
                        }
                        // KMK <--
                    }
                }
            }
            if (byteReqs.isNotEmpty()) {
                val images = byteReqs.map { (it.data as Buffer).readByteArray() }
                val results = RemoteUpscaler.processBatch(images, host, port)
                byteReqs.forEachIndexed { i, req ->
                    // KMK -->
                    results.getOrNull(i)?.let {
                        val bytes = saveBatchResult(req, configHash, it)
                        if (bytes > 0L) {
                            batchSuccessCount++
                            batchBytesOut += bytes
                            val k = "${req.mangaId}_${req.chapterId}_${req.pageIndex}_${req.pageVariant}"
                            completedBatchKeys.add(k)
                            pageRecordMap[k]?.let { r -> setPageRecord(k, r.copy(status = PageUpscaleStatus.DONE, durationMs = System.currentTimeMillis() - batchStartMs)) }
                        }
                    }
                    // KMK <--
                }
            }
            // KMK -->
            UpscaleStats.recordBatch(UpscaleStats.MODE_REMOTE, batchSuccessCount, System.currentTimeMillis() - batchStart, batchBytesOut)
            // KMK <--
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "ImageEnhancer: Batch processing failed" }
            sessionFailed.addAndGet(batch.size)
            _enhancerState.value = _enhancerState.value.copy(lastError = e.message, sessionFailed = sessionFailed.get())
            batch.forEach { req ->
                val k = "${req.mangaId}_${req.chapterId}_${req.pageIndex}_${req.pageVariant}"
                if (k !in completedBatchKeys) {
                    pageRecordMap[k]?.let { r ->
                        if (r.status == PageUpscaleStatus.PROCESSING) {
                            setPageRecord(k, r.copy(status = PageUpscaleStatus.FAILED, durationMs = System.currentTimeMillis() - batchStartMs, error = e.message))
                        }
                    }
                }
            }
        } finally {
            batch.forEach { req ->
                val k = "${req.mangaId}_${req.chapterId}_${req.pageIndex}_${req.pageVariant}"
                pendingRequests.remove(k)
                if (k !in completedBatchKeys) {
                    pageRecordMap[k]?.let { r ->
                        if (r.status == PageUpscaleStatus.PROCESSING) {
                            setPageRecord(k, r.copy(status = PageUpscaleStatus.FAILED, durationMs = System.currentTimeMillis() - batchStartMs))
                        }
                    }
                }
            }
            _enhancerState.value = _enhancerState.value.copy(
                activePage = -1,
                queueSize = queue.size,
                sessionCompleted = sessionCompleted.get(),
                sessionFailed = sessionFailed.get(),
            )
        }
    }

    /**
     * Save raw batch result bytes to cache. Writes bytes directly (no re-encoding) unless the
     * image exceeds the display limits, in which case it falls back to decode + clamp + save.
     * Returns the byte count written on success, or 0 on failure.
     */
    private fun saveBatchResult(req: EnhanceRequest, configHash: String, bytes: ByteArray): Long {
        // KMK -->
        val limit = minOf(GLUtil.DEVICE_TEXTURE_LIMIT, ImageEnhancementCache.MAX_WEBP_DIMENSION)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth > limit || opts.outHeight > limit) {
            // Rare path: decode → clamp → save as WebP (server already clamps, so this is a safety net)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return 0L
            val out = ImageEnhancementCache.clampToDisplayLimits(decoded)
            return try {
                val bytesOut = out.byteCount.toLong()
                val saved = ImageEnhancementCache.saveToCache(req.mangaId, req.chapterId, req.pageIndex, configHash, out, req.pageVariant)
                if (saved != null) bytesOut else 0L
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "ImageEnhancer: Failed to cache (scaled) batch result for page ${req.pageIndex}" }
                0L
            } finally {
                out.recycle()
            }
        }
        // Fast path: write server bytes directly — no decode/re-encode, no quality loss
        return try {
            val saved = ImageEnhancementCache.saveBytesToCache(req.mangaId, req.chapterId, req.pageIndex, configHash, bytes, req.pageVariant)
            if (saved != null) bytes.size.toLong() else 0L
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "ImageEnhancer: Failed to cache batch result for page ${req.pageIndex}" }
            0L
        }
        // KMK <--
    }
    // KMK <--
}
