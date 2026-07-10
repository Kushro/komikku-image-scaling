package eu.kanade.tachiyomi.ui.library

/** Flattened render model for the library grid/list: a linear sequence of headers and entries. */
sealed interface LibraryUiItem {

    data class Header(
        val key: String,
        val title: String,
        val count: Int,
        val level: Int,
        val collapsed: Boolean,
    ) : LibraryUiItem

    data class Entry(val item: LibraryItem, val sectionKey: String) : LibraryUiItem {
        /** Unique across the whole list even if the same manga appears in multiple sections. */
        val listKey: String get() = "$sectionKey-${item.id}"
    }
}

/**
 * Flattens a section tree into a linear list of headers + entries for rendering. Collapsed
 * sections (by [collapsedKeys]) still emit their header but hide their content. When [this] is
 * the single, ungrouped section produced when no layers are configured, entries are emitted with
 * no headers at all, matching today's flat rendering.
 */
fun List<LibrarySection>.flatten(collapsedKeys: Set<String>): List<LibraryUiItem> {
    if (size == 1 && this[0].key == LibrarySection.FLAT_KEY) {
        val section = this[0]
        return section.items.map { LibraryUiItem.Entry(it, section.key) }
    }

    val result = mutableListOf<LibraryUiItem>()

    fun visit(section: LibrarySection, level: Int) {
        val collapsed = section.key in collapsedKeys
        result += LibraryUiItem.Header(
            key = section.key,
            title = section.title,
            count = section.count,
            level = level,
            collapsed = collapsed,
        )
        if (collapsed) return
        if (section.subsections.isNotEmpty()) {
            section.subsections.forEach { visit(it, level + 1) }
        } else {
            section.items.forEach { result += LibraryUiItem.Entry(it, section.key) }
        }
    }

    forEach { visit(it, level = 1) }
    return result
}

/** All manga ids reachable from this section, recursing into subsections. */
fun LibrarySection.allMangaIds(): List<Long> {
    return if (subsections.isNotEmpty()) {
        subsections.flatMap { it.allMangaIds() }
    } else {
        items.map { it.id }
    }
}
