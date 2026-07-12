package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.util.waifu2x.EnhancementMode
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun ReaderSettingsDialog(
    onDismissRequest: () -> Unit,
    onShowMenus: () -> Unit,
    onHideMenus: () -> Unit,
    screenModel: ReaderSettingsScreenModel,
) {
    // KMK --> The chapter-progress tab only exists while an enhancement mode is active.
    val enhancementMode by screenModel.preferences.enhancementMode().collectAsState()
    val upscalingActive = enhancementMode != EnhancementMode.NONE
    // KMK <--
    val tabTitles = persistentListOf(
        stringResource(MR.strings.pref_category_reading_mode),
        stringResource(MR.strings.pref_category_general),
        stringResource(MR.strings.custom_filter),
        stringResource(KMR.strings.upscaling_tab),
        // KMK -->
    ).let {
        if (upscalingActive) (it + stringResource(KMR.strings.upscaling_chapter_progress)).toPersistentList() else it
    }
    // KMK <--
    val pagerState = rememberPagerState { tabTitles.size }

    BoxWithConstraints {
        TabbedDialog(
            modifier = Modifier.heightIn(max = maxHeight * 0.75f),
            onDismissRequest = {
                onDismissRequest()
                onShowMenus()
            },
            tabTitles = tabTitles,
            pagerState = pagerState,
        ) { page ->
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window

            LaunchedEffect(pagerState.currentPage) {
                if (pagerState.currentPage == 2) {
                    window?.setDimAmount(0f)
                    onHideMenus()
                } else {
                    window?.setDimAmount(0.5f)
                    onShowMenus()
                }
            }

            Column(
                modifier = Modifier
                    .padding(vertical = TabbedDialogPaddings.Vertical)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (page) {
                    0 -> ReadingModePage(screenModel)
                    1 -> GeneralPage(screenModel)
                    2 -> ColorFilterPage(screenModel)
                    3 -> UpscalingPage(screenModel)
                    // KMK -->
                    4 -> UpscalingProgressPage()
                    // KMK <--
                }
            }
        }
    }
}
