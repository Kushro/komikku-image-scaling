package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

class SetMangaChapterFlags(
    private val mangaRepository: MangaRepository,
) {

    suspend fun awaitSetDownloadedFilter(manga: Manga, flag: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = manga.chapterFlags.setFlag(flag, Manga.CHAPTER_DOWNLOADED_MASK),
            ),
        )
    }

    suspend fun awaitSetUnreadFilter(manga: Manga, flag: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = manga.chapterFlags.setFlag(flag, Manga.CHAPTER_UNREAD_MASK),
            ),
        )
    }

    suspend fun awaitSetBookmarkFilter(manga: Manga, flag: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = manga.chapterFlags.setFlag(flag, Manga.CHAPTER_BOOKMARKED_MASK),
            ),
        )
    }

    suspend fun awaitSetDisplayMode(manga: Manga, flag: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = manga.chapterFlags.setFlag(flag, Manga.CHAPTER_DISPLAY_MASK),
            ),
        )
    }

    suspend fun awaitSetSortingModeOrFlipOrder(manga: Manga, flag: Long): Boolean {
        val newFlags = manga.chapterFlags.let {
            if (manga.sorting == flag) {
                // Just flip the order
                val orderFlag = if (manga.sortDescending()) {
                    Manga.CHAPTER_SORT_ASC
                } else {
                    Manga.CHAPTER_SORT_DESC
                }
                it.setFlag(orderFlag, Manga.CHAPTER_SORT_DIR_MASK)
            } else {
                // Set new flag with ascending order
                it
                    .setFlag(flag, Manga.CHAPTER_SORTING_MASK)
                    .setFlag(Manga.CHAPTER_SORT_ASC, Manga.CHAPTER_SORT_DIR_MASK)
            }
        }
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = newFlags,
            ),
        )
    }

    suspend fun awaitSetAllFlags(
        mangaId: Long,
        unreadFilter: Long,
        downloadedFilter: Long,
        bookmarkedFilter: Long,
        sortingMode: Long,
        sortingDirection: Long,
        displayMode: Long,
        // KMK -->
        // Flags outside the masks set below (e.g. CHAPTER_SCANLATOR_PRIORITY_MASK) that must survive
        // this bulk overwrite. Callers should pass the manga's current chapterFlags here.
        preservedFlags: Long = 0L,
        // KMK <--
    ): Boolean {
        return mangaRepository.update(
            MangaUpdate(
                id = mangaId,
                chapterFlags = /* KMK --> */ preservedFlags /* KMK <-- */
                    .setFlag(unreadFilter, Manga.CHAPTER_UNREAD_MASK)
                    .setFlag(downloadedFilter, Manga.CHAPTER_DOWNLOADED_MASK)
                    .setFlag(bookmarkedFilter, Manga.CHAPTER_BOOKMARKED_MASK)
                    .setFlag(sortingMode, Manga.CHAPTER_SORTING_MASK)
                    .setFlag(sortingDirection, Manga.CHAPTER_SORT_DIR_MASK)
                    .setFlag(displayMode, Manga.CHAPTER_DISPLAY_MASK),
            ),
        )
    }

    // KMK -->
    suspend fun awaitSetScanlatorPriorityMode(manga: Manga, enabled: Boolean): Boolean {
        val flag = if (enabled) Manga.CHAPTER_SCANLATOR_PRIORITY else 0L
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = manga.chapterFlags.setFlag(flag, Manga.CHAPTER_SCANLATOR_PRIORITY_MASK),
            ),
        )
    }
    // KMK <--

    private fun Long.setFlag(flag: Long, mask: Long): Long {
        return this and mask.inv() or (flag and mask)
    }
}
