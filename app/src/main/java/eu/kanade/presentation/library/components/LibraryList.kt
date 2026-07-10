package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.library.LibraryUiItem
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.util.plus

@Composable
internal fun LibraryList(
    // KMK -->
    items: List<LibraryUiItem>,
    // KMK <--
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
    // KMK <--
) {
    FastScrollLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
    ) {
        item {
            if (!searchQuery.isNullOrEmpty()) {
                GlobalSearchItem(
                    modifier = Modifier.fillMaxWidth(),
                    searchQuery = searchQuery,
                    onClick = onGlobalSearchClicked,
                )
            }
        }

        items(
            items = items,
            key = { if (it is LibraryUiItem.Header) "header-${it.key}" else (it as LibraryUiItem.Entry).listKey },
            contentType = { if (it is LibraryUiItem.Header) "library_group_header" else "library_list_item" },
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
                    MangaListItem(
                        isSelected = manga.id in selection,
                        title = manga.title,
                        coverData = MangaCover(
                            mangaId = manga.id,
                            sourceId = manga.source,
                            isMangaFavorite = manga.favorite,
                            ogUrl = manga.thumbnailUrl,
                            lastModified = manga.coverLastModified,
                        ),
                        badge = {
                            DownloadsBadge(count = libraryItem.downloadCount)
                            UnreadBadge(count = libraryItem.unreadCount)
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
                    )
                }
            }
        }
    }
}
