package eu.kanade.presentation.reader.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.PageUpscaleRecord
import eu.kanade.tachiyomi.util.waifu2x.PageUpscaleStatus
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// KMK -->
/**
 * Reader-sheet tab with the per-page upscale records of the current chapter. Only shown
 * when an enhancement mode is active (see ReaderSettingsDialog).
 */
@Composable
internal fun UpscalingProgressPage() {
    val pageRecords by ImageEnhancer.pageRecords.collectAsState()
    if (pageRecords.isEmpty()) {
        Text(
            text = stringResource(KMR.strings.upscaling_hub_idle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 8.dp),
        )
        return
    }

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
// KMK <--
