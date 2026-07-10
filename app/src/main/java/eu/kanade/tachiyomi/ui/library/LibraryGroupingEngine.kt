package eu.kanade.tachiyomi.ui.library

import android.content.Context
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryGroupLayer
import tachiyomi.domain.library.model.LibraryGroupSort
import tachiyomi.domain.library.model.LibraryGroupType
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.source.local.LocalSource
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Turns a flat list of [LibraryItem]s into a tree of up to [tachiyomi.domain.library.model.LibraryGrouping.MAX_LAYERS]
 * collapsible [LibrarySection]s, per the active [LibraryGroupLayer]s. Rendered as headers within
 * the library grid/list instead of separate tabs (see [LibrarySection]).
 */
class LibraryGroupingEngine(
    private val sourceManager: SourceManager,
    private val trackerManager: TrackerManager,
    private val downloadManager: DownloadManager,
    private val context: Context,
) {

    fun compute(
        items: List<LibraryItem>,
        layers: List<LibraryGroupLayer>,
        tracksMap: Map<Long, List<Track>>,
        categoriesById: Map<Long, Category>,
        genreGroupMinSize: Int = 3,
    ): List<LibrarySection> {
        if (items.isEmpty()) return emptyList()
        if (layers.isEmpty()) {
            return listOf(LibrarySection(key = LibrarySection.FLAT_KEY, title = "", order = 0, items = items))
        }
        val now = System.currentTimeMillis()
        return computeLayer(items, layers, 0, tracksMap, categoriesById, genreGroupMinSize, now, "")
    }

    private fun computeLayer(
        items: List<LibraryItem>,
        layers: List<LibraryGroupLayer>,
        depth: Int,
        tracksMap: Map<Long, List<Track>>,
        categoriesById: Map<Long, Category>,
        genreGroupMinSize: Int,
        now: Long,
        parentKey: String,
    ): List<LibrarySection> {
        val layer = layers[depth]
        val buckets = extractGroups(items, layer.type, tracksMap, categoriesById, genreGroupMinSize, now)
        val sortedBuckets = sortBuckets(buckets, layer)
        return sortedBuckets.map { bucket ->
            val key = "$parentKey/l$depth:${layer.type.name}:${bucket.id}".removePrefix("/")
            if (depth + 1 < layers.size) {
                LibrarySection(
                    key = key,
                    title = bucket.title,
                    order = bucket.order,
                    subsections = computeLayer(
                        bucket.items,
                        layers,
                        depth + 1,
                        tracksMap,
                        categoriesById,
                        genreGroupMinSize,
                        now,
                        key,
                    ),
                )
            } else {
                LibrarySection(key = key, title = bucket.title, order = bucket.order, items = bucket.items)
            }
        }
    }

    private fun sortBuckets(buckets: List<GroupBucket>, layer: LibraryGroupLayer): List<GroupBucket> {
        val comparator = when (layer.sortBy) {
            LibraryGroupSort.NATURAL -> compareBy { it: GroupBucket -> it.order }
            LibraryGroupSort.ALPHABETICAL -> compareBy(String.CASE_INSENSITIVE_ORDER) { it: GroupBucket -> it.title }
            LibraryGroupSort.ITEM_COUNT -> compareBy { it: GroupBucket -> it.items.size }
            LibraryGroupSort.LATEST_CHAPTER -> compareBy { it: GroupBucket -> it.latestChapterDate }
            LibraryGroupSort.DATE_ADDED -> compareBy { it: GroupBucket -> it.dateAdded }
        }
        return if (layer.ascending) buckets.sortedWith(comparator) else buckets.sortedWith(comparator.reversed())
    }

    private fun extractGroups(
        items: List<LibraryItem>,
        type: LibraryGroupType,
        tracksMap: Map<Long, List<Track>>,
        categoriesById: Map<Long, Category>,
        genreGroupMinSize: Int,
        now: Long,
    ): List<GroupBucket> {
        val assignments = items.flatMap { item ->
            keysFor(item, type, tracksMap, categoriesById, now).map { it to item }
        }
        val effective = if (type == LibraryGroupType.GENRE) {
            remapSmallGenreGroups(assignments, genreGroupMinSize)
        } else {
            assignments
        }
        return effective
            .groupBy({ it.first }, { it.second })
            .map { (key, groupItems) -> GroupBucket(key.id, key.title, key.naturalOrder, groupItems) }
    }

    private fun remapSmallGenreGroups(
        assignments: List<Pair<GroupKey, LibraryItem>>,
        minSize: Int,
    ): List<Pair<GroupKey, LibraryItem>> {
        if (minSize <= 1) return assignments
        val counts = assignments.groupingBy { it.first.id }.eachCount()
        val otherKey = GroupKey(OTHER_KEY, context.stringResource(KMR.strings.group_other), Long.MAX_VALUE - 1)
        return assignments.map { (key, item) ->
            if (key.id != NONE_KEY && (counts[key.id] ?: 0) < minSize) otherKey to item else key to item
        }
    }

    private fun keysFor(
        item: LibraryItem,
        type: LibraryGroupType,
        tracksMap: Map<Long, List<Track>>,
        categoriesById: Map<Long, Category>,
        now: Long,
    ): List<GroupKey> {
        val manga = item.libraryManga.manga
        return when (type) {
            LibraryGroupType.SOURCE -> listOf(sourceKey(manga))
            LibraryGroupType.STATUS -> listOf(statusKey(manga))
            LibraryGroupType.TRACK_STATUS -> trackStatusKeys(item, tracksMap)
            LibraryGroupType.GENRE -> genreKeys(manga)
            LibraryGroupType.TRACKER_RATING -> listOf(ratingKey(item, tracksMap))
            LibraryGroupType.TITLE_DUPLICATES -> listOf(titleDuplicateKey(manga))
            LibraryGroupType.LANGUAGE -> listOf(languageKey(item))
            LibraryGroupType.AUTHOR -> listOf(personKey(manga.author, KMR.strings.group_no_author))
            LibraryGroupType.ARTIST -> listOf(personKey(manga.artist, KMR.strings.group_no_artist))
            LibraryGroupType.READ_PROGRESS -> listOf(readProgressKey(item))
            LibraryGroupType.DOWNLOAD_STATE -> listOf(downloadStateKey(item))
            LibraryGroupType.DATE_ADDED -> listOf(dateBucketKey(manga.dateAdded, now))
            LibraryGroupType.LAST_READ -> listOf(dateBucketKey(item.libraryManga.lastRead, now))
            LibraryGroupType.LATEST_CHAPTER -> listOf(dateBucketKey(item.libraryManga.latestUpload, now))
            LibraryGroupType.CATEGORY -> categoryKeys(item, categoriesById)
        }
    }

    private fun sourceKey(manga: Manga): GroupKey {
        val source = sourceManager.getOrStub(manga.source)
        val title = if (source.id == LocalSource.ID) {
            context.stringResource(MR.strings.local_source)
        } else {
            source.name.ifBlank { source.id.toString() }
        }
        return GroupKey(manga.source.toString(), title, 0L)
    }

    private fun statusKey(manga: Manga): GroupKey {
        val (nameRes, order) = statusMap[manga.status] ?: (MR.strings.unknown to 7L)
        return GroupKey(manga.status.toString(), context.stringResource(nameRes), order)
    }

    private fun trackStatusKeys(item: LibraryItem, tracksMap: Map<Long, List<Track>>): List<GroupKey> {
        val statuses = tracksMap[item.libraryManga.id]?.mapNotNull { track ->
            TrackStatus.parseTrackerStatus(trackerManager, track.trackerId, track.status)
        }?.takeIf { it.isNotEmpty() } ?: listOf(TrackStatus.OTHER)
        return statuses.distinct().map { status ->
            GroupKey(status.int.toString(), context.stringResource(status.res), status.ordinal.toLong())
        }
    }

    private fun genreKeys(manga: Manga): List<GroupKey> {
        val genres = manga.genre?.filter { it.isNotBlank() }
        if (genres.isNullOrEmpty()) {
            return listOf(GroupKey(NONE_KEY, context.stringResource(KMR.strings.group_no_genre), Long.MAX_VALUE))
        }
        return genres.map { genre ->
            val normalized = genre.trim().lowercase(Locale.ROOT)
            GroupKey(normalized, genre.trim(), 0L)
        }
    }

    private fun ratingKey(item: LibraryItem, tracksMap: Map<Long, List<Track>>): GroupKey {
        val score = tracksMap[item.libraryManga.id]
            ?.firstNotNullOfOrNull { track ->
                trackerManager.get(track.trackerId)?.get10PointScore(track)?.takeIf { it > 0.0 }
            }
        return when {
            score == null -> GroupKey("unrated", context.stringResource(KMR.strings.group_rating_unrated), 5L)
            score >= 9.0 -> GroupKey("9plus", "9+", 0L)
            score >= 8.0 -> GroupKey("8to9", "8–9", 1L)
            score >= 7.0 -> GroupKey("7to8", "7–8", 2L)
            score >= 6.0 -> GroupKey("6to7", "6–7", 3L)
            else -> GroupKey("under6", "<6", 4L)
        }
    }

    private fun titleDuplicateKey(manga: Manga): GroupKey {
        val normalized = manga.title.lowercase(Locale.ROOT).replace(NON_ALPHANUMERIC_REGEX, "")
        return GroupKey(normalized, manga.title, 0L)
    }

    private fun languageKey(item: LibraryItem): GroupKey {
        val lang = item.sourceLanguage
        if (lang.isBlank()) return GroupKey(NONE_KEY, context.stringResource(MR.strings.unknown), Long.MAX_VALUE)
        val title = runCatching { LocaleHelper.getDisplayName(lang) }.getOrNull()?.takeIf { it.isNotBlank() } ?: lang
        return GroupKey(lang, title, 0L)
    }

    private fun personKey(name: String?, noneRes: StringResource): GroupKey {
        val trimmed = name?.trim()
        if (trimmed.isNullOrEmpty()) return GroupKey(NONE_KEY, context.stringResource(noneRes), Long.MAX_VALUE)
        return GroupKey(trimmed.lowercase(Locale.ROOT), trimmed, 0L)
    }

    private fun readProgressKey(item: LibraryItem): GroupKey {
        val lm = item.libraryManga
        return when {
            lm.readCount == 0L -> GroupKey("not_started", context.stringResource(KMR.strings.group_read_not_started), 0L)
            lm.unreadCount > 0 -> GroupKey("in_progress", context.stringResource(KMR.strings.group_read_in_progress), 1L)
            lm.manga.status == SManga.COMPLETED.toLong() ->
                GroupKey("finished", context.stringResource(KMR.strings.group_read_finished), 3L)
            else -> GroupKey("up_to_date", context.stringResource(KMR.strings.group_read_up_to_date), 2L)
        }
    }

    private fun downloadStateKey(item: LibraryItem): GroupKey {
        val total = item.libraryManga.totalChapters
        val downloaded = downloadManager.getDownloadCount(item.libraryManga.manga).toLong()
        return when {
            total > 0 && downloaded >= total ->
                GroupKey("all", context.stringResource(KMR.strings.group_download_all), 0L)
            downloaded > 0 -> GroupKey("partial", context.stringResource(KMR.strings.group_download_partial), 1L)
            else -> GroupKey("none", context.stringResource(KMR.strings.group_download_none), 2L)
        }
    }

    private fun dateBucketKey(epochMillis: Long, now: Long): GroupKey {
        if (epochMillis <= 0L) return GroupKey("never", context.stringResource(KMR.strings.group_date_never), 6L)
        val days = TimeUnit.MILLISECONDS.toDays(now - epochMillis)
        return when {
            days < 1 -> GroupKey("today", context.stringResource(KMR.strings.group_date_today), 0L)
            days < 7 -> GroupKey("this_week", context.stringResource(KMR.strings.group_date_this_week), 1L)
            days < 30 -> GroupKey("this_month", context.stringResource(KMR.strings.group_date_this_month), 2L)
            days < 90 -> GroupKey("last_3_months", context.stringResource(KMR.strings.group_date_last_3_months), 3L)
            days < 365 -> GroupKey("this_year", context.stringResource(KMR.strings.group_date_this_year), 4L)
            else -> GroupKey("older", context.stringResource(KMR.strings.group_date_older), 5L)
        }
    }

    private fun categoryKeys(item: LibraryItem, categoriesById: Map<Long, Category>): List<GroupKey> {
        val categoryIds = item.libraryManga.categories
        if (categoryIds.isEmpty()) {
            return listOf(GroupKey("0", context.stringResource(KMR.strings.group_uncategorized), Long.MAX_VALUE))
        }
        return categoryIds.map { id ->
            val category = categoriesById[id]
            GroupKey(id.toString(), category?.name ?: context.stringResource(KMR.strings.group_uncategorized), category?.order ?: 0L)
        }
    }

    private data class GroupKey(val id: String, val title: String, val naturalOrder: Long)

    private data class GroupBucket(val id: String, val title: String, val order: Long, val items: List<LibraryItem>) {
        /** Most recent chapter upload date among this section's manga (0 if none). */
        val latestChapterDate: Long by lazy { items.maxOfOrNull { it.libraryManga.latestUpload } ?: 0L }

        /** Most recent date-added-to-library among this section's manga (0 if none). */
        val dateAdded: Long by lazy { items.maxOfOrNull { it.libraryManga.manga.dateAdded } ?: 0L }
    }

    companion object {
        private const val NONE_KEY = "__none__"
        private const val OTHER_KEY = "__other__"
        private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]")

        private val statusMap = mapOf(
            SManga.ONGOING.toLong() to (MR.strings.ongoing to 1L),
            SManga.COMPLETED.toLong() to (MR.strings.completed to 2L),
            SManga.PUBLISHING_FINISHED.toLong() to (MR.strings.publishing_finished to 3L),
            SManga.LICENSED.toLong() to (MR.strings.licensed to 4L),
            SManga.ON_HIATUS.toLong() to (MR.strings.on_hiatus to 5L),
            SManga.CANCELLED.toLong() to (MR.strings.cancelled to 6L),
        )
    }
}
