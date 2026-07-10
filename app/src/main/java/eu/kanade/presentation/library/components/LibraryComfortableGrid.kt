package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.ui.library.LibraryUiItem
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.MangaCover

@Composable
internal fun LibraryComfortableGrid(
    // KMK -->
    items: List<LibraryUiItem>,
    // KMK <--
    columns: Int,
    contentPadding: PaddingValues,
    selection: Set<Long>,
    onClick: (LibraryManga) -> Unit,
    onLongClick: (LibraryManga) -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    // KMK -->
    onToggleGroup: (String) -> Unit,
    onSelectGroup: (String) -> Unit,
    usePanoramaCover: Boolean = false,
    // KMK <--
) {
    LazyLibraryGrid(
        modifier = Modifier.fillMaxSize(),
        columns = columns,
        contentPadding = contentPadding,
    ) {
        globalSearchItem(searchQuery, onGlobalSearchClicked)

        items(
            items = items,
            key = { if (it is LibraryUiItem.Header) "header-${it.key}" else (it as LibraryUiItem.Entry).listKey },
            span = { if (it is LibraryUiItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { if (it is LibraryUiItem.Header) "library_group_header" else "library_comfortable_grid_item" },
        ) { uiItem ->
            when (uiItem) {
                is LibraryUiItem.Header -> {
                    LibraryGroupHeader(
                        title = uiItem.title,
                        count = uiItem.count,
                        level = uiItem.level,
                        collapsed = uiItem.collapsed,
                        onToggle = { onToggleGroup(uiItem.key) },
                        onLongClick = { onSelectGroup(uiItem.key) },
                    )
                }
                is LibraryUiItem.Entry -> {
                    val libraryItem = uiItem.item
                    val manga = libraryItem.libraryManga.manga
                    MangaComfortableGridItem(
                        isSelected = manga.id in selection,
                        title = manga.title,
                        coverData = MangaCover(
                            mangaId = manga.id,
                            sourceId = manga.source,
                            isMangaFavorite = manga.favorite,
                            ogUrl = manga.thumbnailUrl,
                            lastModified = manga.coverLastModified,
                        ),
                        coverBadgeStart = {
                            DownloadsBadge(count = libraryItem.downloadCount)
                            UnreadBadge(count = libraryItem.unreadCount)
                        },
                        coverBadgeEnd = {
                            LanguageBadge(
                                isLocal = libraryItem.isLocal,
                                sourceLanguage = libraryItem.sourceLanguage,
                                useLangIcon = libraryItem.useLangIcon,
                            )
                            SourceIconBadge(source = libraryItem.source)
                        },
                        onLongClick = { onLongClick(libraryItem.libraryManga) },
                        onClick = { onClick(libraryItem.libraryManga) },
                        onClickContinueReading = if (onClickContinueReading != null && libraryItem.unreadCount > 0) {
                            { onClickContinueReading(libraryItem.libraryManga) }
                        } else {
                            null
                        },
                        usePanoramaCover = usePanoramaCover,
                    )
                }
            }
        }
    }
}
