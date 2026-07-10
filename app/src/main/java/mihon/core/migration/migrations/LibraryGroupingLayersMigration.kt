package mihon.core.migration.migrations

import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibraryGroupLayer
import tachiyomi.domain.library.model.LibraryGroupType
import tachiyomi.domain.library.model.LibraryGrouping
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Migrates the legacy single group-as-tabs preference (grouping by source/status/tracking used to
 * produce separate library pages) into the new multi-layer [LibraryGrouping], which instead
 * renders as collapsible sections within a category page. Tabs are now always real categories, or
 * the single "ignore categories" page.
 */
class LibraryGroupingLayersMigration : Migration {
    override val version: Float = 81f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val libraryPreferences = migrationContext.get<LibraryPreferences>() ?: return false

        val layer = when (libraryPreferences.groupLibraryBy().get()) {
            LibraryGroup.BY_SOURCE -> LibraryGroupLayer(LibraryGroupType.SOURCE)
            LibraryGroup.BY_STATUS -> LibraryGroupLayer(LibraryGroupType.STATUS)
            LibraryGroup.BY_TRACK_STATUS -> LibraryGroupLayer(LibraryGroupType.TRACK_STATUS)
            LibraryGroup.UNGROUPED -> {
                libraryPreferences.libraryUngrouped().set(true)
                null
            }
            else -> null
        }
        if (layer != null) {
            libraryPreferences.libraryGrouping().set(LibraryGrouping(listOf(layer)))
        }
        libraryPreferences.groupLibraryBy().set(LibraryGroup.BY_DEFAULT)

        return true
    }
}
