package eu.kanade.tachiyomi.data.coil

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.archive.CbzCrypto
import mihon.core.archive.CbzCrypto.getCoverStream
import mihon.core.archive.archiveReader
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A [Decoder] that uses built-in [ImageDecoder] to decode images that is not supported by the system.
 * KMK: It also handles on-the-fly image enhancement via Waifu2x models.
 */
class TachiyomiImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {
    private val context = Injekt.get<Application>()

    override suspend fun decode(): DecodeResult? {
        decodeCachedEnhancedImage()?.let { cachedBitmap ->
            return DecodeResult(
                image = prepareForDisplay(cachedBitmap).asImage(),
                isSampled = false,
            )
        }

        return resources.source().use { source ->
            // KMK --> ownership is explicit: the decoded bitmap is handed to Coil on
            // success and released by the finally below on every other exit path.
            var bitmap: Bitmap? = null
            var transferred = false
            try {
                var sampleSize = 1

                // KMK --> enhanced bitmap produced by the on-the-fly path, returned below
                var enhancedResult: Bitmap? = null
                // KMK <--

                // KMK --> Enhancement must see the source resolution. Sampling to the reader's
                // display size first permanently discards detail before the model runs.
                val decodeAtSourceResolution = options.enhanced &&
                    options.mangaId != -1L &&
                    options.chapterId != -1L &&
                    options.pageIndex != -1 &&
                    runCatching { Injekt.get<ReaderPreferences>().realCuganEnabled().get() }
                        .getOrDefault(false)
                // KMK <--

                // 1. Attempt decoding with native ImageDecoder (for AVIF/JXL/HEIF)
                bitmap = run {
                    // SY -->
                    var coverStream: BufferedInputStream? = null
                    if (resources.sourceOrNull()?.peek()?.use { CbzCrypto.detectCoverImageArchive(it.inputStream()) } == true) {
                        if (resources.source().peek().use { ImageUtil.findImageType(it.inputStream()) == null }) {
                            coverStream = UniFile.fromFile(resources.file().toFile())
                                ?.archiveReader(context = context)
                                ?.getCoverStream()
                        }
                    }
                    // SY <--
                    val nativeDecoder = try {
                        ImageDecoder.newInstance(
                            // SY -->
                            // KMK --> Keep the original unread so a failed native decode can use BitmapFactory.
                            coverStream ?: source.peek().inputStream(),
                            // KMK <--
                            // SY <--
                            options.cropBorders,
                            displayProfile,
                        )
                    } catch (e: Exception) {
                        null
                    }

                    if (nativeDecoder == null || nativeDecoder.width <= 0 || nativeDecoder.height <= 0) {
                        return@run null
                    }

                    try {
                        val srcWidth = nativeDecoder.width
                        val srcHeight = nativeDecoder.height
                        val dstWidth = options.size.widthPx(options.scale) { srcWidth }
                        val dstHeight = options.size.heightPx(options.scale) { srcHeight }

                        sampleSize = if (decodeAtSourceResolution) {
                            1
                        } else {
                            DecodeUtils.calculateInSampleSize(
                                srcWidth = srcWidth,
                                srcHeight = srcHeight,
                                dstWidth = dstWidth,
                                dstHeight = dstHeight,
                                scale = options.scale,
                            )
                        }
                        nativeDecoder.decode(sampleSize = sampleSize)
                    } finally {
                        nativeDecoder.recycle()
                    }
                }

                // 2. Fallback to BitmapFactory for system-supported formats (JPG, PNG, WEBP, etc.)
                if (bitmap == null) {
                    try {
                        val byteBuf = source.peek().readByteArray()
                        val ops = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(byteBuf, 0, byteBuf.size, ops)

                        if (ops.outWidth > 0 && ops.outHeight > 0) {
                            val srcWidth = ops.outWidth
                            val srcHeight = ops.outHeight
                            val dstWidth = options.size.widthPx(options.scale) { srcWidth }
                            val dstHeight = options.size.heightPx(options.scale) { srcHeight }

                            sampleSize = if (decodeAtSourceResolution) {
                                1
                            } else {
                                DecodeUtils.calculateInSampleSize(
                                    srcWidth = srcWidth,
                                    srcHeight = srcHeight,
                                    dstWidth = dstWidth,
                                    dstHeight = dstHeight,
                                    scale = options.scale,
                                )
                            }

                            val decodeOps = BitmapFactory.Options().apply {
                                inSampleSize = sampleSize
                                inPreferredConfig = if (options.bitmapConfig == Bitmap.Config.HARDWARE) {
                                    Bitmap.Config.ARGB_8888 // Decode to software first
                                } else {
                                    options.bitmapConfig
                                }
                            }
                            bitmap = BitmapFactory.decodeByteArray(byteBuf, 0, byteBuf.size, decodeOps)
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: BitmapFactory fallback failed" }
                    }
                }

                if (bitmap == null) {
                    logcat(LogPriority.ERROR) { "TachiyomiImageDecoder: Failed to decode bitmap via all methods" }
                    return@use null
                }

                // KMK -->
                logcat(LogPriority.DEBUG) {
                    "TachiyomiImageDecoder: decoded ${bitmap.width}x${bitmap.height}, " +
                        "sampleSize=$sampleSize, sourceResolution=$decodeAtSourceResolution"
                }
                // KMK <--

                // KMK --> --- Enhancement Integration ---
                if (options.enhanced) {
                    val preferences = Injekt.get<ReaderPreferences>()
                    if (preferences.realCuganEnabled().get()) {
                        val mangaId = options.mangaId
                        val chapterId = options.chapterId
                        val pageIndex = options.pageIndex
                        val pageVariant = options.pageVariant

                        if (mangaId != -1L && chapterId != -1L && pageIndex != -1) {
                            ImageEnhancementCache.init(context)

                            val configHash = ImageEnhancementCache.getConfigHash(preferences)

                            // Check cache first
                            var usedCache = false
                            val cachedFile = ImageEnhancementCache.getCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant)
                            if (cachedFile != null) {
                                try {
                                    val cachedBitmap = ImageEnhancementCache.decodeCachedImage(cachedFile)
                                    if (cachedBitmap != null && ImageEnhancementCache.isDisplayable(cachedBitmap)) {
                                        bitmap.recycle()
                                        bitmap = cachedBitmap
                                        usedCache = true
                                    } else {
                                        cachedBitmap?.recycle()
                                        ImageEnhancementCache.removeCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant)
                                        logcat(LogPriority.WARN) { "TachiyomiImageDecoder: Removed invalid enhanced cache for page $pageIndex/$pageVariant" }
                                    }
                                } catch (e: Exception) {
                                    logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: Failed to decode cached enhanced image" }
                                }
                            }

                            if (!usedCache) {
                                // Not in cache or decode failed, perform enhancement on-the-fly
                                try {
                                    val model = preferences.realCuganModel().get()
                                    val realEsrganStyle = preferences.realEsrganStyle().get()
                                    val noise = preferences.realCuganNoiseLevel().get()
                                    val scale = preferences.realCuganScale().get()

                                    // --- Resolution Limits / Prescale ---
                                    val processMaxWidth = preferences.realCuganMaxSizeWidth().get()
                                    val processMaxHeight = preferences.realCuganMaxSizeHeight().get()
                                    val skipMaxWidth = preferences.realCuganSkipMaxSizeWidth().get()
                                    val skipMaxHeight = preferences.realCuganSkipMaxSizeHeight().get()
                                    var shouldSkipEnhancement = false

                                    val exceedsSkipLimit =
                                        (skipMaxWidth > 0 && bitmap.width > skipMaxWidth) ||
                                            (skipMaxHeight > 0 && bitmap.height > skipMaxHeight)

                                    if (exceedsSkipLimit) {
                                        logcat(LogPriority.DEBUG) {
                                            "TachiyomiImageDecoder: Skipping enhancement for page $pageIndex - source ${bitmap.width}x${bitmap.height} exceeds max resolution ${skipMaxWidth}x$skipMaxHeight"
                                        }
                                        // KMK --> the skip marker is written once at the inference
                                        // decision below, which also covers the NPU-unsupported path.
                                        shouldSkipEnhancement = true
                                        // KMK <--
                                    }

                                    // --- Performance Mode ---
                                    val perfMode = preferences.realCuganPerformanceMode().get()
                                    val tileSleepMs = when (perfMode) {
                                        1 -> 5
                                        2 -> 15
                                        else -> 0
                                    }
                                    val tileSize = preferences.realCuganTileSize().get().coerceAtLeast(32)
                                    val precision =
                                        preferences.realCuganPrecision().get().takeIf { it == 0 || it == 1 || it == 3 }
                                            ?: 0
                                    val fp16Arithmetic = preferences.realCuganFp16Arithmetic().get()

                                    // Validate scale based on model capabilities
                                    val effectiveScale = ImageEnhancementCache.getEffectiveScale(model, scale, realEsrganStyle)
                                    val processingBackend = Waifu2x.resolveProcessingBackend(
                                        preferences.realCuganProcessingBackend().get(),
                                        model,
                                        effectiveScale,
                                    )
                                    // KMK --> an explicitly selected NPU only runs models with packaged
                                    // NPU support; anything else keeps the original image.
                                    if (processingBackend != Waifu2x.PROCESSING_BACKEND_VULKAN &&
                                        !Waifu2x.isQualcommNpuModelSupported(model, effectiveScale)
                                    ) {
                                        shouldSkipEnhancement = true
                                    }
                                    // KMK <--
                                    if (effectiveScale != scale) {
                                        logcat(LogPriority.DEBUG) { "TachiyomiImageDecoder: Model $model only supports ${effectiveScale}x, clamping from ${scale}x" }
                                    }

                                    // KMK --> prescaling produces a temporary input bitmap; the
                                    // KMK --> original stays intact for display when enhancement fails.
                                    var enhancementInput = bitmap
                                    try {
                                        if (!shouldSkipEnhancement) {
                                            val hasProcessMaxResolution = processMaxWidth > 0 || processMaxHeight > 0
                                            val widthRatio = if (processMaxWidth > 0) {
                                                processMaxWidth / bitmap.width.toFloat()
                                            } else {
                                                Float.POSITIVE_INFINITY
                                            }
                                            val heightRatio = if (processMaxHeight > 0) {
                                                processMaxHeight / bitmap.height.toFloat()
                                            } else {
                                                Float.POSITIVE_INFINITY
                                            }
                                            val maxResolutionRatio = if (hasProcessMaxResolution) {
                                                min(widthRatio, heightRatio)
                                            } else {
                                                1f
                                            }
                                            val ratio = maxResolutionRatio

                                            if (ratio in 0f..<1f) {
                                                val newWidth = max(1, (bitmap.width * ratio).roundToInt())
                                                val newHeight = max(1, (bitmap.height * ratio).roundToInt())
                                                logcat(LogPriority.DEBUG) {
                                                    "TachiyomiImageDecoder: Prescaling page $pageIndex input with native scaling ${bitmap.width}x${bitmap.height} -> ${newWidth}x$newHeight, processingMax=${processMaxWidth}x$processMaxHeight, outputScale=${effectiveScale}x"
                                                }
                                                val scaledBitmap = nativeScaleBitmap(bitmap, newWidth, newHeight)
                                                if (scaledBitmap != bitmap) {
                                                    enhancementInput = scaledBitmap
                                                }
                                            }
                                        }
                                        // --- End Resolution Limits / Prescale ---

                                        if (!shouldSkipEnhancement) {
                                            // KMK --> inference (init + process) runs on the dedicated
                                            // enhance dispatcher; the decoder thread stays free.
                                            withContext(enhanceDispatcher) {
                                                currentCoroutineContext().ensureActive()
                                                val initialized = when (model) {
                                                    0 -> Waifu2x.initRealCugan(context, noise, effectiveScale, isPro = false, tileSleepMs = tileSleepMs, tileSize = tileSize, precision = precision, fp16Arithmetic = fp16Arithmetic, processingBackend = processingBackend)
                                                    1 -> Waifu2x.initRealCugan(context, noise, effectiveScale, isPro = true, tileSleepMs = tileSleepMs, tileSize = tileSize, precision = precision, fp16Arithmetic = fp16Arithmetic, processingBackend = processingBackend)
                                                    Waifu2x.MODEL_REAL_ESRGAN_ANIME -> Waifu2x.initRealESRGAN(context, effectiveScale, style = realEsrganStyle, tileSleepMs = tileSleepMs, tileSize = tileSize, precision = precision, fp16Arithmetic = fp16Arithmetic, processingBackend = processingBackend)
                                                    3 -> Waifu2x.initNose(context, tileSleepMs = tileSleepMs, tileSize = tileSize, precision = precision, fp16Arithmetic = fp16Arithmetic)
                                                    4 -> Waifu2x.initWaifu2x(context, noise, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize, precision = precision, fp16Arithmetic = fp16Arithmetic)
                                                    5 -> Waifu2x.initWaifu2xUpconv7(context, noise, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize, precision = precision, fp16Arithmetic = fp16Arithmetic)
                                                    else -> if (Waifu2x.isW2xExModel(model)) {
                                                        Waifu2x.initW2xEx(context, model, scale = effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize, precision = precision, fp16Arithmetic = fp16Arithmetic, processingBackend = processingBackend)
                                                    } else {
                                                        Waifu2x.initRealCugan(context, noise, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize, precision = precision, fp16Arithmetic = fp16Arithmetic, processingBackend = processingBackend)
                                                    }
                                                }
                                                val processed = if (initialized) {
                                                    // KMK --> the reader labels this stage "enhancing"
                                                    // with live progress once native work starts.
                                                    currentCoroutineContext().ensureActive()
                                                    ImageEnhancer.updateProcessingPhase(
                                                        mangaId,
                                                        chapterId,
                                                        pageIndex,
                                                        pageVariant,
                                                        ImageEnhancer.ProcessingPhase.ENHANCING,
                                                    )
                                                    // KMK <--
                                                    when (model) {
                                                        0, 1 -> Waifu2x.processRealCugan(enhancementInput, pageIndex)
                                                        Waifu2x.MODEL_REAL_ESRGAN_ANIME -> Waifu2x.processRealESRGAN(enhancementInput, pageIndex)
                                                        3 -> Waifu2x.processNose(enhancementInput, pageIndex)
                                                        4, 5 -> Waifu2x.processWaifu2x(enhancementInput, pageIndex)
                                                        else -> if (Waifu2x.isW2xExModel(model)) {
                                                            Waifu2x.processW2xEx(enhancementInput, pageIndex)
                                                        } else {
                                                            Waifu2x.processRealCugan(enhancementInput, pageIndex)
                                                        }
                                                    }
                                                } else {
                                                    null
                                                }

                                                if (processed != null) {
                                                    var result: Bitmap = processed
                                                    var ownsResult = true
                                                    try {
                                                        currentCoroutineContext().ensureActive()

                                                        // --- Output Resolution Limit (prevent Canvas errors) ---
                                                        val textureLimit = eu.kanade.tachiyomi.util.system.GLUtil.DEVICE_TEXTURE_LIMIT

                                                        if (result.width > textureLimit || result.height > textureLimit) {
                                                            val widthRatio = textureLimit.toFloat() / result.width
                                                            val heightRatio = textureLimit.toFloat() / result.height
                                                            val ratio = min(widthRatio, heightRatio)

                                                            val newWidth = (result.width * ratio).toInt().coerceAtLeast(1)
                                                            val newHeight = (result.height * ratio).toInt().coerceAtLeast(1)

                                                            logcat(LogPriority.DEBUG) { "TachiyomiImageDecoder: Output downscale page $pageIndex: ${result.width}x${result.height} -> ${newWidth}x$newHeight (Texture Limit: $textureLimit)" }
                                                            val downscaled = nativeScaleBitmap(result, newWidth, newHeight)
                                                            if (downscaled != result) {
                                                                result.recycle()
                                                                result = downscaled
                                                            }
                                                        }
                                                        // --- End Output Resolution Limit ---

                                                        if (ImageEnhancementCache.isDisplayable(result)) {
                                                            // KMK --> the enhanced bitmap is handed to the display, so
                                                            // the cache writer receives its own copy and encodes it
                                                            // off the inference thread instead of blocking it.
                                                            ownsResult = false
                                                            // KMK --> the reader labels the encode hand-off "finishing".
                                                            ImageEnhancer.updateProcessingPhase(
                                                                mangaId,
                                                                chapterId,
                                                                pageIndex,
                                                                pageVariant,
                                                                ImageEnhancer.ProcessingPhase.SAVING,
                                                            )
                                                            // KMK <--
                                                            // Settled before the enqueue can suspend, so a
                                                            // cancellation still hands the bitmap back for recycling.
                                                            enhancedResult = result
                                                            val cacheBitmap = try {
                                                                result.copy(Bitmap.Config.ARGB_8888, false)
                                                            } catch (e: Exception) {
                                                                logcat(LogPriority.WARN, e) {
                                                                    "TachiyomiImageDecoder: Unable to copy page $pageIndex/$pageVariant for caching"
                                                                }
                                                                null
                                                            }
                                                            if (cacheBitmap != null) {
                                                                val queued = ImageEnhancementCache.enqueueSaveToCache(
                                                                    mangaId,
                                                                    chapterId,
                                                                    pageIndex,
                                                                    configHash,
                                                                    cacheBitmap,
                                                                    pageVariant,
                                                                )
                                                                if (!queued) {
                                                                    logcat(LogPriority.WARN) { "TachiyomiImageDecoder: Page $pageIndex/$pageVariant enhanced result not cached" }
                                                                }
                                                            }
                                                        } else {
                                                            logcat(LogPriority.ERROR) { "TachiyomiImageDecoder: Page $pageIndex/$pageVariant produced a nearly transparent result, keeping original image" }
                                                        }
                                                    } finally {
                                                        if (ownsResult && result !== bitmap && !result.isRecycled) result.recycle()
                                                    }
                                                } else {
                                                    // KMK --> A cancelled native call also returns null, so cancel
                                                    // first: only a real initialization/inference failure is
                                                    // remembered as skipped, otherwise polling could retry a
                                                    // dead request forever (upstream 1.3.17).
                                                    currentCoroutineContext().ensureActive()
                                                    markEnhancementSkipped()
                                                    logcat(LogPriority.WARN) {
                                                        "TachiyomiImageDecoder: Enhancement unavailable for page $pageIndex/$pageVariant; keeping original"
                                                    }
                                                    // KMK <--
                                                }
                                            } // KMK --> end withContext(enhanceDispatcher)
                                        } else {
                                            // KMK --> skipped pages (size limit, unsupported NPU model) are
                                            // remembered so polling reports RAW instead of retrying forever.
                                            markEnhancementSkipped()
                                            // KMK <--
                                        }
                                    } finally {
                                        // KMK --> the prescaled input is temporary; the original bitmap
                                        // KMK --> is retained for display whenever no enhanced result was produced.
                                        if (enhancementInput !== bitmap && !enhancementInput.isRecycled) {
                                            enhancementInput.recycle()
                                        }
                                    }
                                    // KMK <--
                                } catch (e: CancellationException) {
                                    enhancedResult?.let { if (!it.isRecycled) it.recycle() }
                                    enhancedResult = null
                                    throw e
                                } catch (e: Exception) {
                                    // KMK --> a failed enhancement attempt is remembered as skipped;
                                    // polling then reports RAW instead of re-queueing the page.
                                    markEnhancementSkipped()
                                    // KMK <--
                                    logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: Failed to enhance image on-the-fly" }
                                }
                            }
                        }
                    }
                }
                // KMK <-- --- End Enhancement Integration ---

                // KMK --> hand back the enhanced image when the on-the-fly path produced one;
                // prepareForDisplay takes ownership of whichever bitmap it receives.
                val enhanced = enhancedResult
                enhancedResult = null
                if (enhanced != null) {
                    if (enhanced !== bitmap) bitmap?.recycle()
                    bitmap = enhanced
                }

                bitmap = prepareForDisplay(bitmap)

                transferred = true
                DecodeResult(
                    image = bitmap.asImage(),
                    isSampled = sampleSize > 1,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: Critical failure during decode" }
                null
            } catch (e: OutOfMemoryError) {
                // KMK --> decoding is optional: record the page as skipped and let the
                // reader keep its previous image instead of crashing the process.
                markEnhancementSkipped()
                logcat(LogPriority.ERROR, e) {
                    "TachiyomiImageDecoder: Not enough memory; keeping original reader image"
                }
                null
                // KMK <--
            } finally {
                // KMK --> nothing outlives this call: on any failure or cancellation the
                // decoded bitmap is released here instead of leaking until GC.
                if (!transferred) {
                    bitmap?.let { if (!it.isRecycled) it.recycle() }
                }
                // KMK <--
            }
        }
    }

