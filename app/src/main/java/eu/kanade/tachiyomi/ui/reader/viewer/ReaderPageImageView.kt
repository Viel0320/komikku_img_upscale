package eu.kanade.tachiyomi.ui.reader.viewer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.animation.doOnEnd
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import coil3.BitmapImage
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.LandscapeZoomScaleType
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonSubsamplingImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private val alwaysDecodeLongStripWithSSIV by lazy {
        Injekt.get<BasePreferences>().alwaysDecodeLongStripWithSSIV().get()
    }

    // KMK --> recreated on every attach so its collectors never outlive the view
    private var viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var preferenceJobs: List<Job> = emptyList()
    // KMK <--

    private var isSettingProcessedImage = false // Flag to prevent recursive processing

    // KMK -->
    private val preferences: ReaderPreferences by injectLazy()

    private val realCuganEnabled: Boolean
        get() = preferences.realCuganEnabled().get()

    private val realCuganNoiseLevel: Int
        get() = preferences.realCuganNoiseLevel().get()

    private val realCuganScale: Int
        get() = preferences.realCuganScale().get()

    private val preloadSize: Int
        get() = if (preferences.realCuganEnabled().get()) preferences.realCuganPreloadSize().get() else 4

    private val realCuganModel: Int
        get() = preferences.realCuganModel().get()

    private val realCuganMaxSizeWidth: Int
        get() = preferences.realCuganMaxSizeWidth().get()

    private val realCuganMaxSizeHeight: Int
        get() = preferences.realCuganMaxSizeHeight().get()

    private val realCuganSkipMaxSizeWidth: Int
        get() = preferences.realCuganSkipMaxSizeWidth().get()

    private val realCuganSkipMaxSizeHeight: Int
        get() = preferences.realCuganSkipMaxSizeHeight().get()

    private val realCuganShowStatus: Boolean
        get() = preferences.realCuganShowStatus().get()

    private val tileSize: Int
        get() = preferences.realCuganTileSize().get().coerceAtLeast(32)
    // KMK <--

    // Performance throttling is applied by [ImageEnhancer.initializeRuntime]; this view only
    // owns presentation state.
    private var pageView: View? = null
    private var currentLoadedUri: String? = null
    private val statusUpdateVersion = AtomicInteger()

    // KMK -->
    // Incremented on every new image binding (and on recycle/detach); async enhanced-cache
    // swaps compare their captured revision before touching the view.
    private var imageRevision = 0
    private var currentCachedFile: java.io.File? = null
    private var originalStreamFn: (() -> java.io.InputStream)? = null
    private var enhancedRecoveryCount = 0

    // Enhancement waits for the first original frame to be visible: the pending
    // callback runs two animation frames after onImageLoaded, so the original
    // decodes and draws before GPU/NPU work or a cached replacement competes for
    // the renderer (upstream 1.3.16).
    private var hasDisplayedImage = false
    private var pendingInitialEnhancement: (() -> Unit)? = null
    // KMK <--

    // KMK -->
    var enhancedImageSourceFactory: ((java.io.File) -> BufferedSource?)? = null

    /**
     * Builds the original (non-enhanced) display source, used when an enhanced cache entry
     * fails to load so the readable original page is never hidden behind an error.
     */
    var originalImageSourceFactory: (() -> BufferedSource?)? = null

    /**
     * Secondary page of an SY merged double-page spread. When set, both halves are enhanced and
     * [enhancedMergeSourceFactory] merges them into the displayed spread.
     */
    var secondaryPage: ReaderPage? = null

    /** Builds the merged display source from the cached enhanced halves of a double-page spread. */
    var enhancedMergeSourceFactory: ((primary: java.io.File, secondary: java.io.File) -> BufferedSource?)? = null
    var suppressDefaultStatus = false
    /** Whether this view participates in image enhancement (merged spreads included). */
    var enhancementDisplayEnabled: Boolean = true
    // KMK <--

    private var config: Config? = null

    // KMK -->
    private var processingJob: Job? = null
    private var processingKey: EnhancementPollKey? = null
    private var enhancedBitmap: Bitmap? = null
    private var processedSwapView: SubsamplingScaleImageView? = null
    private var outgoingProcessedView: SubsamplingScaleImageView? = null
    private var processedSwapAnimator: ValueAnimator? = null

    // Helper properties for Enhancement logic
    var pageIndex: Int = -1
    var mangaId: Long = -1L
    var chapterId: Long = -1L
    var readerPage: ReaderPage? = null
    var enhancementVariantOverride: String? = null
    var enhancementStreamOverride: (() -> java.io.InputStream)? = null
    var processedTransitionStartFraction: Float = 0f
    var processedTransitionEndFraction: Float = 1f
    var controlsCurrentPageSelection: Boolean = true
    // KMK <--

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    init {
        // KMK -->
        // Preference collectors are registered in onAttachedToWindow and cancelled in
        // onDetachedFromWindow so they cannot leak for detached/recycled views, and cost
        // nothing while the enhancement feature is disabled.
        // KMK <--
    }

    // KMK -->
    private fun startPreferenceCollectors() {
        preferenceJobs = listOf(
            viewScope.launchIO {
                preferences.realCuganShowStatus().changes()
                    .collect { enabled ->
                        withUIContext {
                            if (!enabled) {
                                statusView.isVisible = false
                            } else if (!statusView.text.isNullOrEmpty()) {
                                statusView.isVisible = true
                                statusView.bringToFront()
                            }
                        }
                    }
            },
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // KMK --> reuse the initial scope on first attach; only rebuild it once the previous
        // one was cancelled, so the declared instance can never be orphaned.
        if (viewScope.coroutineContext[Job]?.isActive != true) {
            viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
        startPreferenceCollectors()
    }
    // KMK <--

    // KMK -->
    private var statusViewCreated = false
    private val statusView: TextView by lazy {
        statusViewCreated = true
        TextView(context).apply {
            layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins(20, 0, 0, 20)
            }
            setTextColor(Color.WHITE)
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            isVisible = false
            this@ReaderPageImageView.addView(this)
        }
    }

    private val enhancedOverlay: AppCompatImageView by lazy {
        AppCompatImageView(context).apply {
            layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isVisible = false
            this@ReaderPageImageView.addView(this)
        }
    }

    protected fun bringEnhancementStatusToFront() {
        if (statusViewCreated) statusView.bringToFront()
    }

    private fun updateStatus(text: String?) {
        val version = statusUpdateVersion.incrementAndGet()
        val revision = imageRevision
        val applyStatus = Runnable {
            // An older poll may still be posted when a new status or binding arrives.
            if (version == statusUpdateVersion.get() && revision == imageRevision) {
                statusView.updateEnhancementStatus(text.takeIf { !suppressDefaultStatus && realCuganShowStatus })
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) applyStatus.run() else post(applyStatus)
    }

    // KMK -->
    /**
     * Configured backend name used as the status label once a page is processed and while
     * the enhanced result is pending, e.g. "QNN". Mirrors the value feeding the cache
     * config hash so the label always matches the config; no runtime probing.
     */
    private fun enhancementBackendName(): String {
        if (!realCuganEnabled) return ""
        // KMK -->
        return when (preferences.realCuganProcessingBackend().get()) {
            Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU -> "QNN"
            Waifu2x.PROCESSING_BACKEND_MEDIATEK_NPU -> "LiteRT"
            else -> "Vulkan"
        }
        // KMK <--
    }
    // KMK <--

    // KMK <--

    @CallSuper
    open fun onImageLoaded() {
        // KMK -->
        hasDisplayedImage = true
        // KMK <--
        onImageLoaded?.invoke()
        background = pageBackground

        // KMK -->
        // Keep the processed preview fully covering the page until the replacement image is ready,
        // then switch instantly to avoid exposing SSIV's blank loading state.
        if (isSettingProcessedImage) {
            pageView?.alpha = 1f
            enhancedOverlay.animate().cancel()
            enhancedOverlay.alpha = 0f
            enhancedOverlay.isVisible = false
            enhancedOverlay.setImageBitmap(null)
            enhancedBitmap?.recycle()
            enhancedBitmap = null
            isSettingProcessedImage = false
        }
        // KMK <--

        // KMK -->
        notifyVisibleImageDisplayed()
        // KMK <--
    }

    // KMK -->
    /**
     * onReady runs before drawing. Leave a complete frame for the original on screen
     * before GPU/NPU work or a cached replacement can occupy the renderer, then run
     * the deferred first enhancement and tell the enhancer the visible frame landed.
     */
    private fun notifyVisibleImageDisplayed() {
        val revision = imageRevision
        postOnAnimation {
            postOnAnimation displayed@{
                if (revision != imageRevision || !hasDisplayedImage) return@displayed
                val enhance = pendingInitialEnhancement
                pendingInitialEnhancement = null
                enhance?.invoke()
                if (isVisibleOnScreen()) {
                    ImageEnhancer.onVisibleImageDisplayed(
                        readerPage?.index ?: pageIndex,
                        enhancementVariant(),
                    )
                }
            }
        }
    }
    // KMK <--

    @CallSuper
    open fun onImageLoadError(error: Throwable?) {
        // KMK -->
        // A failed enhanced-cache load must not hide a readable original page.
        if (recoverEnhancedCacheLoad(error)) return
        // KMK <--
        onImageLoadError?.invoke(error)

        // KMK -->
        // Hide overlay and recycle temporary bitmap if enhanced image load failed
        if (isSettingProcessedImage) {
            pageView?.alpha = 1f
            enhancedOverlay.setImageBitmap(null)
            enhancedOverlay.isVisible = false
            enhancedBitmap?.recycle()
            enhancedBitmap = null
            isSettingProcessedImage = false
        }
        // KMK <--
    }

    // KMK -->
    /** Cache failures must not hide a readable original page. Retry enhancement once per binding. */
    protected fun recoverEnhancedCacheLoad(error: Throwable?): Boolean {
        val file = currentCachedFile ?: return false
        val displayConfig = config ?: return false
        val originalFactory = originalImageSourceFactory ?: run {
            val stream = enhancementStreamOverride ?: originalStreamFn ?: readerPage?.stream ?: return false
            { stream().use { Buffer().readFrom(it) } }
        }
        val revision = imageRevision
        val retryEnhancement = enhancedRecoveryCount++ == 0
        currentCachedFile = null
        currentLoadedUri = null
        processingJob?.cancel()
        clearProcessedSwapView()
        pageView?.alpha = 1f
        enhancedOverlay.setImageBitmap(null)
        enhancedOverlay.isVisible = false
        enhancedBitmap?.recycle()
        enhancedBitmap = null
        isSettingProcessedImage = false
        logcat(LogPriority.WARN, error) { "ReaderPageImageView: Restoring original after enhanced cache load failed" }
        viewScope.launchIO {
            ImageEnhancementCache.discardCachedImage(file)
            val original = try {
                originalFactory() ?: throw IllegalStateException("Original image is unavailable")
            } catch (e: Exception) {
                withUIContext { if (revision == imageRevision) onImageLoadError(e) }
                return@launchIO
            }
            withUIContext {
                if (revision != imageRevision) {
                    original.close()
                    return@withUIContext
                }
                prepareNonAnimatedImageView()
                setNonAnimatedImage(original, displayConfig, originalStreamFn, allowEnhancement = retryEnhancement)
                if (!retryEnhancement) updateStatus(context.stringResource(KMR.strings.reader_status_raw))
            }
        }
        return true
    }
    // KMK <--

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)

        // KMK -->
        if (processedSwapAnimator?.isRunning == true) {
            completeProcessedSwapTransition(notifyLoaded = true)
        }

        // If zooming, dismiss the static overlay immediately to show the zoomable image
        if (newScale != 1f && isSettingProcessedImage) {
            enhancedOverlay.animate().cancel()
            enhancedOverlay.isVisible = false
            enhancedOverlay.setImageBitmap(null)
            enhancedBitmap?.recycle()
            enhancedBitmap = null
            isSettingProcessedImage = false
            pageView?.alpha = 1f
        }
        // KMK <--
    }

    // KMK -->
    private fun clearProcessedSwapView() {
        processedSwapAnimator?.run {
            removeAllUpdateListeners()
            removeAllListeners()
            cancel()
        }
        processedSwapAnimator = null

        val swapView = processedSwapView
        val outgoingView = outgoingProcessedView
        if (swapView != null) {
            if (pageView === swapView) {
                pageView = outgoingView
            }
            // KMK: detach before recycling - recycling an attached SSIV nulls vTranslate and a
            // subsequent draw pass crashes in sendStateChanged.
            swapView.setOnStateChangedListener(null)
            removeView(swapView)
            swapView.recycle()
        }
        outgoingView?.alpha = 1f
        outgoingView?.clipBounds = null
        processedSwapView = null
        outgoingProcessedView = null
    }

    private fun completeProcessedSwapTransition(notifyLoaded: Boolean) {
        processedSwapAnimator?.run {
            removeAllUpdateListeners()
            removeAllListeners()
            cancel()
        }
        processedSwapAnimator = null

        val swapView = processedSwapView ?: return
        swapView.alpha = 1f
        swapView.clipBounds = null
        pageView = swapView

        outgoingProcessedView?.let { outgoingView ->
            if (outgoingView !== swapView) {
                // KMK: detach before recycling (see clearProcessedSwapView).
                outgoingView.setOnStateChangedListener(null)
                removeView(outgoingView)
                outgoingView.recycle()
            }
        }
        processedSwapView = null
        outgoingProcessedView = null

        if (notifyLoaded) {
            onImageLoaded()
        }
    }

    private fun displayedImageRect(view: SubsamplingScaleImageView): RectF? {
        val center = view.center ?: return null
        val scale = view.scale
        val sourceWidth = view.sWidth
        val sourceHeight = view.sHeight
        if (scale <= 0f || sourceWidth <= 0 || sourceHeight <= 0 || view.width <= 0 || view.height <= 0) {
            return null
        }

        val left = view.width / 2f - center.x * scale
        val top = view.height / 2f - center.y * scale
        return RectF(
            left,
            top,
            left + sourceWidth * scale,
            top + sourceHeight * scale,
        )
    }

    private fun animateProcessedSwap(
        activeView: SubsamplingScaleImageView,
        targetConfig: Config?,
        setImageBlock: SubsamplingScaleImageView.() -> Unit,
    ) {
        clearProcessedSwapView()

        val targetScale = activeView.scale
        val targetCenter = activeView.center?.let { PointF(it.x, it.y) }
        val targetMinScale = activeView.minScale
        val targetWidth = activeView.sWidth
        val targetHeight = activeView.sHeight
        outgoingProcessedView = activeView
        val swapView = createSubsamplingPageView().apply {
            alpha = 0f
            isVisible = true
            setDoubleTapZoomDuration((targetConfig?.zoomDuration ?: 500).getSystemScaledDuration())
            setMinimumScaleType(targetConfig?.minimumScaleType ?: SCALE_TYPE_CENTER_INSIDE)
            setMinimumDpi(1)
            setCropBorders(targetConfig?.cropBorders ?: false)
            setOnImageEventListener(
                object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onReady() {
                        if (processedSwapView !== this@apply) return

                        setupZoom(targetConfig)

                        val wasZoomed =
                            targetScale > 0f &&
                                targetCenter != null &&
                                targetMinScale > 0f &&
                                targetWidth > 0 &&
                                targetHeight > 0 &&
                                targetScale > targetMinScale + 0.01f

                        if (wasZoomed) {
                            val zoomFactor = targetScale / targetMinScale
                            val mappedCenter = PointF(
                                (targetCenter.x / targetWidth) * sWidth,
                                (targetCenter.y / targetHeight) * sHeight,
                            )
                            val mappedScale = (minScale * zoomFactor).coerceIn(minScale, maxScale)
                            setScaleAndCenter(mappedScale, mappedCenter)
                        } else if (isVisibleOnScreen()) {
                            landscapeZoom(true)
                        }

                        bringToFront()
                        bringEnhancementStatusToFront()
                        pageView = this@apply
                        val revealStart = processedTransitionStartFraction.coerceIn(0f, 1f)
                        val revealEnd = processedTransitionEndFraction.coerceIn(revealStart, 1f)
                        val imageRect = displayedImageRect(this@apply)
                        val contentLeft = imageRect?.left ?: 0f
                        val contentWidth = imageRect?.width() ?: width.toFloat()
                        val viewWidth = width.coerceAtLeast(1)
                        val clipLeft = (contentLeft + contentWidth * revealStart)
                            .roundToInt()
                            .coerceIn(0, viewWidth - 1)
                        val clipRight = (contentLeft + contentWidth * revealEnd)
                            .roundToInt()
                            .coerceIn(clipLeft + 1, viewWidth)
                        clipBounds = Rect(clipLeft, 0, clipRight, height)
                        alpha = 0f

                        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = PROCESSED_SWAP_DURATION_MS
                            interpolator = FastOutSlowInInterpolator()
                            addUpdateListener { animator ->
                                alpha = animator.animatedValue as Float
                            }
                            doOnEnd {
                                if (processedSwapAnimator === this) {
                                    processedSwapAnimator = null
                                    completeProcessedSwapTransition(notifyLoaded = true)
                                }
                            }
                        }
                        processedSwapAnimator = animator
                        animator.start()
                    }

                    override fun onImageLoadError(e: Exception) {
                        clearProcessedSwapView()
                        this@ReaderPageImageView.onImageLoadError(e)
                    }
                },
            )
        }
        processedSwapView = swapView
        addView(swapView, 0, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        swapView.setImageBlock()
    }

    // KMK <--

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    open fun onPageSelected(forward: Boolean) {
        // KMK --> Enhancement cache status / pruning
        val mId = readerPage?.chapter?.chapter?.manga_id ?: mangaId
        val cId = readerPage?.chapter?.chapter?.id ?: chapterId
        val pIdx = readerPage?.index ?: pageIndex

        val secondary = secondaryPage

        // Update global current page index to help other instances decide if they should self-heal
        if (controlsCurrentPageSelection && pIdx >= 0) {
            currentGlobalPageIndex = pIdx
            ImageEnhancer.reprioritizeAround(
                pageIndex = pIdx,
                pageVariant = enhancementVariant(),
                secondaryPageIndex = secondary?.index,
                secondaryPageVariant = secondary?.let(::variantOf).orEmpty(),
            )
        }

        // KMK -->
        // The selection callback can run before the holder's first frame is displayed;
        // defer cache/enhancement work until then, when pendingInitialEnhancement runs.
        if (hasDisplayedImage) notifyVisibleImageDisplayed()
        // KMK <--

        if (hasDisplayedImage && pIdx >= 0 && mId != -1L && cId != -1L && realCuganEnabled && enhancementDisplayEnabled) {
            ImageEnhancementCache.init(context)
            val configHash = enhancementConfigHash()
            val pageVariant = enhancementVariant()
            val secondaryFile = secondaryCachedFile(mId, configHash)

            // KMK --> the sibling half is visible too, so a skip marker recorded for it gets the
            // same one-shot retry as the primary page: clear it on selection only, so a half that
            // keeps failing cannot loop through the poll heal path.
            if (secondary != null && isSecondarySkipped(mId, configHash)) {
                secondary.chapter.chapter.id?.let { secondaryChapterId ->
                    ImageEnhancementCache.removeSkipMarker(mId, secondaryChapterId, secondary.index, configHash, variantOf(secondary))
                }
            }
            // KMK <--
            if (secondary != null && secondaryFile == null) {
                // Waiting for the sibling half - keep both queued and let polling swap once ready.
                updateStatus(context.stringResource(KMR.strings.reader_status_processing))
                enqueueEnhancement(mId, cId, pIdx, highPriority = true)
                enqueueSecondaryIfNeeded(mId, configHash, highPriority = true)
                startEnhancementPolling(mId, cId, pIdx, configHash)
            } else {
                val cachedFile = ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant)
                if (cachedFile != null) {
                    // Already processed - update status and load enhanced image
                    val uriString = cachedFile.toURI().toString()
                    if (currentLoadedUri != uriString) {
                        // Load the enhanced image if not already loaded or if source changed
                        val revision = imageRevision
                        viewScope.launchIO {
                            val transformedSource = buildEnhancedDisplaySource(cachedFile, secondaryFile)
                            if (transformedSource != null) {
                                withUIContext {
                                    if (revision == imageRevision) {
                                        setProcessedSource(cachedFile, transformedSource = transformedSource)
                                    } else {
                                        transformedSource.close()
                                    }
                                }
                                return@launchIO
                            }

                            if (secondaryFile != null) {
                                // Cannot merge the enhanced halves - keep showing the original spread.
                                withUIContext { updateStatus(context.stringResource(KMR.strings.reader_status_raw)) }
                                return@launchIO
                            }

                            val source = ImageEnhancementCache.readCachedImage(cachedFile)

                            if (source != null) {
                                withUIContext {
                                    if (revision == imageRevision) {
                                        setProcessedSource(cachedFile, transformedSource = source)
                                    } else {
                                        source.close()
                                    }
                                }
                            } else {
                                healInvalidEnhancedCache(mId, cId, pIdx, configHash, readerPage, forceCurrentPage = true)
                                startEnhancementPolling(mId, cId, pIdx, configHash, readerPage)
                            }
                        }
                    } else {
                        updateStatus(enhancementBackendName())
                    }
                } else if (ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant)) {
                    // KMK --> the page is now the one the user navigated to, so give it another
                    // chance instead of leaving it raw for the rest of the session. Mirrors
                    // upstream's ensureVisibleExtraPageEnhancement; the marker is only cleared
                    // on selection, never on a plain rebind, so a failing page cannot loop.
                    logcat(LogPriority.DEBUG) { "ReaderPageImageView: Page $pIdx was skipped, retrying because it is visible" }
                    ImageEnhancementCache.removeSkipMarker(mId, cId, pIdx, configHash, pageVariant)
                    updateStatus(context.stringResource(KMR.strings.reader_status_processing))
                    enqueueEnhancement(mId, cId, pIdx, highPriority = true)
                    enqueueSecondaryIfNeeded(mId, configHash, highPriority = true)
                    startEnhancementPolling(mId, cId, pIdx, configHash)
                    // KMK <--
                } else {
                    // Not in cache, not skipped - ensure it's being processed with high priority
                    updateStatus(context.stringResource(KMR.strings.reader_status_processing))
                    enqueueEnhancement(mId, cId, pIdx, highPriority = true)
                    enqueueSecondaryIfNeeded(mId, configHash, highPriority = true)

                    // Start/restart polling if not already running
                    startEnhancementPolling(mId, cId, pIdx, configHash)
                }
            }
        }

        // Prune others. Keep the same page-based window as the polling self-heal check
        // (`pIdx <= currentGlobalPageIndex + preloadSize`); in double-page mode the sibling page is
        // pIdx + 1 and always included because preloadSize is at least 1.
        if (pIdx >= 0 && mId != -1L && cId != -1L) {
            ImageEnhancer.cancelRequestsLessThan(context.applicationContext, mId, cId, pIdx)
            ImageEnhancer.cancelRequestsGreaterThan(context.applicationContext, mId, cId, pIdx + preloadSize)
        }
        // KMK <--

        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                landscapeZoom(forward)
            } else {
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            setupZoom(config)
                            landscapeZoom(forward)
                            this@ReaderPageImageView.onImageLoaded()
                        }

                        override fun onImageLoadError(e: Exception) {
                            onImageLoadError(e)
                        }
                    },
                )
            }
        }
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        val config = config
        if (config != null &&
            config.landscapeZoom &&
            config.minimumScaleType == SCALE_TYPE_CENTER_INSIDE &&
            sWidth > sHeight &&
            scale == minScale
        ) {
            handler?.postDelayed(500) {
                val point = when (config.zoomStartPosition) {
                    ZoomStartPosition.LEFT -> if (forward) PointF(0F, 0F) else PointF(sWidth.toFloat(), 0F)
                    ZoomStartPosition.RIGHT -> if (forward) PointF(sWidth.toFloat(), 0F) else PointF(0F, 0F)
                    ZoomStartPosition.CENTER -> center
                }

                val targetScale = /* KMK --> */ when (config.landscapeZoomScaleType) {
                    LandscapeZoomScaleType.DOUBLE -> scale * 2
                    // KMK <--
                    else -> height.toFloat() / sHeight.toFloat()
                }
                (animateScaleAndCenter(targetScale, point) ?: return@postDelayed)
                    .withDuration(500)
                    .withEasing(EASE_IN_OUT_QUAD)
                    .withInterruptible(true)
                    .start()
            }
        }
    }

    fun setImage(drawable: Drawable, config: Config) {
        // KMK -->
        imageRevision++
        hasDisplayedImage = false
        pendingInitialEnhancement = null
        updateStatus(null)
        processingJob?.cancel()
        currentCachedFile = null
        enhancedRecoveryCount = 0
        originalStreamFn = null
        // KMK <--
        this.config = config
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
    }

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config, streamFn: (() -> java.io.InputStream)? = null) {
        // KMK -->
        imageRevision++
        hasDisplayedImage = false
        pendingInitialEnhancement = null
        updateStatus(null)
        processingJob?.cancel()
        currentCachedFile = null
        enhancedRecoveryCount = 0
        originalStreamFn = streamFn
        // KMK <--
        this.config = config
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config, streamFn)
        }
    }

    // KMK -->
    fun recycle() {
        imageRevision++
        hasDisplayedImage = false
        pendingInitialEnhancement = null
        updateStatus(null)
        currentCachedFile = null
        originalStreamFn = null
        clearProcessedSwapView()
        pageView?.let {
            processingJob?.cancel()
            processingJob = null

            when (it) {
                is SubsamplingScaleImageView -> it.recycle()
                is AppCompatImageView -> it.dispose()
            }
            it.isVisible = false
            enhancedOverlay.setImageBitmap(null)
            enhancedOverlay.isVisible = false
            enhancedBitmap?.recycle()
            enhancedBitmap = null
            isSettingProcessedImage = false
            currentLoadedUri = null
            invalidate()
        }
    }
    // KMK <--

    /**
     * Check if the image can be panned to the left
     */
    fun canPanLeft(): Boolean = canPan { it.left }

    /**
     * Check if the image can be panned to the right
     */
    fun canPanRight(): Boolean = canPan { it.right }

    // KMK -->
    /**
     * Check if the image can be panned up
     */
    fun canPanUp(): Boolean = canPan { it.top }

    /**
     * Check if the image can be panned down
     */
    fun canPanDown(): Boolean = canPan { it.bottom }
    // KMK <--

    /**
     * Check whether the image can be panned.
     * @param fn a function that returns the direction to check for
     */
    private fun canPan(fn: (RectF) -> Float): Boolean {
        (pageView as? SubsamplingScaleImageView)?.let { view ->
            RectF().let {
                view.getPanRemaining(it)
                return fn(it) > 1
            }
        }
        return false
    }

    /**
     * Pans the image to the left by a screen's width worth.
     */
    fun panLeft() {
        pan { center, view -> center.also { it.x -= view.width / view.scale } }
    }

    /**
     * Pans the image to the right by a screen's width worth.
     */
    fun panRight() {
        pan { center, view -> center.also { it.x += view.width / view.scale } }
    }

    // KMK -->
    /**
     * Pans the image down by a screen's height worth.
     */
    fun panDown() {
        pan { center, view -> center.also { it.y += view.height / view.scale } }
    }

    /**
     * Pans the image up by a screen's height worth.
     */
    fun panUp() {
        pan { center, view -> center.also { it.y -= view.height / view.scale } }
    }
    // KMK <--

    /**
     * Pans the image.
     * @param fn a function that computes the new center of the image
     */
    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        (pageView as? SubsamplingScaleImageView)?.let { view ->

            val target = fn(view.center ?: return, view)
            view.animateCenter(target)!!
                .withEasing(EASE_OUT_QUAD)
                .withDuration(250)
                .withInterruptible(true)
                .start()
        }
    }

    // KMK -->
    protected fun setProcessedSource(
        file: java.io.File,
        transformedSource: BufferedSource,
        forceRefresh: Boolean = false,
    ) {
        // Ignore results produced by an older enhancement configuration: a model
        // switch clears the cache asynchronously and a slow poller can still hold
        // a file that was deleted (or already replaced) for the previous config.
        val cachedConfig = file.name.substringAfter('_').removeSuffix(".webp")
        val currentConfig = enhancementConfigHash()
        if (!realCuganEnabled || (cachedConfig != currentConfig && !cachedConfig.startsWith("${currentConfig}_"))) {
            transformedSource.close()
            return
        }
        val uriString = file.toURI().toString()
        // KMK --> Re-showing the already displayed file must not reset the active image
        // (a spread refresh re-runs this with the same output); callers that genuinely
        // need the swap re-run pass forceRefresh (upstream 1.3.17).
        if (!forceRefresh && currentLoadedUri == uriString) {
            transformedSource.close()
            return
        }
        // KMK <--
        currentCachedFile = file
        updateStatus(enhancementBackendName())

        val activeView = pageView as? SubsamplingScaleImageView
        if (activeView != null) {
            animateProcessedSwap(activeView, config) {
                setImageWithCurrentCrop(ImageSource.inputStream(transformedSource.inputStream()))
            }
        } else {
            val previewBitmap = try {
                android.graphics.BitmapFactory.decodeStream(transformedSource.peek().inputStream())
            } catch (_: Exception) {
                null
            }

            if (previewBitmap != null) {
                enhancedOverlay.scaleType = ImageView.ScaleType.FIT_CENTER
                enhancedOverlay.bringToFront()
                enhancedOverlay.setImageBitmap(previewBitmap)
                enhancedOverlay.alpha = 1f
                enhancedOverlay.isVisible = true
                bringEnhancementStatusToFront()
                enhancedBitmap = previewBitmap
                isSettingProcessedImage = true
                pageView?.alpha = 0f
            } else {
                pageView?.alpha = 1f
                enhancedOverlay.animate().cancel()
                enhancedOverlay.setImageBitmap(null)
                enhancedOverlay.isVisible = false
                enhancedBitmap?.recycle()
                enhancedBitmap = null
                isSettingProcessedImage = false
            }
        }
        currentLoadedUri = uriString
        isVisible = true
    }

    /**
     * Starts a new decode with the current border-crop setting.
     *
     * The subsampling decoder reads this setting when [SubsamplingScaleImageView.setImage] is
     * called. Reapply it at every source swap so the temporary original image and the enhanced
     * replacement cannot be decoded with different crop settings.
     */
    private fun SubsamplingScaleImageView.setImageWithCurrentCrop(source: ImageSource) {
        setCropBorders(config?.cropBorders == true)
        setImage(source)
    }

    private fun enhancementVariant(): String {
        return variantOf(readerPage)
    }

    private fun variantOf(page: ReaderPage?): String {
        return enhancementVariantOverride ?: page?.enhancementKeySuffix.orEmpty()
    }

    private fun enhancementConfigHash(): String {
        return ImageEnhancementCache.getConfigHash(preferences)
    }

    private fun secondaryCachedFile(mangaId: Long, configHash: String): java.io.File? {
        val page = secondaryPage ?: return null
        val chapterId = page.chapter.chapter.id ?: return null
        return ImageEnhancementCache.getCachedImage(mangaId, chapterId, page.index, configHash, variantOf(page))
    }

    private fun isSecondarySkipped(mangaId: Long, configHash: String): Boolean {
        val page = secondaryPage ?: return false
        val chapterId = page.chapter.chapter.id ?: return false
        return ImageEnhancementCache.isSkipped(mangaId, chapterId, page.index, configHash, variantOf(page))
    }

    private fun isSecondaryRequested(mangaId: Long): Boolean {
        val page = secondaryPage ?: return false
        val chapterId = page.chapter.chapter.id ?: return false
        return ImageEnhancer.hasRequest(mangaId, chapterId, page.index, variantOf(page))
    }

    /**
     * Resolves the display source for the (possibly merged) enhanced page. When [secondaryFile] is
     * present the two enhanced halves are merged, otherwise the single enhanced image is used.
     */
    private fun buildEnhancedDisplaySource(primaryFile: java.io.File, secondaryFile: java.io.File?): BufferedSource? {
        return if (secondaryFile != null) {
            enhancedMergeSourceFactory?.invoke(primaryFile, secondaryFile)
        } else {
            enhancedImageSourceFactory?.invoke(primaryFile)
        }
    }

    private fun enqueueSecondaryIfNeeded(mangaId: Long, configHash: String, highPriority: Boolean) {
        val page = secondaryPage ?: return
        val chapterId = page.chapter.chapter.id ?: return
        val variant = variantOf(page)
        if (ImageEnhancementCache.getCachedImage(mangaId, chapterId, page.index, configHash, variant) != null) return
        if (ImageEnhancementCache.isSkipped(mangaId, chapterId, page.index, configHash, variant)) return
        if (ImageEnhancer.hasRequest(mangaId, chapterId, page.index, variant)) return
        ImageEnhancer.enhance(context.applicationContext, page, highPriority)
    }

    private fun buildEnhancementDataProvider(
        streamFn: (() -> java.io.InputStream)? = null,
        originalData: Any? = null,
    ): (() -> Any?)? {
        fun buffered(stream: (() -> java.io.InputStream)?): (() -> Any?)? {
            return stream?.let { source ->
                {
                    try {
                        source().use { input -> Buffer().readFrom(input) }
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }

        enhancementStreamOverride?.let { enhancedStream ->
            return buffered(enhancedStream)
        }

        readerPage?.let { page ->
            return buffered(page.stream) ?: page.imageUrl?.let { { it } }
        }

        return when (originalData) {
            is ReaderPage -> buffered(originalData.stream) ?: originalData.imageUrl?.let { { it } }
            null -> buffered(streamFn)
            else -> buffered(streamFn) ?: { originalData }
        }
    }

    private fun enqueueEnhancement(
        mId: Long,
        cId: Long,
        pIdx: Int,
        highPriority: Boolean,
        streamFn: (() -> java.io.InputStream)? = null,
        originalData: Any? = null,
    ) {
        val dataProvider = buildEnhancementDataProvider(streamFn, originalData) ?: return
        ImageEnhancer.enhanceLazy(
            context = context.applicationContext,
            mangaId = mId,
            chapterId = cId,
            pageIndex = pIdx,
            highPriority = highPriority,
            pageVariant = enhancementVariant(),
            dataProvider = dataProvider,
        )
    }

    fun promoteEnhancementRequest(highPriority: Boolean = true) {
        if (!realCuganEnabled) return

        val mId = readerPage?.chapter?.chapter?.manga_id ?: mangaId
        val cId = readerPage?.chapter?.chapter?.id ?: chapterId
        val pIdx = readerPage?.index ?: pageIndex
        if (pIdx < 0 || mId == -1L || cId == -1L) return

        ImageEnhancementCache.init(context)
        val configHash = ImageEnhancementCache.getConfigHash(preferences)
        val pageVariant = enhancementVariant()

        if (ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant) != null) return
        if (ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant)) return

        enqueueEnhancement(mId, cId, pIdx, highPriority)
    }

    private fun requeueEnhancement(
        mId: Long,
        cId: Long,
        pIdx: Int,
        triggerData: Any?,
        streamFn: (() -> java.io.InputStream)? = null,
        forceCurrentPage: Boolean = false,
    ) {
        val dataProvider = buildEnhancementDataProvider(streamFn, triggerData)

        if (dataProvider == null) {
            logcat(LogPriority.WARN) {
                "ReaderPageImageView: Unable to re-enqueue page $pIdx because source data is unavailable"
            }
            return
        }

        val isCurrent = forceCurrentPage || pIdx == currentGlobalPageIndex || pIdx == ImageEnhancer.targetPageIndex
        logcat(LogPriority.WARN) {
            "ReaderPageImageView: Re-enqueueing page $pIdx after invalid enhanced cache (current=$isCurrent)"
        }

        ImageEnhancer.enhanceLazy(
            context = context.applicationContext,
            mangaId = mId,
            chapterId = cId,
            pageIndex = pIdx,
            highPriority = isCurrent,
            pageVariant = enhancementVariant(),
            dataProvider = dataProvider,
        )
    }

    private suspend fun healInvalidEnhancedCache(
        mId: Long,
        cId: Long,
        pIdx: Int,
        configHash: String,
        triggerData: Any?,
        streamFn: (() -> java.io.InputStream)? = null,
        forceCurrentPage: Boolean = false,
    ) {
        val pageVariant = enhancementVariant()
        val removed = ImageEnhancementCache.removeCachedImage(mId, cId, pIdx, configHash, pageVariant)
        logcat(if (removed) LogPriority.WARN else LogPriority.ERROR) {
            "ReaderPageImageView: Invalid enhanced cache for page $pIdx/$pageVariant removed=$removed"
        }

        withUIContext {
            pageView?.alpha = 1f
            enhancedOverlay.setImageBitmap(null)
            enhancedOverlay.isVisible = false
            enhancedBitmap?.recycle()
            enhancedBitmap = null
            isSettingProcessedImage = false
            updateStatus(context.stringResource(KMR.strings.reader_status_processing))
        }

        requeueEnhancement(mId, cId, pIdx, triggerData, streamFn, forceCurrentPage)
    }
    // KMK <--

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        // KMK -->
        clearProcessedSwapView()
        // KMK <--
        removeView(pageView)

        pageView = createSubsamplingPageView()
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
        bringEnhancementStatusToFront()
    }

    // KMK -->
    private fun createSubsamplingPageView(): SubsamplingScaleImageView {
        // KMK <--
        return if (isWebtoon) {
            WebtoonSubsamplingImageView(context)
        } else {
            SubsamplingScaleImageView(context)
        }.apply {
            setMaxTileSize(ImageUtil.hardwareBitmapThreshold)
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        this@ReaderPageImageView.onScaleChanged(newScale)
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        // Not used
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }
        // KMK -->
    }
    // KMK <--

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        // KMK -->
        if (config?.disableZoomIn == true) {
            isZoomEnabled = false
        } else {
            if (config?.doubleTapZoom == false) {
                setDoubleTapZoomScale(scale)
            } else {
                // KMK <--
                setDoubleTapZoomScale(scale * 2)
            }
        }

        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
        streamFn: (() -> java.io.InputStream)? = null,
        allowEnhancement: Boolean = true,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        // KMK -->
        // Defer enhancement until the first original frame is displayed
        // (see notifyVisibleImageDisplayed); one callback covers every decode path below.
        pendingInitialEnhancement = if (allowEnhancement && realCuganEnabled && pageIndex >= 0 && mangaId != -1L) {
            {
                processImageHelper(
                    streamFn = streamFn,
                    originalData = (data as? BitmapDrawable)?.bitmap,
                )
            }
        } else {
            null
        }
        // KMK <--
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    setupZoom(config)
                    if (isVisibleOnScreen()) landscapeZoom(true)
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.onImageLoadError(e)
                }
            },
        )

        when (data) {
            is BitmapDrawable -> {
                setImageWithCurrentCrop(ImageSource.bitmap(data.bitmap))
                isVisible = true
            }
            is BufferedSource -> {
                if (!isWebtoon || alwaysDecodeLongStripWithSSIV) {
                    setHardwareConfig(ImageUtil.canUseHardwareBitmap(data))
                    setImageWithCurrentCrop(ImageSource.inputStream(data.inputStream()))
                    isVisible = true
                    return@apply
                }

                ImageRequest.Builder(context)
                    .data(data)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .target(
                        onSuccess = { result ->
                            val image = result as BitmapImage
                            setImage(ImageSource.bitmap(image.bitmap))
                            isVisible = true
                        },
                    )
                    .listener(
                        onError = { _, result ->
                            onImageLoadError(result.throwable)
                        },
                    )
                    .size(ViewSizeResolver(this@ReaderPageImageView))
                    .precision(Precision.INEXACT)
                    .cropBorders(config.cropBorders)
                    .customDecoder(true)
                    .crossfade(false)
                    .build()
                    .let(context.imageLoader::enqueue)
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    // KMK -->
    private fun SubsamplingScaleImageView.processImageHelper(
        streamFn: (() -> java.io.InputStream)? = null,
        originalData: Any? = null,
    ) {
        if (isSettingProcessedImage) {
            updateStatus(null)
            return
        }
        if (!realCuganEnabled || !enhancementDisplayEnabled) {
            updateStatus(context.stringResource(KMR.strings.reader_status_raw))
            return
        }

        // Initialize IDs from readerPage if available, otherwise use properties
        val mId = readerPage?.chapter?.chapter?.manga_id ?: mangaId
        val cId = readerPage?.chapter?.chapter?.id ?: chapterId
        val pIdx = readerPage?.index ?: pageIndex

        if (pIdx < 0 || mId == -1L || cId == -1L) {
            logcat(LogPriority.DEBUG) { "ReaderPageImageView: Skipping enhancement, invalid IDs (m=$mId, c=$cId, p=$pIdx)" }
            return
        }

        ImageEnhancementCache.init(context)
        val configHash = enhancementConfigHash()
        val pageVariant = enhancementVariant()
        val secondary = secondaryPage
        val secondaryFile = secondaryCachedFile(mId, configHash)

        if (secondary != null && isSecondarySkipped(mId, configHash)) {
            updateStatus(context.stringResource(KMR.strings.reader_status_raw))
            return
        }

        if (secondary == null || secondaryFile != null) {
            val cachedFile = ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant)
            if (cachedFile != null) {
                logcat(LogPriority.DEBUG) { "ReaderPageImageView: Page $pIdx found in cache on first check: ${cachedFile.absolutePath}" }
                // KMK --> The observer also handles an already-cached result. Never decode or
                // transform a cached page inside the UI frame callback, or reset the active
                // image while it is being drawn (upstream 1.3.17). Invalid entries go through
                // the poll's heal path; merged spreads were already delegated to the poll.
                startEnhancementPolling(mId, cId, pIdx, configHash, originalData, streamFn)
                return
                // KMK <--
            }
        }

        if (ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant)) {
            logcat(LogPriority.DEBUG) { "ReaderPageImageView: Page $pIdx marked as skipped, showing RAW" }
            updateStatus(context.stringResource(KMR.strings.reader_status_raw))
            return
        }

        logcat(LogPriority.DEBUG) { "ReaderPageImageView: Page $pIdx NOT in cache, starting monitoring (m=$mId, c=$cId, config=$configHash)" }

        // Trigger enhancement if it's not already in progress
        val triggerDataProvider = buildEnhancementDataProvider(streamFn, originalData)

        if (triggerDataProvider != null) {
            // Use High Priority if this is the current target page (the one user is viewing)
            // This ensures re-queued pages are processed immediately, not stuck in low priority
            val isCurrentPage = pIdx == ImageEnhancer.targetPageIndex
            val isInitialTargetPage = currentGlobalPageIndex < 0 && isCurrentPage
            val canEnqueueNow = currentGlobalPageIndex >= 0 || isInitialTargetPage

            if (canEnqueueNow) {
                logcat(LogPriority.DEBUG) { "ReaderPageImageView: Triggering enhancement for page $pIdx. isCurrentPage=$isCurrentPage (target=${ImageEnhancer.targetPageIndex})" }

                ImageEnhancer.enhanceLazy(
                    context = context.applicationContext,
                    mangaId = mId,
                    chapterId = cId,
                    pageIndex = pIdx,
                    highPriority = isCurrentPage,
                    pageVariant = pageVariant,
                    dataProvider = triggerDataProvider,
                )
            } else {
                logcat(LogPriority.DEBUG) {
                    "ReaderPageImageView: Delaying enhancement enqueue for page $pIdx until initial target page ${ImageEnhancer.targetPageIndex} starts"
                }
            }
        }

        // Merged spreads must enhance both halves so they can be merged for display.
        secondary?.let {
            enqueueSecondaryIfNeeded(mId, configHash, highPriority = pIdx == ImageEnhancer.targetPageIndex)
        }

        // Simplified polling for the enhanced image in cache
        startEnhancementPolling(mId, cId, pIdx, configHash, originalData, streamFn)
    }

    /**
     * Start or restart the polling job to monitor enhancement progress and update status.
     */
    private data class EnhancementPollKey(
        val revision: Int,
        val mangaId: Long,
        val chapterId: Long,
        val pageIndex: Int,
        val pageVariant: String,
        val configHash: String,
    )

    private fun startEnhancementPolling(
        mId: Long,
        cId: Long,
        pIdx: Int,
        configHash: String,
        originalData: Any? = null,
        streamFn: (() -> java.io.InputStream)? = null,
    ) {
        val key = EnhancementPollKey(imageRevision, mId, cId, pIdx, enhancementVariant(), configHash)
        if (processingKey == key && processingJob?.isActive == true) return
        processingJob?.cancel()
        processingKey = key
        processingJob = viewScope.launchIO {
            // KMK --> the variant captured in the key identifies this job; recomputing it
            // live could diverge from the key the dedupe check compares against.
            val pageVariant = key.pageVariant
            // KMK <--
            val secondary = secondaryPage
            // KMK --> a merged spread waits on both halves; affects() only matches the
            // primary key, so the sibling half's Saved event could never wake this poll
            // and the swap waited on the fallback pass below. Watch the sibling key too.
            // Declared above the try so its finally can stop the producer.
            val secondaryKey = secondary?.chapter?.chapter?.id?.let { secCid ->
                ImageEnhancementCache.CacheKey(mId, secCid, secondary.index, configHash, variantOf(secondary))
            }
            val cacheEvents = ImageEnhancementCache.events
                .filter { it.affects(mId, cId, pIdx, configHash, pageVariant) || it.key == secondaryKey }
                .produceIn(this)
            // KMK <--
            try {
                var wasEnhancing = false
                // KMK --> bounded so a page that cannot be enhanced (for example an NPU model
                // that failed to compile) is not decoded again on every poll pass.
                var healAttempts = 0
                // The degraded-raw state persists while the page stays outside the heal
                // window, so it is logged once per poll instead of every 500 ms pass.
                var degradedLogged = false
                // KMK <--
                var shouldCheckCache = true
                // The queue may legitimately take longer than a minute for a tall webtoon page
                // or a spread waiting on its sibling half. Keep observing while this frame is
                // attached; onDetachedFromWindow cancels this job when it is no longer useful.
                while (isActive) {
                    if (shouldCheckCache) {
                        shouldCheckCache = false
                        if (ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant)) {
                            withUIContext { updateStatus(context.stringResource(KMR.strings.reader_status_raw)) }
                            return@launchIO
                        }
                        if (secondary != null && isSecondarySkipped(mId, configHash)) {
                            withUIContext { updateStatus(context.stringResource(KMR.strings.reader_status_raw)) }
                            return@launchIO
                        }
                        val secondaryFile = secondaryCachedFile(mId, configHash)
                        val file = ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant)
                        if (file != null && (secondary == null || secondaryFile != null)) {
                            logcat(LogPriority.DEBUG) { "ReaderPageImageView: Page $pIdx/$pageVariant found in cache after cache event: ${file.absolutePath}" }
                            // KMK --> Completion notifications can be lost at a timeout boundary:
                            // when the cached file is already on screen, only the status label
                            // needs recovering; skip the decode entirely (upstream 1.3.17).
                            val alreadyDisplayed = withUIContext {
                                if (key.revision == imageRevision && currentLoadedUri == file.toURI().toString()) {
                                    updateStatus(enhancementBackendName())
                                    true
                                } else {
                                    false
                                }
                            }
                            if (alreadyDisplayed) return@launchIO
                            // KMK <--
                            val transformedSource = buildEnhancedDisplaySource(file, secondaryFile)
                            if (transformedSource != null) {
                                withUIContext {
                                    // KMK --> a stale revision must not swap the image of a
                                    // recycled or rebound view (upstream 1.3.17).
                                    if (key.revision == imageRevision) {
                                        setProcessedSource(file, transformedSource = transformedSource)
                                    } else {
                                        transformedSource.close()
                                    }
                                    // KMK <--
                                }
                                return@launchIO
                            }
                            if (secondaryFile != null) {
                                // Cannot merge the enhanced halves - keep showing the original spread.
                                withUIContext { updateStatus(context.stringResource(KMR.strings.reader_status_raw)) }
                                return@launchIO
                            }

                            val source = ImageEnhancementCache.readCachedImage(file)

                            if (source != null) {
                                withUIContext {
                                    // KMK --> same revision guard as above.
                                    if (key.revision == imageRevision) {
                                        setProcessedSource(file, transformedSource = source)
                                    } else {
                                        source.close()
                                    }
                                    // KMK <--
                                }
                                return@launchIO
                            } else {
                                healInvalidEnhancedCache(
                                    mId = mId,
                                    cId = cId,
                                    pIdx = pIdx,
                                    configHash = configHash,
                                    triggerData = originalData,
                                    streamFn = streamFn,
                                    forceCurrentPage = pIdx == currentGlobalPageIndex,
                                )
                            }
                        }
                    }

                    // Check progress status
                    val pid = Waifu2x.getProgressId()
                    val requested = ImageEnhancer.hasRequest(mId, cId, pIdx, pageVariant) || isSecondaryRequested(mId)
                    // KMK --> Phase-aware label for the actively processed page: PREPARING while
                    // the model/runtime is being readied, ENHANCING with live progress, SAVING
                    // while the result is encoded into the cache; null when the attempt was just
                    // cancelled so the generic labels take over (upstream 1.3.17).
                    val phaseLabel = context.activeEnhancementLabel(mId, cId, pIdx, pageVariant)
                    if (phaseLabel != null) {
                        wasEnhancing = true
                        updateStatus(phaseLabel)
                    } else if (pid == pIdx || (secondary != null && pid == secondary.index)) {
                        // KMK <--
                        wasEnhancing = true
                        // Actively enhancing: show pure percentage progress, refreshed by
                        // the poll; the number itself moving signals activity.
                        val progress = Waifu2x.getProgressPercent().coerceIn(0, 100)
                        updateStatus(context.stringResource(KMR.strings.reader_status_enhancing_progress, progress))
                    } else if (requested && wasEnhancing) {
                        // KMK --> Reverts d68236e31: the tail after active inference (a request
                        // still registered once the progress id moved on) is its own FINISHING
                        // stage again, not a continuation of the enhancing label. The SAVING
                        // phase label above already covers the decode-side save window; this
                        // branch covers the post-active window while the cache write settles.
                        updateStatus(context.stringResource(KMR.strings.reader_status_finishing))
                        // KMK <--
                    } else if (requested) {
                        updateStatus(context.stringResource(KMR.strings.reader_status_queued))
                    } else {
                        // KMK -->
                        // The request left the queue without this poll seeing its Saved/Skipped
                        // event: the poll subscribes after the request is enqueued, and in
                        // double-page modes a sibling holder watches the same key. Cache checks
                        // are otherwise event-driven only, so a finished result could sit in
                        // the cache until a page change re-ran onPageSelected. Re-check the
                        // cache before treating this as a failure.
                        val cachedFileNow = ImageEnhancementCache.getCachedImage(mId, cId, pIdx, configHash, pageVariant)
                        val secondaryFileNow = secondaryCachedFile(mId, configHash)
                        if (cachedFileNow != null && (secondary == null || secondaryFileNow != null)) {
                            val transformedSource = buildEnhancedDisplaySource(cachedFileNow, secondaryFileNow)
                            if (transformedSource != null) {
                                withUIContext {
                                    // KMK --> stale revision must not swap a recycled view's image.
                                    if (key.revision == imageRevision) {
                                        setProcessedSource(cachedFileNow, transformedSource = transformedSource)
                                    } else {
                                        transformedSource.close()
                                    }
                                    // KMK <--
                                }
                                return@launchIO
                            }
                            if (secondaryFileNow != null) {
                                // Cannot merge the enhanced halves - keep showing the original spread.
                                logcat(LogPriority.WARN) { "ReaderPageImageView: Fallback cannot merge page $pIdx" }
                                withUIContext { updateStatus(context.stringResource(KMR.strings.reader_status_raw)) }
                                return@launchIO
                            }
                            val source = ImageEnhancementCache.readCachedImage(cachedFileNow)
                            if (source != null) {
                                withUIContext {
                                    // KMK --> same revision guard as above.
                                    if (key.revision == imageRevision) {
                                        setProcessedSource(cachedFileNow, transformedSource = source)
                                    } else {
                                        source.close()
                                    }
                                    // KMK <--
                                }
                                return@launchIO
                            }
                            logcat(LogPriority.WARN) { "ReaderPageImageView: Fallback invalid entry page $pIdx" }
                            // The entry failed validation: drop it so the re-enqueue below
                            // rebuilds it instead of being rejected as already-cached.
                            healInvalidEnhancedCache(
                                mId = mId,
                                cId = cId,
                                pIdx = pIdx,
                                configHash = configHash,
                                triggerData = originalData,
                                streamFn = streamFn,
                                forceCurrentPage = pIdx == currentGlobalPageIndex,
                            )
                        }
                        // KMK --> half-missing recoveries: the spread is one enhancement away
                        // from being swappable in either direction, so re-queue just the missing
                        // half (bounded) and keep waiting. Exiting here would strand the spread:
                        // the heal window keys on the primary index and legitimately rejects it
                        // while the reader is still scrolling toward this spread, and a completed
                        // half's Saved event must not be lost. A degraded poll keeps observing
                        // while attached; onDetachedFromWindow is what stops it.
                        // Read the sibling from the live property rather than the poll-start
                        // snapshot: a split can clear it after this job started, and the cache
                        // helpers below already resolve it live. Mixing the two sources could
                        // re-queue a sibling that no longer exists and burn a heal attempt.
                        val secondaryNow = secondaryPage
                        if (cachedFileNow != null && secondaryNow != null && secondaryFileNow == null) {
                            if (isSecondarySkipped(mId, configHash) || healAttempts >= MAX_HEAL_ATTEMPTS) {
                                withUIContext { updateStatus(context.stringResource(KMR.strings.reader_status_raw)) }
                            } else {
                                healAttempts++
                                logcat(LogPriority.WARN) {
                                    "ReaderPageImageView: Re-queueing sibling page ${secondaryNow.index} (primary of $pIdx cached)"
                                }
                                enqueueSecondaryIfNeeded(
                                    mangaId = mId,
                                    configHash = configHash,
                                    highPriority = ImageEnhancer.isFocusedTarget(secondaryNow.index, variantOf(secondaryNow)),
                                )
                            }
                        } else if (cachedFileNow == null && secondaryNow != null && secondaryFileNow != null) {
                            if (ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant) || healAttempts >= MAX_HEAL_ATTEMPTS) {
                                withUIContext { updateStatus(context.stringResource(KMR.strings.reader_status_raw)) }
                            } else {
                                healAttempts++
                                logcat(LogPriority.WARN) {
                                    "ReaderPageImageView: Re-queueing primary page $pIdx (sibling ${secondaryNow.index} cached)"
                                }
                                enqueueEnhancement(
                                    mId = mId,
                                    cId = cId,
                                    pIdx = pIdx,
                                    highPriority = ImageEnhancer.isFocusedTarget(pIdx, pageVariant),
                                    originalData = originalData,
                                    streamFn = streamFn,
                                )
                            }
                        } else if (cachedFileNow == null) {
                            // Not in queue, not cached - bounded windowed re-enqueue
                            val current = currentGlobalPageIndex
                            val shouldHeal = pIdx >= current && pIdx <= current + preloadSize
                            val canRetry = healAttempts < MAX_HEAL_ATTEMPTS
                            if (shouldHeal && canRetry && !ImageEnhancementCache.isSkipped(mId, cId, pIdx, configHash, pageVariant)) {
                                healAttempts++
                                logcat(LogPriority.WARN) { "ReaderPageImageView: Polling re-enqueue page $pIdx/$pageVariant (cur=$current)" }
                                // Only the focused variant may jump the queue; other variants sharing
                                // the page index must not monopolize the high-priority queue.
                                val isCurrent = ImageEnhancer.isFocusedTarget(pIdx, pageVariant)
                                enqueueEnhancement(mId, cId, pIdx, highPriority = isCurrent, originalData = originalData, streamFn = streamFn)
                                secondaryNow?.let { enqueueSecondaryIfNeeded(mId, configHash, highPriority = isCurrent) }
                            } else {
                                // A page behind the heal window stays degraded for as long as
                                // it is attached, so only the transition is a warning; the
                                // repeated passes would otherwise flood the log every 500 ms.
                                if (!degradedLogged) {
                                    degradedLogged = true
                                    logcat(LogPriority.WARN) {
                                        "ReaderPageImageView: Poll degraded to raw page $pIdx (heal window failed, cur=$current, preload=$preloadSize)"
                                    }
                                }
                                withUIContext { updateStatus(context.stringResource(KMR.strings.reader_status_raw)) }
                            }
                        }
                        // KMK <--
                    }

                    val event = withTimeoutOrNull(500) {
                        cacheEvents.receive()
                    }
                    shouldCheckCache = event is ImageEnhancementCache.CacheEvent.Saved ||
                        event is ImageEnhancementCache.CacheEvent.Skipped
                }
            } catch (_: CancellationException) {
                return@launchIO
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Error in polling for page $pIdx" }
            } catch (e: OutOfMemoryError) {
                // KMK --> Enhancement is optional; retain the original image when the
                // cached replacement is too large for this device (upstream 1.3.17).
                logcat(LogPriority.ERROR, e) { "ReaderPageImageView: Not enough memory for enhanced page $pIdx" }
                withUIContext { updateStatus(context.stringResource(KMR.strings.reader_status_raw)) }
                // KMK <--
            } finally {
                // KMK --> The shared event flow never completes; its producer must not
                // outlive the finished observer (upstream 1.3.17).
                cacheEvents.cancel()
                // KMK <--
            }
        }
    }
    // KMK <--

    // KMK -->
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        imageRevision++
        currentCachedFile = null
        processingJob?.cancel()
        processingJob = null

        enhancedOverlay.setImageBitmap(null)
        enhancedBitmap?.recycle()
        enhancedBitmap = null
        isSettingProcessedImage = false
        currentLoadedUri = null
        clearProcessedSwapView()

        preferenceJobs.forEach { it.cancel() }
        preferenceJobs = emptyList()
        viewScope.cancel()
    }
    // KMK <--

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
        bringEnhancementStatusToFront()
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
            )
            .listener(
                onError = { _, result ->
                    onImageLoadError(result.throwable)
                },
            )
            .crossfade(false)
            // KMK -->
            .allowHardware(false) // Disable hardware bitmaps for GIFs
            // KMK <--
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
        // KMK -->
        val disableZoomIn: Boolean = false,
        val doubleTapZoom: Boolean = true,
        val landscapeZoomScaleType: LandscapeZoomScaleType = LandscapeZoomScaleType.FIT,
        // KMK <--
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }

    companion object {
        // KMK -->
        /** Page index of the page currently shown, used by enhancement jobs to self-heal. */
        @Volatile var currentGlobalPageIndex: Int = -1
        // KMK <--
    }
}

private const val MAX_ZOOM_SCALE = 5F

// KMK -->
private const val PROCESSED_SWAP_DURATION_MS = 280L

/**
 * Upper bound on poll-driven re-enqueues per image binding. A failed attempt that
 * writes neither a cache entry nor a skip marker would otherwise be retried on every
 * poll pass, decoding the whole page each time.
 */
private const val MAX_HEAL_ATTEMPTS = 2
// KMK <--
