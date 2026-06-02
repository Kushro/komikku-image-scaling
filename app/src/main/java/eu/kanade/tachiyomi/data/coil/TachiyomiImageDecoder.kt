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
import eu.kanade.tachiyomi.util.image.ImageFilter
import eu.kanade.tachiyomi.util.system.GLUtil
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.RemoteUpscaler
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 * // KMK --) Also handles on-the-fly image enhancement via Waifu2x/RealCUGAN models.
 */
class TachiyomiImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {
    private val context = Injekt.get<Application>()

    override suspend fun decode(): DecodeResult? {
        return decodeSemaphore.withPermit {
            try {
                var bitmap: Bitmap? = null
                var sampleSize = 1

                // SY --> CbzCrypto cover image support
                var coverStream: BufferedInputStream? = null
                if (resources.sourceOrNull()?.peek()?.use { CbzCrypto.detectCoverImageArchive(it.inputStream()) } == true) {
                    if (resources.source().peek().use { ImageUtil.findImageType(it.inputStream()) == null }) {
                        coverStream = UniFile.fromFile(resources.file().toFile())
                            ?.archiveReader(context = context)
                            ?.getCoverStream()
                    }
                }
                // SY <--

                // 1. Try native ImageDecoder first
                val nativeDecoder = try {
                    val inputStream = coverStream
                        ?: resources.sourceOrNull()?.inputStream()
                        ?: resources.source().inputStream()
                    ImageDecoder.newInstance(inputStream, options.cropBorders, displayProfile)
                } catch (e: Exception) {
                    null
                }

                if (nativeDecoder != null && nativeDecoder.width > 0 && nativeDecoder.height > 0) {
                    try {
                        val srcWidth = nativeDecoder.width
                        val srcHeight = nativeDecoder.height
                        val dstWidth = options.size.widthPx(options.scale) { srcWidth }
                        val dstHeight = options.size.heightPx(options.scale) { srcHeight }

                        sampleSize = DecodeUtils.calculateInSampleSize(
                            srcWidth = srcWidth,
                            srcHeight = srcHeight,
                            dstWidth = dstWidth,
                            dstHeight = dstHeight,
                            scale = options.scale,
                        )
                        bitmap = nativeDecoder.decode(sampleSize = sampleSize)
                    } finally {
                        nativeDecoder.recycle()
                    }
                }

                // 2. Fallback to BitmapFactory for system-supported formats
                if (bitmap == null) {
                    try {
                        val byteBuf = resources.source().peek().readByteArray()
                        val ops = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(byteBuf, 0, byteBuf.size, ops)

                        if (ops.outWidth > 0 && ops.outHeight > 0) {
                            val srcWidth = ops.outWidth
                            val srcHeight = ops.outHeight
                            val dstWidth = options.size.widthPx(options.scale) { srcWidth }
                            val dstHeight = options.size.heightPx(options.scale) { srcHeight }

                            sampleSize = DecodeUtils.calculateInSampleSize(
                                srcWidth = srcWidth,
                                srcHeight = srcHeight,
                                dstWidth = dstWidth,
                                dstHeight = dstHeight,
                                scale = options.scale,
                            )

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
                    return@withPermit null
                }

                // KMK --> Enhancement Integration
                if (options.enhanced && !options.alreadyUpscaled) {
                    val preferences = Injekt.get<ReaderPreferences>()
                    val mangaId = options.mangaId
                    val chapterId = options.chapterId
                    val pageIndex = options.pageIndex
                    val pageVariant = options.pageVariant

                    if (mangaId != -1L && chapterId != -1L && pageIndex != -1) {
                        ImageEnhancementCache.init(context)

                        val enhancementMode = preferences.enhancementMode().get()
                        val isRemoteUpscaler = enhancementMode == 3
                        val configHash = if (isRemoteUpscaler) {
                            val remoteHost = preferences.remoteUpscalerHost().get()
                            val remotePort = preferences.remoteUpscalerPort().get()
                            ImageEnhancementCache.getConfigHash(
                                noise = 0,
                                scale = 0,
                                inputScale = 100,
                                model = -1,
                                maxWidth = 0,
                                maxHeight = 0,
                                resizeEnabled = false,
                                remoteHash = "$remoteHost:$remotePort",
                            )
                        } else {
                            ImageEnhancementCache.getConfigHash(
                                noise = preferences.realCuganNoiseLevel().get(),
                                scale = preferences.realCuganScale().get(),
                                inputScale = 100,
                                model = preferences.realCuganModel().get(),
                                maxWidth = preferences.realCuganMaxSizeWidth().get(),
                                maxHeight = preferences.realCuganMaxSizeHeight().get(),
                                resizeEnabled = preferences.realCuganResizeLargeImage().get(),
                            )
                        }

                        // KMK --> Remote upscaler branch — routes images to a Python TUI server
                        if (isRemoteUpscaler) {
                            val remoteHost = preferences.remoteUpscalerHost().get()
                            val remotePort = preferences.remoteUpscalerPort().get()

                            // Check cache first (same cache path as local mode)
                            var usedRemoteCache = false
                            val cachedFile = ImageEnhancementCache.getCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant)
                            if (cachedFile != null) {
                                try {
                                    val cachedBitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                                    if (cachedBitmap != null) {
                                        bitmap.recycle()
                                        bitmap = cachedBitmap
                                        usedRemoteCache = true
                                    }
                                } catch (e: Exception) {
                                    logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: Failed to decode cached remote-enhanced image" }
                                }
                            }

                            if (!usedRemoteCache) {
                                try {
                                    val result = RemoteUpscaler.process(bitmap, remoteHost, remotePort)
                                    if (result != null) {
                                        var enhanced = result

                                        // Output resolution limit (same as local path)
                                        val textureLimit = GLUtil.DEVICE_TEXTURE_LIMIT
                                        if (enhanced.width > textureLimit || enhanced.height > textureLimit) {
                                            val widthRatio = textureLimit.toFloat() / enhanced.width
                                            val heightRatio = textureLimit.toFloat() / enhanced.height
                                            val downscaleRatio = Math.min(widthRatio, heightRatio)
                                            val newWidth = (enhanced.width * downscaleRatio).toInt().coerceAtLeast(1)
                                            val newHeight = (enhanced.height * downscaleRatio).toInt().coerceAtLeast(1)
                                            val downscaled = nativeScaleBitmap(enhanced, newWidth, newHeight)
                                            if (downscaled != enhanced) {
                                                enhanced.recycle()
                                                enhanced = downscaled
                                            }
                                        }

                                        ImageEnhancementCache.saveToCache(mangaId, chapterId, pageIndex, configHash, enhanced, pageVariant)
                                        if (bitmap != enhanced) bitmap.recycle()
                                        bitmap = enhanced
                                    }
                                    // If remote returns null, keep original bitmap unenhanced
                                } catch (e: Exception) {
                                    logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: Remote upscaler failed" }
                                }
                            }
                        } else if (enhancementMode == 2) {
                            // KMK --> Serve a previously-enhanced page (e.g. populated by the prefetch
                            // queue) from disk instead of re-running the model on every decode.
                            val cachedFile = ImageEnhancementCache.getCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant)
                            val skipped = ImageEnhancementCache.isSkipped(mangaId, chapterId, pageIndex, configHash, pageVariant)
                            if (cachedFile != null) {
                                try {
                                    val cachedBitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                                    if (cachedBitmap != null) {
                                        bitmap.recycle()
                                        bitmap = cachedBitmap
                                    }
                                } catch (e: Exception) {
                                    logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: Failed to decode cached enhanced image" }
                                }
                            } else if (!skipped) try {
                                val model = preferences.realCuganModel().get()
                                val noise = preferences.realCuganNoiseLevel().get()
                                var scale = preferences.realCuganScale().get()

                                // Target Resolution Check / Prescale
                                val maxWidth = preferences.realCuganMaxSizeWidth().get()
                                val maxHeight = preferences.realCuganMaxSizeHeight().get()
                                val shouldResize = preferences.realCuganResizeLargeImage().get()
                                var shouldSkipEnhancement = false

                                val targetWidth = if (maxWidth > 0) maxWidth else Int.MAX_VALUE
                                val targetHeight = if (maxHeight > 0) maxHeight else Int.MAX_VALUE
                                val hasTargetResolution = maxWidth > 0 || maxHeight > 0
                                val exceedsLimit = hasTargetResolution &&
                                    (bitmap.width > targetWidth || bitmap.height > targetHeight)

                                if (exceedsLimit && !shouldResize) {
                                    ImageEnhancementCache.saveSkippedToCache(mangaId, chapterId, pageIndex, configHash, pageVariant)
                                    shouldSkipEnhancement = true
                                } else if (exceedsLimit) {
                                    ImageEnhancementCache.saveSkippedToCache(mangaId, chapterId, pageIndex, configHash, pageVariant)
                                    shouldSkipEnhancement = true
                                }

                                // Performance Mode
                                val perfMode = preferences.realCuganPerformanceMode().get()
                                val tileSleepMs = when (perfMode) {
                                    1, 2 -> 15
                                    else -> 0
                                }
                                val tileSize = when (perfMode) {
                                    1 -> 96
                                    2 -> 64
                                    else -> 128
                                }

                                // Validate scale based on model capabilities
                                val effectiveScale = when (model) {
                                    3 -> 2 // Nose: fixed 2x
                                    5 -> 2 // Waifu2x Upconv7: only supports 2x
                                    else -> scale
                                }

                                if (!shouldSkipEnhancement && hasTargetResolution) {
                                    val finalWidthAtScale = bitmap.width * effectiveScale.toFloat()
                                    val finalHeightAtScale = bitmap.height * effectiveScale.toFloat()
                                    val ratio = min(
                                        targetWidth / finalWidthAtScale,
                                        targetHeight / finalHeightAtScale,
                                    )

                                    if (ratio in 0f..<1f) {
                                        val newWidth = max(1, (bitmap.width * ratio).roundToInt())
                                        val newHeight = max(1, (bitmap.height * ratio).roundToInt())
                                        val scaledBitmap = nativeScaleBitmap(bitmap, newWidth, newHeight)
                                        if (scaledBitmap != bitmap) {
                                            bitmap.recycle()
                                            bitmap = scaledBitmap
                                        }
                                    }
                                }

                                if (!shouldSkipEnhancement) {
                                    val initialized = when (model) {
                                        0 -> Waifu2x.initRealCugan(context, noise, effectiveScale, isPro = false, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                        1 -> Waifu2x.initRealCugan(context, noise, effectiveScale, isPro = true, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                        2 -> Waifu2x.initRealESRGAN(context, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                        3 -> Waifu2x.initNose(context, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                        4 -> Waifu2x.initWaifu2x(context, noise, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                        5 -> Waifu2x.initWaifu2xUpconv7(context, noise, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                        else -> Waifu2x.initRealCugan(context, noise, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize)
                                    }

                                    if (initialized) {
                                        val processed = when (model) {
                                            0, 1 -> Waifu2x.processRealCugan(bitmap, pageIndex)
                                            2 -> Waifu2x.processRealESRGAN(bitmap, pageIndex)
                                            3 -> Waifu2x.processNose(bitmap, pageIndex)
                                            4, 5 -> Waifu2x.processWaifu2x(bitmap, pageIndex)
                                            else -> Waifu2x.processRealCugan(bitmap, pageIndex)
                                        }

                                        if (processed != null) {
                                            var result = ImageFilter.applyInkFilterIfEnabled(processed, Injekt.get())

                                            // Output resolution limit (prevent Canvas errors)
                                            val textureLimit = GLUtil.DEVICE_TEXTURE_LIMIT
                                            if (result.width > textureLimit || result.height > textureLimit) {
                                                val widthRatio = textureLimit.toFloat() / result.width
                                                val heightRatio = textureLimit.toFloat() / result.height
                                                val downscaleRatio = Math.min(widthRatio, heightRatio)

                                                val newWidth = (result.width * downscaleRatio).toInt().coerceAtLeast(1)
                                                val newHeight = (result.height * downscaleRatio).toInt().coerceAtLeast(1)
                                                val downscaled = nativeScaleBitmap(result, newWidth, newHeight)
                                                if (downscaled != result) {
                                                    result.recycle()
                                                    result = downscaled
                                                }
                                            }

                                            ImageEnhancementCache.saveToCache(mangaId, chapterId, pageIndex, configHash, result, pageVariant)
                                            if (bitmap != result) bitmap.recycle()
                                            bitmap = result
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: Failed to enhance image on-the-fly" }
                            }
                        }
                    }
                }
                // KMK <-- Enhancement Integration

                if (options.bitmapConfig == Bitmap.Config.HARDWARE && ImageUtil.canUseHardwareBitmap(bitmap)) {
                    val hwBitmap = bitmap.copy(Bitmap.Config.HARDWARE, false)
                    if (hwBitmap != null) {
                        bitmap.recycle()
                        bitmap = hwBitmap
                    }
                }

                DecodeResult(
                    image = bitmap.asImage(),
                    isSampled = sampleSize > 1,
                )
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: Critical failure during decode" }
                null
            }
        }
    }

    class Factory : Decoder.Factory {

        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            return if (options.customDecoder || isApplicable(result.source.source())) {
                TachiyomiImageDecoder(result.source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: BufferedSource): Boolean {
            val type = source.peek().inputStream().buffered().use { stream ->
                ImageUtil.findImageType(stream)
            }
            // SY -->
            source.peek().inputStream().use { stream ->
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
        private val decodeSemaphore = Semaphore(1)
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
