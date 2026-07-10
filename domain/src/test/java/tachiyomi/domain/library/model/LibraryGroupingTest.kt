package tachiyomi.domain.library.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class LibraryGroupingTest {

    @Test
    fun `empty serialized string decodes to default (no layers)`() {
        LibraryGrouping.deserialize("") shouldBe LibraryGrouping.default
        LibraryGrouping.default.layers shouldBe emptyList()
    }

    @Test
    fun `round-trips a single layer`() {
        val grouping = LibraryGrouping(
            listOf(LibraryGroupLayer(LibraryGroupType.SOURCE, LibraryGroupSort.ALPHABETICAL, ascending = false)),
        )
        val decoded = LibraryGrouping.deserialize(grouping.serialize())
        decoded shouldBe grouping
    }

    @Test
    fun `round-trips two layers preserving order`() {
        val grouping = LibraryGrouping(
            listOf(
                LibraryGroupLayer(LibraryGroupType.STATUS, LibraryGroupSort.NATURAL, ascending = true),
                LibraryGroupLayer(LibraryGroupType.SOURCE, LibraryGroupSort.ITEM_COUNT, ascending = false),
            ),
        )
        val decoded = LibraryGrouping.deserialize(grouping.serialize())
        decoded.layers.map { it.type } shouldBe listOf(LibraryGroupType.STATUS, LibraryGroupType.SOURCE)
        decoded shouldBe grouping
    }

    @Test
    fun `caps decoded layers at MAX_LAYERS`() {
        val serialized = LibraryGroupType.entries.joinToString("|") { "${it.name}:NATURAL:true" }
        val decoded = LibraryGrouping.deserialize(serialized)
        decoded.layers.size shouldBe LibraryGrouping.MAX_LAYERS
    }

    @Test
    fun `drops duplicate layer types keeping the first`() {
        val serialized = "SOURCE:NATURAL:true|SOURCE:ALPHABETICAL:false"
        val decoded = LibraryGrouping.deserialize(serialized)
        decoded.layers shouldBe listOf(LibraryGroupLayer(LibraryGroupType.SOURCE, LibraryGroupSort.NATURAL, true))
    }

    @Test
    fun `malformed entries are dropped instead of throwing`() {
        val decoded = LibraryGrouping.deserialize("not-a-valid-entry|SOURCE:NATURAL:true|GARBAGE:NATURAL:true")
        decoded.layers shouldBe listOf(LibraryGroupLayer(LibraryGroupType.SOURCE, LibraryGroupSort.NATURAL, true))
    }
}
