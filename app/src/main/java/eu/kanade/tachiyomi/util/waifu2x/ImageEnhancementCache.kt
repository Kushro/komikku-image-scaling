package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * Manages disk cache for Real-CUGAN enhanced images to reduce memory usage.
 */
object ImageEnhancementCache {
    private const val CACHE_DIR_NAME = "realcugan_cache"
    private const val DEFAULT_MAX_CACHE_SIZE_MB = 3 * 1024 // 3 GB in MB
    private var cacheDir: File? = null
    private var lastTrimTime = 0L

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
     * Get the cache directory for a specific manga and chapter
     */
    private fun getChapterDir(mangaId: Long, chapterId: Long): File {
        val mangaDir = File(cacheDir, mangaId.toString())
        if (!mangaDir.exists()) mangaDir.mkdirs()
        val chapterDir = File(mangaDir, chapterId.toString())
        if (!chapterDir.exists()) chapterDir.mkdirs()
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
            android.util.Log.e("ImageEnhancementCache", "Failed to remove cached image for page $pageIndex", e)
            false
        }
    }

    fun removeSkipMarker(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): Boolean {
        return try {
            val file = File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant) + ".skip")
            !file.exists() || file.delete()
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to remove skip marker for page $pageIndex", e)
            false
        }
    }

    /**
     * Save bitmap to disk cache.
     *
     * The temp file name MUST be unique per write: the same page can be saved
     * concurrently by two writers (the page holder's individual remote-enhance job
     * and the prefetch queue's batch worker). With a shared fixed ".tmp" name both
     * writers interleave bytes into one file — and after the first rename the
     * second writer's fd keeps writing into the *renamed* final file (same inode),
     * corrupting the cached WebP into a mosaic of misplaced macroblocks.
     */
    fun saveToCache(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, bitmap: Bitmap, pageVariant: String = ""): File? {
        val currentCacheDir = cacheDir ?: return null

        try {
            val file = File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant))
            val tempFile = File(
                file.parent,
                "${file.name}.${Thread.currentThread().id}-${System.nanoTime()}.tmp",
            )

            try {
                FileOutputStream(tempFile).use { out ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
                    } else {
                        @Suppress("DEPRECATION")
                        bitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
                    }
                    out.flush()
                }

                // rename(2) is atomic and replaces any existing target, so a
                // concurrent duplicate save just wins wholesale — never mixes.
                if (tempFile.renameTo(file)) {
                    return file
                }
                return null
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        } catch (t: Throwable) {
            android.util.Log.e("ImageEnhancementCache", "Failed to save to cache for page $pageIndex", t)
            return null
        }
    }

    /**
     * Write raw image bytes directly to the cache, using the same atomic temp-rename pattern.
     * Used by the batch worker to avoid re-encoding server output (PNG → WebP transcoding).
     * The file keeps the ".webp" extension for naming consistency — decoders sniff the actual type.
     */
    fun saveBytesToCache(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, bytes: ByteArray, pageVariant: String = ""): File? {
        val currentCacheDir = cacheDir ?: return null
        return try {
            val file = File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant))
            val tempFile = File(
                file.parent,
                "${file.name}.${Thread.currentThread().id}-${System.nanoTime()}.tmp",
            )
            try {
                tempFile.writeBytes(bytes)
                if (tempFile.renameTo(file)) file else null
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        } catch (t: Throwable) {
            android.util.Log.e("ImageEnhancementCache", "Failed to save raw bytes to cache for page $pageIndex", t)
            null
        }
    }

    /**
     * Mark a page as skipped (too large to process) in the cache
     */
    fun saveSkippedToCache(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = "") {
        try {
            val file = File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant) + ".skip")
            if (!file.exists()) {
                file.createNewFile()
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to save skip marker", e)
        }
    }

    /**
     * Check if a page was marked as skipped in the cache
     */
    fun isSkipped(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): Boolean {
        return File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant) + ".skip").exists()
    }

    /**
     * Clear old cache files including skip markers
     */
    fun clearOldCache(mangaId: Long, chapterId: Long, currentPage: Int, keepRange: Int = 5) {
        getChapterDir(mangaId, chapterId).listFiles()?.forEach { file ->
            try {
                // filename format: pageIndex_configHash.webp
                val name = file.name
                val parts = name.split("_")
                if (parts.isNotEmpty()) {
                    val pageIndex = parts[0].toIntOrNull()
                    if (pageIndex != null) {
                        // Delete if page is too far behind or ahead
                        if (kotlin.math.abs(pageIndex - currentPage) > keepRange) {
                            file.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
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

    /**
     * Clear all cache files for a specific chapter
     */
    fun clearChapterCache(mangaId: Long, chapterId: Long) {
        try {
            val chapterDir = getChapterDir(mangaId, chapterId)
            if (chapterDir.exists()) {
                chapterDir.deleteRecursively()
                android.util.Log.d("ImageEnhancementCache", "Cleared cache for manga $mangaId, chapter $chapterId")
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to clear chapter cache", e)
        }
    }

    /**
     * Check cache size and trim if it exceeds limit (3GB)
     * Should be called from background thread
     */
    fun checkAndTrim(context: Context) {
        // Debounce: only check once every 10 minutes
        if (System.currentTimeMillis() - lastTrimTime < 10 * 60 * 1000) return
        lastTrimTime = System.currentTimeMillis()

        init(context)
        val dir = cacheDir ?: return

        try {
            val limit = maxCacheBytes
            var size = dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            if (size > limit) {
                android.util.Log.d("ImageEnhancementCache", "Cache size ${size / 1024 / 1024}MB > ${maxCacheSizeMb}MB, trimming...")

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
                android.util.Log.d("ImageEnhancementCache", "Trim complete, new size: ${size / 1024 / 1024}MB")
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to trim cache", e)
        }
    }

    /**
     * Delete all cached files for a chapter that match a specific config hash.
     * Used by "Force re-upscale" to invalidate remote-enhanced images after server model changes.
     */
    fun clearForChapter(mangaId: Long, chapterId: Long, configHash: String) {
        val dir = cacheDir ?: return
        val chapterDir = File(dir, "$mangaId/$chapterId")
        if (!chapterDir.isDirectory) return
        chapterDir.listFiles()
            ?.filter { it.name.contains(configHash) }
            ?.forEach { it.delete() }
    }
}
