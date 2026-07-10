package tachiyomi.domain.library.model

/**
 * A type of grouping that can be applied as one layer of [LibraryGrouping]. Distinct from the
 * legacy [LibraryGroup] (which turns a single grouping into separate library pages/tabs): these
 * render as collapsible sections within a single category page.
 */
enum class LibraryGroupType {
    SOURCE,
    STATUS,
    TRACK_STATUS,
    GENRE,
    TRACKER_RATING,
    TITLE_DUPLICATES,
    LANGUAGE,
    AUTHOR,
    ARTIST,
    READ_PROGRESS,
    DOWNLOAD_STATE,
    DATE_ADDED,
    LAST_READ,
    LATEST_CHAPTER,
    CATEGORY,
}

/** How sections within a single grouping layer are ordered. */
enum class LibraryGroupSort {
    /** Type-specific logical order (e.g. publication status order, rating buckets high to low). */
    NATURAL,
    ALPHABETICAL,
    ITEM_COUNT,
}

data class LibraryGroupLayer(
    val type: LibraryGroupType,
    val sortBy: LibraryGroupSort = LibraryGroupSort.NATURAL,
    val ascending: Boolean = true,
)

/**
 * Up to 2 [LibraryGroupLayer]s applied within a library category page. An empty list means no
 * grouping (today's default rendering: a flat grid/list of manga).
 */
data class LibraryGrouping(
    val layers: List<LibraryGroupLayer> = emptyList(),
) {

    object Serializer {
        fun deserialize(serialized: String): LibraryGrouping {
            return LibraryGrouping.deserialize(serialized)
        }

        fun serialize(value: LibraryGrouping): String {
            return value.serialize()
        }
    }

    fun serialize(): String {
        return layers.joinToString("|") { "${it.type.name}:${it.sortBy.name}:${it.ascending}" }
    }

    companion object {
        val default = LibraryGrouping()

        /** Max number of simultaneous grouping layers supported by the engine and settings UI. */
        const val MAX_LAYERS = 2

        fun deserialize(serialized: String): LibraryGrouping {
            if (serialized.isBlank()) return default
            val layers = serialized.split("|").mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size != 3) return@mapNotNull null
                val type = runCatching { LibraryGroupType.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
                val sortBy = runCatching { LibraryGroupSort.valueOf(parts[1]) }.getOrNull() ?: LibraryGroupSort.NATURAL
                val ascending = parts[2].toBooleanStrictOrNull() ?: true
                LibraryGroupLayer(type, sortBy, ascending)
            }.distinctBy { it.type }.take(MAX_LAYERS)
            return LibraryGrouping(layers)
        }
    }
}
