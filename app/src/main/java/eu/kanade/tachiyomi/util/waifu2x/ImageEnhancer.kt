package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import eu.kanade.tachiyomi.data.coil.chapterId
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.data.coil.enhanced
import eu.kanade.tachiyomi.data.coil.mangaId
import eu.kanade.tachiyomi.data.coil.pageIndex
import eu.kanade.tachiyomi.data.coil.pageVariant
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object ImageEnhancer {
    // KMK -->
    // Distinguishes what the actively processed page is doing so the reader can label
    // model preparation, native inference and cache saving separately (upstream 1.3.17).
    enum class ProcessingPhase { PREPARING, ENHANCING, SAVING }

    @Volatile
    private var activePhase = ProcessingPhase.PREPARING
    // KMK <--

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Values identify individual attempts: cancelling and retrying a page can happen
    // within the same generation while the previous attempt is still unwinding.
    private val pendingRequests = ConcurrentHashMap<String, EnhanceRequest>()

    // Priority Queue order:
    // 1. Current visible primary page
    // 2. Current visible secondary page in double-page mode
    // 3. Other promoted/high-priority requests
    // 4. Normal preload requests
    // Then Distance from Target ASC, Seq ASC
    private val queue = PriorityBlockingQueue<EnhanceRequest>()
    private val queueSignal = Channel<Unit>(Channel.CONFLATED)
    private val seqGenerator = AtomicInteger(0)
    private val generation = AtomicInteger(0)

    // KMK -->
    private val runtimeInitialized = AtomicBoolean(false)
    private val preferences: ReaderPreferences by lazy { Injekt.get() }
    // KMK <--

    @Volatile
    private var lastResetTime = 0L

    @Volatile
    private var isFirstRequestAfterReset = false

    @Volatile
    private var initialTargetEnqueued = false

    // KMK -->
    // Model initialization/inference must not compete with the reader's first
    // original-image decode or replace it before its first frame is visible.
    private var waitingForVisibleImage = false
    // KMK <--

    @Volatile
    private var activeMangaId = -1L

    @Volatile
    private var activeChapterId = -1L

    @Volatile
    private var activePageIndex = -1

    @Volatile
    private var activePageVariant = ""

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var activeRequest: EnhanceRequest? = null

    @Volatile
    private var nativeResetJob: Job? = null

    // Current page the user is viewing. Used to prioritize requests closest to this page.
    @Volatile
    var targetPageIndex: Int = 0
        private set

    @Volatile
    private var targetPageVariant: String = ""

    @Volatile
    private var targetSecondaryPageIndex: Int = -1

    @Volatile
    private var targetSecondaryPageVariant: String = ""

    class EnhanceRequest(
        val context: Context,
        val mangaId: Long,
        val chapterId: Long,
        val pageIndex: Int,
        val pageVariant: String,
        val dataProvider: () -> Any?,
        val priority: Int, // 1 = promoted/high priority, 0 = preload
        val generation: Int,
        val seq: Int = 0,
    ) : Comparable<EnhanceRequest> {
        val cancelled = AtomicBoolean(false)
        val requeueOnCancel = AtomicBoolean(false)

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

                    // Poll under the same monitor as reprioritization/cancellation.
                    // Waiting for work must not hold that monitor or an IO thread.
                    // KMK -->
                    // Hold work until the first original frame of the target page is on screen.
                    val req = synchronized(this@ImageEnhancer) {
                        if (waitingForVisibleImage) null else queue.poll()
                    }
                    // KMK <--
                    if (req == null) {
                        queueSignal.receive()
                        continue
                    }
                    if (req.generation == generation.get()) {
                        processRequest(req)
                    } else {
                        pendingRequests.remove(req.key, req)
                    }
                } catch (e: Exception) {
                    if (e !is InterruptedException && e !is CancellationException) {
                        logcat(LogPriority.ERROR, e) { "ImageEnhancer: Worker loop error" }
                    }
                } catch (e: OutOfMemoryError) {
                    // KMK --> An optional enhancement must not kill the process or
                    // permanently stop the queue; drop the failed attempt and continue.
                    logcat(LogPriority.ERROR, e) { "ImageEnhancer: Not enough memory for enhancement" }
                    // KMK <--
                }
            }
        }
    }

    fun enhance(context: Context, page: ReaderPage, highPriority: Boolean = false) {
        val mangaId = page.chapter.chapter.manga_id ?: -1L
        val chapterId = page.chapter.chapter.id ?: -1L

        if (mangaId == -1L || chapterId == -1L) return

        enhanceLazy(
            context = context,
            mangaId = mangaId,
            chapterId = chapterId,
            pageIndex = page.index,
            highPriority = highPriority,
            pageVariant = page.enhancementKeySuffix,
        ) {
            // Streams are opened only after queue de-duplication and on the worker dispatcher.
            page.enhancementStream?.let(::bufferStream)
                ?: page.stream?.let(::bufferStream)
                ?: page.imageUrl
        }
    }

    fun enhance(context: Context, mangaId: Long, chapterId: Long, pageIndex: Int, data: Any, highPriority: Boolean, pageVariant: String = "") {
        enhanceLazy(context, mangaId, chapterId, pageIndex, highPriority, pageVariant) { data }
    }

    fun enhanceLazy(
        context: Context,
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        highPriority: Boolean,
        pageVariant: String = "",
        dataProvider: () -> Any?,
    ) {
        // KMK -->
        // Capture the generation before runtime init/cache I/O so a reset racing the
        // enhanced-cache lookup cannot let a stale request slip into the fresh queue.
        val requestGeneration = generation.get()
        initializeRuntime(context.applicationContext)
        val configHash = currentConfigHash()
        if (
            ImageEnhancementCache.isCached(mangaId, chapterId, pageIndex, configHash, pageVariant) ||
            ImageEnhancementCache.isSkipped(mangaId, chapterId, pageIndex, configHash, pageVariant)
        ) {
            return
        }
        // KMK <--
        if (ImageEnhancementCache.isSavePending(mangaId, chapterId, pageIndex, pageVariant, configHash)) return

        enqueueRequest(context, mangaId, chapterId, pageIndex, highPriority, pageVariant, requestGeneration, dataProvider)
    }

    // KMK -->
    // Only in-memory queue state is protected here. Cache I/O must never hold
    // the monitor used by the UI for page selection and progress queries.
    @Synchronized
    private fun enqueueRequest(
        context: Context,
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        highPriority: Boolean,
        pageVariant: String,
        requestGeneration: Int,
        dataProvider: () -> Any?,
    ) {
        if (requestGeneration != generation.get()) return

        val isInitialTargetRequest = !initialTargetEnqueued && pageIndex == targetPageIndex
        val effectiveHighPriority = highPriority || isInitialTargetRequest
        val requestKey = requestKey(mangaId, chapterId, pageIndex, pageVariant)

        val existingRequest = pendingRequests[requestKey]
        if (existingRequest != null) {
            if (effectiveHighPriority) {
                // KMK --> the page is already promoted, but the worker may still be busy with
                // another page; re-asserting the visible request is what preempts it. Without
                // this call the promotion silently never interrupts an active non-focused page.
                if (existingRequest.priority == 1) {
                    preemptForVisibleRequest(pageIndex, pageVariant)
                    return
                }
                // KMK <--
                // Upgrade priority: Remove existing (likely Low) and re-add as High
                val removed = queue.remove(existingRequest)
                if (removed) {
                    logcat(LogPriority.DEBUG) {
                        "ImageEnhancer: Upgrading page $pageIndex/$pageVariant to High Priority"
                    }
                    pendingRequests.remove(requestKey, existingRequest)
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

        if (isInitialTargetRequest) {
            initialTargetEnqueued = true
        }

        val priorityLevel = if (effectiveHighPriority) 1 else 0
        val req = EnhanceRequest(
            context.applicationContext,
            mangaId,
            chapterId,
            pageIndex,
            pageVariant,
            dataProvider,
            priorityLevel,
            requestGeneration,
            seqGenerator.getAndIncrement(),
        )
        pendingRequests[requestKey] = req
        queue.offer(req)
        queueSignal.trySend(Unit)

        // A visible page may interrupt background work, but another preloaded page should not be
        // discarded just because the reader advanced within the preload window.
        if (effectiveHighPriority) {
            preemptForVisibleRequest(pageIndex, pageVariant)
        }

        logcat(LogPriority.DEBUG) { "ImageEnhancer: Enqueued page $pageIndex/$pageVariant (priority=$priorityLevel)" }
    }

    @Synchronized
    fun reset(initialPageIndex: Int = 0) {
        cancelAll(reason = "reset")
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
        waitingForVisibleImage = true
        logcat(LogPriority.DEBUG) { "ImageEnhancer: Resetting state to page $initialPageIndex" }
    }

    // KMK -->
    // Called once the reader shows the first original frame of the focused page;
    // releases the queue held by [waitingForVisibleImage].
    @Synchronized
    fun onVisibleImageDisplayed(pageIndex: Int, pageVariant: String = "") {
        if (!waitingForVisibleImage) return
        if (!isFocusedTarget(pageIndex, pageVariant)) {
            // KMK --> A held queue that never matches a visible frame deadlocks every
            // page behind it; make the mismatch observable in the reader log.
            logcat(LogPriority.INFO) {
                "ImageEnhancer: Visible frame $pageIndex/$pageVariant does not match target " +
                    "$targetPageIndex/$targetPageVariant " +
                    "(secondary=$targetSecondaryPageIndex/$targetSecondaryPageVariant); queue stays held"
            }
            // KMK <--
            return
        }
        waitingForVisibleImage = false
        logcat(LogPriority.INFO) {
            "ImageEnhancer: First visible image displayed: $pageIndex/$pageVariant; releasing ${queue.size} queued requests"
        }
        queueSignal.trySend(Unit)
    }
    // KMK <--

    @Synchronized
    fun cancelAll(reason: String = "cancelAll", resetNative: Boolean = true) {
        generation.incrementAndGet()
        queue.clear()
        pendingRequests.clear()
        activeJob?.cancel(CancellationException("Image enhancement cancelled: $reason"))
        activeRequest?.cancelled?.set(true)
        activeJob = null
        activeRequest = null
        activeMangaId = -1L
        activeChapterId = -1L
        activePageIndex = -1
        activePageVariant = ""
        initialTargetEnqueued = false
        if (resetNative) {
            try {
                Waifu2x.abortProcessing()
            } catch (t: Throwable) {
                logcat(LogPriority.WARN, t) { "ImageEnhancer: Failed to signal native abort" }
            }
            if (nativeResetJob?.isActive != true) {
                nativeResetJob = scope.launch {
                    try {
                        Waifu2x.resetRealCugan()
                    } catch (t: Throwable) {
                        logcat(LogPriority.ERROR, t) { "ImageEnhancer: Failed to reset native upscaler" }
                    }
                }
            }
        }
        logcat(LogPriority.DEBUG) { "ImageEnhancer: Cancelled all enhancement work (reason=$reason)" }
    }

    @Synchronized
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
        preemptActiveRequestIfBehindTarget()
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
        return pendingRequests.containsKey(requestKey(mangaId, chapterId, pageIndex, pageVariant)) ||
            ImageEnhancementCache.isSavePending(mangaId, chapterId, pageIndex, pageVariant)
    }

    @Synchronized
    fun isFocusedTarget(pageIndex: Int, pageVariant: String = ""): Boolean {
        return (pageIndex == targetPageIndex && pageVariant == targetPageVariant) ||
            (pageIndex == targetSecondaryPageIndex && pageVariant == targetSecondaryPageVariant)
    }

    @Synchronized
    fun isActivelyProcessing(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = ""): Boolean {
        return activeMangaId == mangaId &&
            activeChapterId == chapterId &&
            activePageIndex == pageIndex &&
            activePageVariant == pageVariant
    }

    // KMK -->
    /**
     * Returns what the actively processed page is currently doing, or null when the page is not
     * the active request or its attempt was already cancelled. Null lets status polling fall
     * back to the generic labels instead of reporting a phase that is about to be abandoned.
     */
    @Synchronized
    fun processingPhase(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = ""): ProcessingPhase? {
        return activePhase.takeIf {
            activeRequest?.cancelled?.get() == false &&
                isActivelyProcessing(mangaId, chapterId, pageIndex, pageVariant)
        }
    }

    /**
     * Moves the active request's phase forward. Called from the decoder between runtime
     * initialization, native inference and the cache hand-off; a stale or cancelled request
     * must not update the shared phase.
     */
    @Synchronized
    fun updateProcessingPhase(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        pageVariant: String,
        phase: ProcessingPhase,
    ) {
        if (activeRequest?.cancelled?.get() == false &&
            isActivelyProcessing(mangaId, chapterId, pageIndex, pageVariant)
        ) {
            activePhase = phase
        }
    }
    // KMK <--

    @Synchronized
    fun cancel(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = "") {
        val requestKey = requestKey(mangaId, chapterId, pageIndex, pageVariant)
        if (pendingRequests.remove(requestKey) != null) {
            val removed = queue.removeIf {
                it.mangaId == mangaId && it.chapterId == chapterId && it.pageIndex == pageIndex && it.pageVariant == pageVariant
            }
            if (removed) {
                logcat(LogPriority.DEBUG) { "ImageEnhancer: Cancelled page $pageIndex/$pageVariant" }
            }
        }
        if (
            activeMangaId == mangaId &&
            activeChapterId == chapterId &&
            activePageIndex == pageIndex &&
            activePageVariant == pageVariant
        ) {
            // A replacement request may already be queued for this page; the
            // cancelled attempt must not suppress its requeue bookkeeping.
            activeRequest?.requeueOnCancel?.set(false)
            preemptActiveRequest("page cancelled")
        }
    }

    @Synchronized
    fun cancelRequestsLessThan(context: Context, mangaId: Long, chapterId: Long, thresholdPageIndex: Int) {
        queue.removeIf { req ->
            if (req.mangaId == mangaId && req.chapterId == chapterId && req.pageIndex < thresholdPageIndex &&
                !isFocusedTarget(req.pageIndex, req.pageVariant)
            ) {
                pendingRequests.remove(req.key, req)
                logcat(LogPriority.DEBUG) { "ImageEnhancer: Pruned page ${req.pageIndex}/${req.pageVariant} (reason: < $thresholdPageIndex)" }
                true
            } else {
                false
            }
        }
    }

    @Synchronized
    fun cancelRequestsGreaterThan(context: Context, mangaId: Long, chapterId: Long, thresholdPageIndex: Int) {
        queue.removeIf { req ->
            if (req.mangaId == mangaId && req.chapterId == chapterId && req.pageIndex > thresholdPageIndex &&
                !isFocusedTarget(req.pageIndex, req.pageVariant)
            ) {
                pendingRequests.remove(req.key, req)
                logcat(LogPriority.DEBUG) { "ImageEnhancer: Pruned page ${req.pageIndex}/${req.pageVariant} (reason: > $thresholdPageIndex)" }
                true
            } else {
                false
            }
        }
    }

    private suspend fun processRequest(req: EnhanceRequest) {
        try {
            val resetJob = synchronized(this) {
                if (req.generation != generation.get() || pendingRequests[req.key] !== req) return
                activeMangaId = req.mangaId
                activeChapterId = req.chapterId
                activePageIndex = req.pageIndex
                activePageVariant = req.pageVariant
                activeRequest = req
                // KMK --> the claim starts a fresh attempt; the decoder advances the phase
                // through ENHANCING and SAVING as it reaches those stages.
                activePhase = ProcessingPhase.PREPARING
                // KMK <--
                nativeResetJob
            }
            // A reset must finish before this attempt can initialize a new model.
            resetJob?.join()
            if (req.generation != generation.get() || req.cancelled.get()) return
            logcat(LogPriority.DEBUG) {
                "ImageEnhancer: Processing page ${req.pageIndex}/${req.pageVariant} (priority=${req.priority})"
            }
            val data = req.dataProvider()
            if (data == null) {
                // KMK --> A dropped attempt must be observable: without this log a page
                // whose source vanished looks identical to one stuck in the queue.
                logcat(LogPriority.WARN) {
                    "ImageEnhancer: Page ${req.pageIndex}/${req.pageVariant} has no readable source; dropping request"
                }
                // KMK <--
                return
            }
            if (req.generation != generation.get() || req.cancelled.get()) return
            Waifu2x.prepareProcessing()
            if (req.cancelled.get()) return
            val request = ImageRequest.Builder(req.context)
                .data(data)
                // KMK --> hardware bitmaps cannot feed the native upscaler or the cache
                // encoder; force a software copy of the decoded page (upstream 1.3.17).
                .allowHardware(false)
                // KMK <--
                .memoryCachePolicy(CachePolicy.DISABLED)
                .customDecoder(true)
                .enhanced(true)
                .mangaId(req.mangaId)
                .chapterId(req.chapterId)
                .pageIndex(req.pageIndex)
                .pageVariant(req.pageVariant)
                .build()

            val disposable = SingletonImageLoader.get(req.context).enqueue(request)
            val job = disposable.job
            synchronized(this) {
                if (activeRequest === req) activeJob = job
                if (req.generation != generation.get() || req.cancelled.get()) {
                    Waifu2x.abortProcessing()
                    job.cancel(CancellationException("Image enhancement request became obsolete"))
                }
            }
            job.await()
        } catch (e: OutOfMemoryError) {
            // KMK --> A page that exhausts memory is marked skipped so polling does not
            // retry it forever; the reader keeps showing the original image.
            if (req.generation == generation.get() && !req.cancelled.get()) {
                ImageEnhancementCache.saveSkippedToCache(
                    req.mangaId,
                    req.chapterId,
                    req.pageIndex,
                    currentConfigHash(),
                    req.pageVariant,
                )
            }
            logcat(LogPriority.ERROR, e) { "ImageEnhancer: Keeping original after enhancement ran out of memory" }
            // KMK <--
        } finally {
            synchronized(this) {
                if (activeRequest === req) {
                    activeMangaId = -1L
                    activeChapterId = -1L
                    activePageIndex = -1
                    activePageVariant = ""
                    activeJob = null
                    activeRequest = null
                }
                pendingRequests.remove(req.key, req)

                if (req.requeueOnCancel.get() && req.generation == generation.get()) {
                    logcat(LogPriority.DEBUG) {
                        "ImageEnhancer: Re-queueing preempted preload page ${req.pageIndex}/${req.pageVariant}"
                    }
                    enhanceLazy(
                        context = req.context,
                        mangaId = req.mangaId,
                        chapterId = req.chapterId,
                        pageIndex = req.pageIndex,
                        highPriority = false,
                        pageVariant = req.pageVariant,
                        dataProvider = req.dataProvider,
                    )
                }
            }
        }
    }

    private val EnhanceRequest.key: String
        get() = requestKey(mangaId, chapterId, pageIndex, pageVariant)

    private fun requestKey(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String): String {
        return "${mangaId}_${chapterId}_${pageIndex}_$pageVariant"
    }

    private fun bufferStream(streamFactory: () -> java.io.InputStream): Any? {
        return try {
            streamFactory().use { okio.Buffer().readFrom(it) }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "ImageEnhancer: Failed to read enhancement source" }
            null
        }
    }

    // KMK -->
    private fun initializeRuntime(context: Context) {
        ImageEnhancementCache.init(context)
        if (!runtimeInitialized.compareAndSet(false, true)) return

        scope.launch {
            ImageEnhancementCache.checkAndTrim(context)
            updatePerformanceSettings()
        }
        scope.launch {
            preferences.realCuganPerformanceMode().changes().collect {
                updatePerformanceSettings()
            }
        }
        scope.launch {
            preferences.realCuganTileSize().changes().collect {
                updatePerformanceSettings()
            }
        }
    }

    private fun updatePerformanceSettings() {
        val sleepMs = when (preferences.realCuganPerformanceMode().get()) {
            0 -> 0
            1 -> 5
            2 -> 15
            else -> 0
        }
        Waifu2x.updatePerformance(sleepMs, preferences.realCuganTileSize().get().coerceAtLeast(32))
    }

    private fun currentConfigHash(): String {
        return ImageEnhancementCache.getConfigHash(preferences)
    }
    // KMK <--

    private fun preemptActiveRequestIfBehindTarget() {
        // KMK --> Nearby work finishes and populates the cache during ordinary page turns;
        // only a page left well behind the target is worth aborting (upstream 1.3.17).
        if (activePageIndex >= 0 && activePageIndex < targetPageIndex - 2 &&
            !isFocusedTarget(activePageIndex, activePageVariant)
        ) {
            preemptActiveRequest("active page is behind visible target")
        }
    }

    private fun preemptForVisibleRequest(pageIndex: Int, pageVariant: String) {
        if (activePageIndex >= 0 &&
            (activePageIndex != pageIndex || activePageVariant != pageVariant) &&
            !isFocusedTarget(activePageIndex, activePageVariant) &&
            // KMK --> A request within a couple of pages of the visible one still serves an
            // imminent page turn; aborting it would discard work the reader needs next.
            kotlin.math.abs(activePageIndex - pageIndex) > 2
            // KMK <--
        ) {
            preemptActiveRequest("visible page requested", requeue = activePageIndex > targetPageIndex)
        }
    }

    private fun preemptActiveRequest(reason: String, requeue: Boolean = false) {
        val request = activeRequest ?: return
        if (requeue) {
            request.requeueOnCancel.set(true)
        }
        if (!request.cancelled.compareAndSet(false, true)) return

        logcat(LogPriority.DEBUG) {
            "ImageEnhancer: Preempting active page $activePageIndex/$activePageVariant ($reason)"
        }
        Waifu2x.abortProcessing()
        activeJob?.cancel(CancellationException("Image enhancement preempted: $reason"))
    }
}