    // KMK -->
    /**
     * Persists a "skipped" marker for this page so status polling and retry heuristics treat
     * the enhancement as intentionally unavailable instead of endlessly re-requesting it.
     * Non-reader requests (covers, previews) carry no reader metadata and are ignored.
     */
    private fun markEnhancementSkipped() {
        if (!options.enhanced || options.mangaId == -1L || options.chapterId == -1L || options.pageIndex < 0) return
        val preferences = Injekt.get<ReaderPreferences>()
        ImageEnhancementCache.saveSkippedToCache(
            options.mangaId,
            options.chapterId,
            options.pageIndex,
            ImageEnhancementCache.getConfigHash(preferences),
            options.pageVariant,
        )
    }
    // KMK <--

    // KMK -->
    private fun decodeCachedEnhancedImage(): Bitmap? {
        if (!options.enhanced) return null

        val mangaId = options.mangaId
        val chapterId = options.chapterId
        val pageIndex = options.pageIndex
        if (mangaId == -1L || chapterId == -1L || pageIndex == -1) return null

        val preferences = Injekt.get<ReaderPreferences>()
        if (!preferences.realCuganEnabled().get()) return null

        ImageEnhancementCache.init(context)
        val configHash = ImageEnhancementCache.getConfigHash(preferences)
        val cachedFile = ImageEnhancementCache.getCachedImage(
            mangaId,
            chapterId,
            pageIndex,
            configHash,
            options.pageVariant,
        ) ?: return null

        val cachedBitmap = ImageEnhancementCache.decodeCachedImage(cachedFile)
        if (cachedBitmap != null && ImageEnhancementCache.isDisplayable(cachedBitmap)) {
            logcat(LogPriority.DEBUG) {
                "TachiyomiImageDecoder: Page $pageIndex/${options.pageVariant} served from enhanced cache before source decode"
            }
            return cachedBitmap
        }

        cachedBitmap?.recycle()
        ImageEnhancementCache.removeCachedImage(
            mangaId,
            chapterId,
            pageIndex,
            configHash,
            options.pageVariant,
        )
        return null
    }
    // KMK <--

