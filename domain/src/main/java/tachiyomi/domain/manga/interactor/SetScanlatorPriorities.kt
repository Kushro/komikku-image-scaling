package tachiyomi.domain.manga.interactor

import tachiyomi.domain.chapter.repository.ChapterRepository

class SetScanlatorPriorities(
    private val chapterRepository: ChapterRepository,
) {

    suspend fun await(mangaId: Long, priorities: List<String>) {
        chapterRepository.setScanlatorPriorities(mangaId, priorities)
    }
}
