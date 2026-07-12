package eu.kanade.presentation.reader.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ConnectionStatus
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.util.waifu2x.EnhancementMode
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.PageUpscaleRecord
import eu.kanade.tachiyomi.util.waifu2x.PageUpscaleStatus
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun UpscalingPage(screenModel: ReaderSettingsScreenModel) {
    val preferences = screenModel.preferences
    val enhancementMode by preferences.enhancementMode().collectAsState()

    EnhancementModeSetting(preferences)

    when (enhancementMode) {
        EnhancementMode.REMOTE -> RemoteUpscalerSettings(preferences) {
            ConnectionCheckRow(screenModel)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 4.dp),
            ) {
                OutlinedButton(onClick = { screenModel.forceReupscale() }) {
                    Text(stringResource(KMR.strings.reader_force_reupscale))
                }
            }
        }
        EnhancementMode.LOCAL -> LocalUpscalerSettings(preferences)
    }

    if (enhancementMode != EnhancementMode.NONE) {
        EnhanceOnDownloadSetting(preferences)
        OverlaySettings(preferences)
    }

    ChapterUpscaleProgress()
}

@Composable
private fun ConnectionCheckRow(screenModel: ReaderSettingsScreenModel) {
    val connectionStatus by screenModel.connectionStatus.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { screenModel.checkRemoteConnection() }) {
            Text(stringResource(KMR.strings.reader_check_connection))
        }
        when (val status = connectionStatus) {
            ConnectionStatus.Checking -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
            is ConnectionStatus.Ok -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = UpscalingSuccessColor,
                )
                if (status.modelName != null) {
                    Text(
                        text = stringResource(KMR.strings.reader_remote_model_active, status.modelName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ConnectionStatus.NotReady -> {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(KMR.strings.upscaling_hub_server_not_ready),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ConnectionStatus.Failed -> {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            null -> {}
        }
    }
}

@Composable
private fun OverlaySettings(preferences: ReaderPreferences) {
    HeadingItem(stringResource(KMR.strings.upscaling_overlay_settings))

    val overlayDetail by preferences.enhancementOverlayDetail().collectAsState()
    SettingsChipRow(KMR.strings.upscaling_overlay_detail) {
        listOf(
            0 to stringResource(KMR.strings.upscaling_overlay_detail_off),
            1 to stringResource(KMR.strings.upscaling_overlay_detail_minimal),
            2 to stringResource(KMR.strings.upscaling_overlay_detail_compact),
            3 to stringResource(KMR.strings.upscaling_overlay_detail_detailed),
        ).forEach { (value, label) ->
            FilterChip(
                selected = overlayDetail.coerceAtLeast(0) == value,
                onClick = { preferences.enhancementOverlayDetail().set(value) },
                label = { Text(label) },
            )
        }
    }

    val overlayPosition by preferences.enhancementOverlayPosition().collectAsState()
    SettingsChipRow(KMR.strings.upscaling_overlay_position) {
        listOf(
            0 to stringResource(KMR.strings.upscaling_overlay_position_bottom_start),
            1 to stringResource(KMR.strings.upscaling_overlay_position_bottom_end),
            2 to stringResource(KMR.strings.upscaling_overlay_position_top_start),
            3 to stringResource(KMR.strings.upscaling_overlay_position_top_end),
        ).forEach { (value, label) ->
            FilterChip(
                selected = overlayPosition == value,
                onClick = { preferences.enhancementOverlayPosition().set(value) },
                label = { Text(label) },
            )
        }
    }

    val overlayStyle by preferences.enhancementOverlayStyle().collectAsState()
    SettingsChipRow(KMR.strings.upscaling_overlay_style) {
        listOf(
            0 to stringResource(KMR.strings.upscaling_overlay_style_filled),
            1 to stringResource(KMR.strings.upscaling_overlay_style_outlined),
            2 to stringResource(KMR.strings.upscaling_overlay_style_minimal),
        ).forEach { (value, label) ->
            FilterChip(
                selected = overlayStyle == value,
                onClick = { preferences.enhancementOverlayStyle().set(value) },
                label = { Text(label) },
            )
        }
    }

    val overlaySize by preferences.enhancementOverlaySize().collectAsState()
    SettingsChipRow(KMR.strings.upscaling_overlay_size) {
        listOf(
            0 to stringResource(KMR.strings.upscaling_overlay_size_small),
            1 to stringResource(KMR.strings.upscaling_overlay_size_medium),
            2 to stringResource(KMR.strings.upscaling_overlay_size_large),
        ).forEach { (value, label) ->
            FilterChip(
                selected = overlaySize == value,
                onClick = { preferences.enhancementOverlaySize().set(value) },
                label = { Text(label) },
            )
        }
    }

    val overlayOpacity by preferences.enhancementOverlayOpacity().collectAsState()
    SliderItem(
        value = overlayOpacity,
        valueRange = 10..100,
        label = stringResource(KMR.strings.upscaling_overlay_opacity),
        onChange = { preferences.enhancementOverlayOpacity().set(it) },
    )

    val overlayMargin by preferences.enhancementOverlayMarginDp().collectAsState()
    SettingsChipRow(KMR.strings.upscaling_overlay_margin) {
        listOf(4, 8, 12, 16, 20, 24).forEach { margin ->
            FilterChip(
                selected = overlayMargin == margin,
                onClick = { preferences.enhancementOverlayMarginDp().set(margin) },
                label = { Text("${margin}dp") },
            )
        }
    }
}

@Composable
private fun ChapterUpscaleProgress() {
    val pageRecords by ImageEnhancer.pageRecords.collectAsState()
    if (pageRecords.isEmpty()) return

    HeadingItem(stringResource(KMR.strings.upscaling_chapter_progress))
    val expandedPages = remember { mutableStateMapOf<String, Boolean>() }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    pageRecords.forEach { record ->
        val key = "${record.pageIndex}_${record.pageVariant}"
        PageRecordRow(
            record = record,
            isExpanded = expandedPages[key] ?: false,
            onToggleExpanded = { expandedPages[key] = !(expandedPages[key] ?: false) },
            timeFormatter = timeFormatter,
        )
    }
}

@Composable
private fun PageRecordRow(
    record: PageUpscaleRecord,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    timeFormatter: DateTimeFormatter,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (record.pageVariant.isNotEmpty()) {
                    stringResource(KMR.strings.upscaling_page_number_variant, record.pageIndex + 1, record.pageVariant)
                } else {
                    stringResource(KMR.strings.upscaling_page_number, record.pageIndex + 1)
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            val (statusColor, statusLabel) = when (record.status) {
                PageUpscaleStatus.QUEUED ->
                    MaterialTheme.colorScheme.outline to stringResource(KMR.strings.upscaling_status_queued)
                PageUpscaleStatus.PROCESSING ->
                    MaterialTheme.colorScheme.primary to stringResource(KMR.strings.upscaling_status_processing)
                PageUpscaleStatus.DONE ->
                    UpscalingSuccessColor to stringResource(KMR.strings.upscaling_status_done)
                PageUpscaleStatus.FAILED ->
                    MaterialTheme.colorScheme.error to stringResource(KMR.strings.upscaling_status_failed)
            }
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
            )
            if (record.durationMs > 0) {
                Text(
                    text = "${record.durationMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (record.status == PageUpscaleStatus.PROCESSING) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsItemsPaddings.Horizontal)
                    .height(2.dp),
            )
        }
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier.padding(
                    start = SettingsItemsPaddings.Horizontal + 16.dp,
                    end = SettingsItemsPaddings.Horizontal,
                    top = 2.dp,
                    bottom = 6.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (record.model.isNotEmpty()) {
                    Text(
                        text = stringResource(KMR.strings.upscaling_detail_model, record.model),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (record.batchId > 0) {
                    Text(
                        text = stringResource(KMR.strings.upscaling_detail_batch, record.batchId, record.batchSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (record.enqueuedMs > 0) {
                    val time = timeFormatter.format(
                        Instant.ofEpochMilli(record.enqueuedMs).atZone(ZoneId.systemDefault()).toLocalTime(),
                    )
                    Text(
                        text = stringResource(KMR.strings.upscaling_detail_enqueued, time),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (record.error != null) {
                    Text(
                        text = stringResource(KMR.strings.upscaling_detail_error, record.error),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
