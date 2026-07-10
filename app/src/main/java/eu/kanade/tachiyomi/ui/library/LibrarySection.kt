package eu.kanade.tachiyomi.ui.library

/**
 * One collapsible section produced by [LibraryGroupingEngine.compute]. A section with
 * [subsections] is a first-layer group containing second-layer subgroups; a section without
 * subsections is a leaf holding the actual [items].
 */
data class LibrarySection(
    val key: String,
    val title: String,
    val order: Long,
    val items: List<LibraryItem> = emptyList(),
    val subsections: List<LibrarySection> = emptyList(),
) {
    val count: Int by lazy {
        if (subsections.isNotEmpty()) subsections.sumOf { it.count } else items.size
    }

    companion object {
        /** Key used for the single, ungrouped section returned when no layers are configured. */
        const val FLAT_KEY = "flat"
    }
}
