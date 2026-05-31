package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadEnhancer
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import mihon.core.archive.archiveReader
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * Loader used to load a chapter from the downloaded chapters.
 */
internal class DownloadPageLoader(
    private val chapter: ReaderChapter,
    private val manga: Manga,
    private val source: Source,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
) : PageLoader() {

    private val context: Application by injectLazy()
    private val readerPreferences: ReaderPreferences by injectLazy()

    private var archivePageLoader: ArchivePageLoader? = null

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val dbChapter = chapter.chapter
        val chapterPath = downloadProvider.findChapterDir(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            // SY -->
            manga.ogTitle,
            // SY <--
            source,
        )
        return if (chapterPath?.isFile == true) {
            getPagesFromArchive(chapterPath)
        } else {
            getPagesFromDirectory()
        }
    }

    override fun recycle() {
        super.recycle()
        archivePageLoader?.recycle()
    }

    private suspend fun getPagesFromArchive(file: UniFile): List<ReaderPage> {
        val loader = ArchivePageLoader(file.archiveReader(context)).also { archivePageLoader = it }
        return loader.getPages().also { pages ->
            // KMK --> Check if chapter was enhanced during download (marker inside archive)
            val configHash = DownloadEnhancer.readEnhancedConfigHash(file)
            if (configHash != null) {
                val currentHash = DownloadEnhancer.computeConfigHash(readerPreferences)
                val alreadyUpscaled = configHash == currentHash
                if (alreadyUpscaled) {
                    pages.forEach { it.alreadyUpscaled = true }
                }
            }
            // KMK <--
        }
    }

    private fun getPagesFromDirectory(): List<ReaderPage> {
        val chapterDir = downloadProvider.findChapterDir(
            chapter.chapter.name,
            chapter.chapter.scanlator,
            chapter.chapter.url,
            // SY -->
            manga.ogTitle,
            // SY <--
            source,
        )
        // KMK --> Check if this chapter was enhanced during download (check once, apply to all)
        val isAlreadyUpscaled = if (chapterDir != null) {
            val configHash = DownloadEnhancer.readEnhancedConfigHash(chapterDir)
            if (configHash != null) {
                configHash == DownloadEnhancer.computeConfigHash(readerPreferences)
            } else {
                false
            }
        } else {
            false
        }
        // KMK <--
        val pages = downloadManager.buildPageList(source, manga, chapter.chapter.toDomainChapter()!!)
        return pages.map { page ->
            ReaderPage(page.index, page.url, page.imageUrl) {
                context.contentResolver.openInputStream(page.uri ?: Uri.EMPTY)!!
            }.apply {
                status = Page.State.Ready
                // KMK -->
                if (isAlreadyUpscaled) {
                    alreadyUpscaled = true
                }
                // KMK <--
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        archivePageLoader?.loadPage(page)
    }
}