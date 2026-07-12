package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import eu.kanade.tachiyomi.util.system.GLUtil
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages disk cache for Real-CUGAN enhanced images to reduce memory usage.
 */
object ImageEnhancementCache {
    private const val CACHE_DIR_NAME = "realcugan_cache"
    private const val DEFAULT_MAX_CACHE_SIZE_MB = 3 * 1024 // 3 GB in MB

    // KMK -->
    /** WebP hard-limits each side to 2^14−1 px; `Bitmap.compress(WEBP…)` silently fails above it. */
    const val MAX_WEBP_DIMENSION = 16383
    // KMK <--

    private var cacheDir: File? = null
    private var lastTrimTime = 0L

    /** Distinguishes concurrent temp-file writers of the same page (see [saveToCache]). */
    private val tempWriterId = AtomicLong()

    var maxCacheSizeMb: Int = DEFAULT_MAX_CACHE_SIZE_MB

    private val maxCacheBytes: Long get() = maxCacheSizeMb.toLong() * 1024 * 1024

    fun cacheSizeBytes(): Long = cacheDir?.walkTopDown()?.filter { it.isFile }?.map { it.length() }?.sum() ?: 0L

    fun cacheFileCount(): Int = cacheDir?.walkTopDown()?.filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".skip") }?.count() ?: 0

    fun init(context: Context) {
        if (cacheDir == null) {
            cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply {
                if (!exists()) mkdirs()
            }
        }
    }

    /**
     * Get the cache directory for a specific manga and chapter.
     * Read paths pass [create] = false so cache lookups don't litter empty directories
     * for every chapter the user merely opens.
     */
    private fun getChapterDir(mangaId: Long, chapterId: Long, create: Boolean = false): File {
        val chapterDir = File(cacheDir, "$mangaId/$chapterId")
        if (create && !chapterDir.exists()) chapterDir.mkdirs()
        return chapterDir
    }

    /**
     * Get cached file if it exists
     */
    fun getCachedImage(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): File? {
        val file = File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant))
        return if (file.exists()) file else null
    }

    /**
     * Check if a file is already cached (helper for UI checks)
     */
    fun isCached(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): Boolean {
        return getCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant) != null
    }

    /**
     * Remove a cached enhanced image and its temporary file for the same page/config.
     */
    fun removeCachedImage(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): Boolean {
        return try {
            val file = File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant))
            val removedFile = !file.exists() || file.delete()
            // In-progress saves use unique "<name>.<writer>.tmp" suffixes — sweep them all.
            var removedTemps = true
            file.parentFile?.listFiles()?.forEach { sibling ->
                if (sibling.name.startsWith("${file.name}.") && sibling.name.endsWith(".tmp")) {
                    removedTemps = sibling.delete() && removedTemps
                }
            }
            removedFile && removedTemps
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "ImageEnhancementCache: Failed to remove cached image for page $pageIndex" }
            false
        }
    }

    fun removeSkipMarker(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): Boolean {
        return try {
            val file = File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant) + ".skip")
            !file.exists() || file.delete()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "ImageEnhancementCache: Failed to remove skip marker for page $pageIndex" }
            false
        }
    }

    // KMK -->
    /**
     * Downscale a bitmap so it fits both the device GL texture limit and the WebP encoder
     * limit ([MAX_WEBP_DIMENSION]). Recycles the input when a smaller copy is returned.
     * Every enhanced bitmap MUST pass through this before being cached or displayed —
     * tall upscaled webtoon pages regularly exceed 16383 px.
     */
    fun clampToDisplayLimits(bitmap: Bitmap): Bitmap {
        val limit = minOf(GLUtil.DEVICE_TEXTURE_LIMIT, MAX_WEBP_DIMENSION)
        if (bitmap.width <= limit && bitmap.height <= limit) return bitmap
        val ratio = minOf(limit.toFloat() / bitmap.width, limit.toFloat() / bitmap.height)
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }
    // KMK <--

    /**
     * Save bitmap to disk cache. Returns null when encoding or the atomic rename fails.
     *
     * The temp file name MUST be unique per write: the same page can be saved
     * concurrently by two writers (the page holder's individual remote-enhance job
     * and the prefetch queue's batch worker). With a shared fixed ".tmp" name both
     * writers interleave bytes into one file — and after the first rename the
     * second writer's fd keeps writing into the *renamed* final file (same inode),
     * corrupting the cached WebP into a mosaic of misplaced macroblocks.
     */
    fun saveToCache(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, bitmap: Bitmap, pageVariant: String = ""): File? {
        cacheDir ?: return null

        try {
            val file = File(getChapterDir(mangaId, chapterId, create = true), getFilename(pageIndex, configHash, pageVariant))
            val tempFile = File(
                file.parent,
                "${file.name}.${tempWriterId.incrementAndGet()}-${System.nanoTime()}.tmp",
            )

            try {
                val compressed = FileOutputStream(tempFile).use { out ->
                    val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
                    } else {
                        @Suppress("DEPRECATION")
                        bitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
                    }
                    out.flush()
                    ok
                }
                // compress() returns false without throwing (e.g. a side exceeds the WebP
                // 16383 px limit) — never promote that empty temp to a cache entry.
                if (!compressed) {
                    logcat(LogPriority.ERROR) {
                        "ImageEnhancementCache: WebP encode failed for page $pageIndex (${bitmap.width}x${bitmap.height})"
                    }
                    return null
                }

                // rename(2) is atomic and replaces any existing target, so a
                // concurrent duplicate save just wins wholesale — never mixes.
                if (tempFile.renameTo(file)) {
                    trimIfNeeded()
                    return file
                }
                return null
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "ImageEnhancementCache: Failed to save to cache for page $pageIndex" }
            return null
        }
    }

    /**
     * Write raw image bytes directly to the cache, using the same atomic temp-rename pattern.
     * Used by the batch worker to avoid re-encoding server output (PNG → WebP transcoding).
     * The file keeps the ".webp" extension for naming consistency — decoders sniff the actual type.
     */
    fun saveBytesToCache(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, bytes: ByteArray, pageVariant: String = ""): File? {
        cacheDir ?: return null
        return try {
            val file = File(getChapterDir(mangaId, chapterId, create = true), getFilename(pageIndex, configHash, pageVariant))
            val tempFile = File(
                file.parent,
                "${file.name}.${tempWriterId.incrementAndGet()}-${System.nanoTime()}.tmp",
            )
            try {
                tempFile.writeBytes(bytes)
                if (tempFile.renameTo(file)) {
                    trimIfNeeded()
                    file
                } else {
                    null
                }
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "ImageEnhancementCache: Failed to save raw bytes to cache for page $pageIndex" }
            null
        }
    }

    /**
     * Mark a page as skipped (too large to process) in the cache
     */
    fun saveSkippedToCache(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = "") {
        try {
            val file = File(getChapterDir(mangaId, chapterId, create = true), getFilename(pageIndex, configHash, pageVariant) + ".skip")
            if (!file.exists()) {
                file.createNewFile()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "ImageEnhancementCache: Failed to save skip marker" }
        }
    }

    /**
     * Check if a page was marked as skipped in the cache
     */
    fun isSkipped(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): Boolean {
        return File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant) + ".skip").exists()
    }

    /**
     * Delete all cache files
     */
    fun clear(context: Context) {
        init(context)
        cacheDir?.deleteRecursively()
        cacheDir?.mkdirs()
    }

    private fun getFilename(pageIndex: Int, configHash: String, pageVariant: String = ""): String {
        return buildString {
            append(pageIndex)
            append('_')
            append(configHash)
            if (pageVariant.isNotEmpty()) {
                append('_')
                append(pageVariant)
            }
            append(".webp")
        }
    }

    /**
     * Generate a unique hash string based on current settings
     */
    fun getConfigHash(
        noise: Int,
        scale: Int,
        inputScale: Int,
        model: Int = 0,
        maxWidth: Int = 0,
        maxHeight: Int = 0,
        resizeEnabled: Boolean = false,
        remoteHash: String = "",
    ): String {
        if (remoteHash.isNotEmpty()) {
            return "remote_${remoteHash}_${noise}x${scale}x${inputScale}_m$model"
        }
        return "${noise}x${scale}x${inputScale}_m${model}_w${maxWidth}_h${maxHeight}_r${if (resizeEnabled) 1 else 0}"
    }

    // KMK -->
    /**
     * Config hash for remote-enhanced pages. Single source of truth shared by the page
     * holders, the decoder, the prefetch batch worker and the settings screen model —
     * they must all agree byte-for-byte or cache reads/writes silently miss each other.
     */
    fun getRemoteConfigHash(host: String, port: Int): String = getConfigHash(
        noise = 0,
        scale = 0,
        inputScale = 100,
        model = -1,
        maxWidth = 0,
        maxHeight = 0,
        resizeEnabled = false,
        remoteHash = "$host:$port",
    )
    // KMK <--

    /**
     * Clear all cache files for a specific chapter
     */
    fun clearChapterCache(mangaId: Long, chapterId: Long) {
        try {
            val chapterDir = getChapterDir(mangaId, chapterId)
            if (chapterDir.exists()) {
                chapterDir.deleteRecursively()
                logcat { "ImageEnhancementCache: Cleared cache for manga $mangaId, chapter $chapterId" }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "ImageEnhancementCache: Failed to clear chapter cache" }
        }
    }

    /**
     * Trim the cache back under [maxCacheSizeMb] by deleting the oldest files first.
     * Called after every successful save; the actual walk is debounced to once every
     * 10 minutes since it scans the whole cache tree.
     */
    private fun trimIfNeeded() {
        if (System.currentTimeMillis() - lastTrimTime < 10 * 60 * 1000) return
        lastTrimTime = System.currentTimeMillis()

        val dir = cacheDir ?: return

        try {
            val limit = maxCacheBytes
            var size = dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            if (size > limit) {
                logcat { "ImageEnhancementCache: Cache size ${size / 1024 / 1024}MB > ${maxCacheSizeMb}MB, trimming..." }

                // Get all files sorted by last modified (oldest first)
                val files = dir.walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.lastModified() }
                    .iterator()

                while (files.hasNext() && size > (limit * 0.9).toLong()) { // Trim to 90%
                    val file = files.next()
                    val len = file.length()
                    if (file.delete()) {
                        size -= len
                    }
                }
                logcat { "ImageEnhancementCache: Trim complete, new size: ${size / 1024 / 1024}MB" }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "ImageEnhancementCache: Failed to trim cache" }
        }
    }

    /**
     * Delete all cached files for a chapter that match a specific config hash.
     * Used by "Force re-upscale" to invalidate remote-enhanced images after server model changes.
     */
    fun clearForChapter(mangaId: Long, chapterId: Long, configHash: String) {
        val chapterDir = getChapterDir(mangaId, chapterId)
        if (!chapterDir.isDirectory) return
        chapterDir.listFiles()
            ?.filter { it.name.contains(configHash) }
            ?.forEach { it.delete() }
    }
}