    private fun prepareForDisplay(source: Bitmap): Bitmap {
        if (options.bitmapConfig != Bitmap.Config.HARDWARE || !ImageUtil.canUseHardwareBitmap(source)) {
            return source
        }

        val hardwareBitmap = source.copy(Bitmap.Config.HARDWARE, false) ?: return source
        source.recycle()
        return hardwareBitmap
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            return if (options.customDecoder || isApplicable(result.source)) {
                TachiyomiImageDecoder(result.source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: ImageSource): Boolean {
            val type = try {
                source.source().peek().inputStream().use { ImageUtil.findImageType(it) }
            } catch (e: Exception) {
                null
            }
            // SY -->
            source.source().peek().inputStream().use { stream ->
                if (CbzCrypto.detectCoverImageArchive(stream)) return true
            }
            // SY <--
            return when (type) {
                ImageUtil.ImageType.AVIF, ImageUtil.ImageType.JXL, ImageUtil.ImageType.HEIF -> true
                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory
        override fun hashCode() = javaClass.hashCode()
    }

    companion object {
        var displayProfile: ByteArray? = null

        // KMK -->
        // Enhancement inference takes seconds; run it on a dedicated single-thread
        // dispatcher instead of occupying the shared Coil decoder pool (3 threads).
        private val enhanceDispatcher = Dispatchers.IO.limitedParallelism(1)
        // KMK <--
    }
}

// KMK -->
private fun nativeScaleBitmap(
    source: Bitmap,
    targetWidth: Int,
    targetHeight: Int,
): Bitmap {
    if (source.width == targetWidth && source.height == targetHeight) return source
    return Waifu2x.scaleBitmapNative(
        source,
        max(1, targetWidth),
        max(1, targetHeight),
    ) ?: Bitmap.createScaledBitmap(
        source,
        max(1, targetWidth),
        max(1, targetHeight),
        true,
    )
}
// KMK <--
