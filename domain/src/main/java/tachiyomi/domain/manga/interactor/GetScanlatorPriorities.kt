package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.chapter.repository.ChapterRepository

class GetScanlatorPriorities(
    private val chapterRepository: ChapterRepository,
) {

    suspend fun await(mangaId: Long): List<String> {
        return chapterRepository.getScanlatorPriorities(mangaId)
    }

    fun subscribe(mangaId: Long): Flow<List<String>> {
        return chapterRepository.getScanlatorPrioritiesAsFlow(mangaId)
    }
}
