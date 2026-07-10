package tachiyomi.domain.chapter.service

import tachiyomi.domain.chapter.model.Chapter

/**
 * Sentinel used in the scanlator_priorities table and throughout this file to represent
 * chapters with a null/blank scanlator.
 */
const val UNKNOWN_SCANLATOR = ""

fun Chapter.scanlatorKey(): String = scanlator?.trim().orEmpty()

/**
 * Keeps a single version of each recognized chapter number: the one from the highest-priority
 * scanlator that has it. Chapters with an unrecognized number are never removed. Scanlators not
 * present in [priorities] are treated as lowest priority, tie-broken alphabetically.
 */
fun List<Chapter>.deduplicateByScanlatorPriority(priorities: List<String>): List<Chapter> {
    if (isEmpty()) return this

    val rank = priorities.withIndex().associate { (index, scanlator) -> scanlator to index }
    val unrankedPriority = priorities.size
    fun rankOf(chapter: Chapter): Int = rank[chapter.scanlatorKey()] ?: unrankedPriority

    val winnerIds = asSequence()
        .filter { it.isRecognizedNumber }
        .groupBy { it.chapterNumber }
        .mapValues { (_, candidates) ->
            candidates.minWithOrNull(
                compareBy<Chapter> { rankOf(it) }
                    .thenBy { it.scanlatorKey().lowercase() }
                    .thenByDescending { it.dateUpload }
                    .thenByDescending { it.id },
            )!!.id
        }
        .values
        .toHashSet()

    return filter { !it.isRecognizedNumber || it.id in winnerIds }
}
