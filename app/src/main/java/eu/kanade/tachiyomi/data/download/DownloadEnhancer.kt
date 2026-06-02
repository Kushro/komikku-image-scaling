package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.image.ImageFilter
import eu.kanade.tachiyomi.util.system.GLUtil
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.RemoteUpscaler
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Handles image enhancement during chapter download and metadata tracking
 * for skipping re-enhancement when the reader loads previously-upscaled chapters.
 */
object DownloadEnhancer {

    /** Metadata file name stored in each enhanced chapter directory */
    private const val ENHANCED_MARKER = ".komikkup_enhanced"

    /**
     * Enhances a single downloaded image file in-place.
     * Reads the file, applies the current enhancement model, and overwrites it.
     * Returns true if enhancement was applied successfully.
     */
    suspend fun enhanceImage(
        context: Context,
        imageFile: UniFile,
        preferences: ReaderPreferences,
        pageIndex: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        // KMK --> Remote mode: upscale the file via the server instead of the local NCNN models.
        if (preferences.enhancementMode().get() == 3) {
            return@withContext enhanceImageRemote(imageFile, preferences)
        }
        // KMK <--
        try {
            val model = preferences.realCuganModel().get()
            val noise = preferences.realCuganNoiseLevel().get()
            val scale = preferences.realCuganScale().get()
            val perfMode = preferences.realCuganPerformanceMode().get()
            val maxWidth = preferences.realCuganMaxSizeWidth().get()
            val maxHeight = preferences.realCuganMaxSizeHeight().get()

            // Decode the original image
            val originalBitmap = withContext(Dispatchers.IO) {
                imageFile.openInputStream().use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
            } ?: return@withContext false

            var bitmap = originalBitmap

            // Determine effective scale based on model
            val effectiveScale = when (model) {
                3 -> 2 // Nose: fixed 2x
                5 -> 2 // Waifu2x Upconv7: only supports 2x
                else -> scale
            }

            // Check target resolution
            val targetWidth = if (maxWidth > 0) maxWidth else Int.MAX_VALUE
            val targetHeight = if (maxHeight > 0) maxHeight else Int.MAX_VALUE
            val hasTargetResolution = maxWidth > 0 || maxHeight > 0
            val exceedsLimit = hasTargetResolution &&
                (bitmap.width > targetWidth || bitmap.height > targetHeight)

            // If image exceeds limit and we're not resizing, skip
            val shouldResize = preferences.realCuganResizeLargeImage().get()
            if (exceedsLimit && !shouldResize) {
                bitmap.recycle()
                return@withContext false
            }

            // Performance mode
            val tileSleepMs = when (perfMode) {
                1, 2 -> 15
                else -> 0
            }
            val tileSize = when (perfMode) {
                1 -> 96
                2 -> 64
                else -> 128
            }

            // Initialize the model
            val initialized = when (model) {
                0 -> Waifu2x.initRealCugan(context, noise, effectiveScale, isPro = false, tileSleepMs = tileSleepMs, tileSize = tileSize)
                1 -> Waifu2x.initRealCugan(context, noise, effectiveScale, isPro = true, tileSleepMs = tileSleepMs, tileSize = tileSize)
                2 -> Waifu2x.initRealESRGAN(context, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize)
                3 -> Waifu2x.initNose(context, tileSleepMs = tileSleepMs, tileSize = tileSize)
                4 -> Waifu2x.initWaifu2x(context, noise, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize)
                5 -> Waifu2x.initWaifu2xUpconv7(context, noise, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize)
                else -> Waifu2x.initRealCugan(context, noise, effectiveScale, tileSleepMs = tileSleepMs, tileSize = tileSize)
            }

            if (!initialized) {
                bitmap.recycle()
                return@withContext false
            }

            // Process the image
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
                    val downscaleRatio = min(widthRatio, heightRatio)
                    val newWidth = (result.width * downscaleRatio).toInt().coerceAtLeast(1)
                    val newHeight = (result.height * downscaleRatio).toInt().coerceAtLeast(1)
                    val downscaled = nativeScaleBitmap(result, newWidth, newHeight)
                    if (downscaled != result) {
                        result.recycle()
                        result = downscaled
                    }
                }

                // Write enhanced bitmap back to file
                val compressFormat = detectCompressFormat(imageFile)
                imageFile.openOutputStream().use { outputStream ->
                    result.compress(compressFormat, 95, outputStream)
                }

                if (bitmap != result) bitmap.recycle()
                result.recycle()
                true
            } else {
                bitmap.recycle()
                false
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "DownloadEnhancer: Failed to enhance image" }
            false
        }
    }

    /**
     * Enhances a single downloaded image file in-place via the remote upscale server.
     */
    private suspend fun enhanceImageRemote(
        imageFile: UniFile,
        preferences: ReaderPreferences,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val host = preferences.remoteUpscalerHost().get()
            val port = preferences.remoteUpscalerPort().get()
            if (host.isBlank()) return@withContext false

            val originalBitmap = imageFile.openInputStream().use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            } ?: return@withContext false

            val processed = RemoteUpscaler.process(originalBitmap, host, port)
            if (processed != null) {
                var result = ImageFilter.applyInkFilterIfEnabled(processed, Injekt.get())

                val textureLimit = GLUtil.DEVICE_TEXTURE_LIMIT
                if (result.width > textureLimit || result.height > textureLimit) {
                    val widthRatio = textureLimit.toFloat() / result.width
                    val heightRatio = textureLimit.toFloat() / result.height
                    val downscaleRatio = min(widthRatio, heightRatio)
                    val newWidth = (result.width * downscaleRatio).toInt().coerceAtLeast(1)
                    val newHeight = (result.height * downscaleRatio).toInt().coerceAtLeast(1)
                    val downscaled = nativeScaleBitmap(result, newWidth, newHeight)
                    if (downscaled != result) {
                        result.recycle()
                        result = downscaled
                    }
                }

                val compressFormat = detectCompressFormat(imageFile)
                imageFile.openOutputStream().use { outputStream ->
                    result.compress(compressFormat, 95, outputStream)
                }

                if (originalBitmap != result) originalBitmap.recycle()
                result.recycle()
                true
            } else {
                originalBitmap.recycle()
                false
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "DownloadEnhancer: Failed to enhance image via remote server" }
            false
        }
    }

    /**
     * Writes the enhancement metadata file to a chapter directory.
     * Records the config hash so the reader can check if a chapter was
     * already enhanced with the current settings.
     */
    fun writeEnhancedMarker(chapterDir: UniFile, configHash: String) {
        try {
            val marker = chapterDir.createFile(ENHANCED_MARKER) ?: return
            marker.openOutputStream().bufferedWriter().use { writer ->
                writer.write(configHash)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "DownloadEnhancer: Failed to write enhanced marker" }
        }
    }

    /**
     * Writes the enhancement metadata file to a chapter directory (File variant).
     */
    fun writeEnhancedMarker(chapterDir: File, configHash: String) {
        try {
            val marker = File(chapterDir, ENHANCED_MARKER)
            marker.writeText(configHash)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "DownloadEnhancer: Failed to write enhanced marker" }
        }
    }

    /**
     * Reads the enhancement config hash from a chapter directory.
     * Returns null if the chapter was not enhanced or the marker is missing.
     */
    fun readEnhancedConfigHash(chapterDir: UniFile): String? {
        return try {
            val marker = chapterDir.findFile(ENHANCED_MARKER) ?: return null
            marker.openInputStream().bufferedReader().use { it.readLine() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads the enhancement config hash from a chapter directory (File variant).
     */
    fun readEnhancedConfigHash(chapterDir: File): String? {
        return try {
            val marker = File(chapterDir, ENHANCED_MARKER)
            if (marker.exists()) marker.readText().trim() else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Computes the current enhancement config hash, matching the format used by
     * ImageEnhancementCache.getConfigHash plus the model type. Mode-aware: remote mode (3) uses the
     * server host/port hash so download markers line up with the reader's remote cache keys.
     */
    fun computeConfigHash(preferences: ReaderPreferences): String {
        if (preferences.enhancementMode().get() == 3) {
            val host = preferences.remoteUpscalerHost().get()
            val port = preferences.remoteUpscalerPort().get()
            return ImageEnhancementCache.getConfigHash(
                noise = 0,
                scale = 0,
                inputScale = 100,
                model = -1,
                maxWidth = 0,
                maxHeight = 0,
                resizeEnabled = false,
                remoteHash = "$host:$port",
            )
        }
        return ImageEnhancementCache.getConfigHash(
            noise = preferences.realCuganNoiseLevel().get(),
            scale = preferences.realCuganScale().get(),
            inputScale = preferences.realCuganInputScale().get(),
            model = preferences.realCuganModel().get(),
            maxWidth = preferences.realCuganMaxSizeWidth().get(),
            maxHeight = preferences.realCuganMaxSizeHeight().get(),
            resizeEnabled = preferences.realCuganResizeLargeImage().get(),
        )
    }

    private fun detectCompressFormat(file: UniFile): Bitmap.CompressFormat {
        val name = file.name?.lowercase() ?: return Bitmap.CompressFormat.JPEG
        return when {
            name.endsWith(".png") -> Bitmap.CompressFormat.PNG
            name.endsWith(".webp") -> Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
    }

    private fun nativeScaleBitmap(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap {
        if (source.width == targetWidth && source.height == targetHeight) return source
        return Waifu2x.scaleBitmapNative(source, max(1, targetWidth), max(1, targetHeight))
            ?: Bitmap.createScaledBitmap(source, max(1, targetWidth), max(1, targetHeight), true)
    }
}
