package tachiyomi.domain.chapter.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.model.Chapter

@Execution(ExecutionMode.CONCURRENT)
class ScanlatorPriorityDedupTest {

    @Test
    fun `fills gaps from lower priority group`() {
        val groupA = (1..6).map { chapter(id = it.toLong(), number = it.toDouble(), scanlator = "A") } +
            (8..11).map { chapter(id = it.toLong(), number = it.toDouble(), scanlator = "A") }
        val groupB = (1..11).map { chapter(id = it.toLong() + 100, number = it.toDouble(), scanlator = "B") }

        val result = (groupA + groupB).deduplicateByScanlatorPriority(listOf("A", "B"))

        result.map { it.chapterNumber to it.scanlatorKey() }.sortedBy { it.first } shouldBe
            (1..11).map { it.toDouble() to if (it == 7) "B" else "A" }
    }

    @Test
    fun `never removes unrecognized chapter numbers`() {
        val chapters = listOf(
            chapter(id = 1, number = -1.0, scanlator = "A"),
            chapter(id = 2, number = -1.0, scanlator = "B"),
            chapter(id = 3, number = 1.0, scanlator = "A"),
        )

        val result = chapters.deduplicateByScanlatorPriority(listOf("A", "B"))

        result.map { it.id }.toSet() shouldBe setOf(1L, 2L, 3L)
    }

    @Test
    fun `decimal chapter numbers are distinct keys`() {
        val chapters = listOf(
            chapter(id = 1, number = 7.0, scanlator = "A"),
            chapter(id = 2, number = 7.5, scanlator = "A"),
        )

        val result = chapters.deduplicateByScanlatorPriority(listOf("A"))

        result.map { it.id }.toSet() shouldBe setOf(1L, 2L)
    }

    @Test
    fun `null and blank scanlators group under the unknown sentinel and respect their rank`() {
        val chapters = listOf(
            chapter(id = 1, number = 1.0, scanlator = null),
            chapter(id = 2, number = 1.0, scanlator = "A"),
        )

        val result = chapters.deduplicateByScanlatorPriority(listOf(UNKNOWN_SCANLATOR, "A"))

        result.map { it.id } shouldBe listOf(1L)
    }

    @Test
    fun `scanlator without a rank loses to any ranked scanlator`() {
        val chapters = listOf(
            chapter(id = 1, number = 1.0, scanlator = "Unranked"),
            chapter(id = 2, number = 1.0, scanlator = "A"),
        )

        val result = chapters.deduplicateByScanlatorPriority(listOf("A"))

        result.map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `two unranked scanlators are tie-broken alphabetically`() {
        val chapters = listOf(
            chapter(id = 1, number = 1.0, scanlator = "Zeta"),
            chapter(id = 2, number = 1.0, scanlator = "Alpha"),
        )

        val result = chapters.deduplicateByScanlatorPriority(emptyList())

        result.map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `duplicate from the same scanlator keeps the most recent upload`() {
        val chapters = listOf(
            chapter(id = 1, number = 1.0, scanlator = "A", dateUpload = 1000L),
            chapter(id = 2, number = 1.0, scanlator = "A", dateUpload = 2000L),
        )

        val result = chapters.deduplicateByScanlatorPriority(listOf("A"))

        result.map { it.id } shouldBe listOf(2L)
    }

    private fun chapter(
        id: Long,
        number: Double,
        scanlator: String?,
        dateUpload: Long = 0L,
    ) = Chapter.create().copy(
        id = id,
        chapterNumber = number,
        scanlator = scanlator,
        dateUpload = dateUpload,
    )
}
