package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ConnectionStatus
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.util.waifu2x.EnhancementMode
import eu.kanade.tachiyomi.util.waifu2x.EnhancementOverlayType
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

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

        // Page-slider notch showing how far ahead pages are upscaled (the download
        // counterpart lives in the General tab).
        CheckboxItem(
            label = stringResource(KMR.strings.pref_show_upscale_notch),
            pref = preferences.showUpscaleNotch(),
        )

        OverlaySettings(preferences)
    }
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

    // Overlay variant picker — each type is a different design with its own information
    // density, so a dropdown reads better than two separate detail/style chip rows.
    val overlayType by preferences.enhancementOverlayType().collectAsState()
    val typeOptions = listOf(
        EnhancementOverlayType.OFF to stringResource(KMR.strings.upscaling_overlay_type_off),
        EnhancementOverlayType.BAR to stringResource(KMR.strings.upscaling_overlay_type_bar),
        EnhancementOverlayType.COUNTER to stringResource(KMR.strings.upscaling_overlay_type_counter),
        EnhancementOverlayType.DETAILED to stringResource(KMR.strings.upscaling_overlay_type_detailed),
        EnhancementOverlayType.RING to stringResource(KMR.strings.upscaling_overlay_type_ring),
    )
    SelectItem(
        label = stringResource(KMR.strings.upscaling_overlay_type),
        options = typeOptions.map { it.second }.toTypedArray(),
        selectedIndex = typeOptions.indexOfFirst { it.first == overlayType }.coerceAtLeast(0),
        onSelect = { index -> preferences.enhancementOverlayType().set(typeOptions[index].first) },
    )

    if (overlayType <= EnhancementOverlayType.OFF) return

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
        valueString = "$overlayOpacity%",
        onChange = { preferences.enhancementOverlayOpacity().set(it) },
    )

    // Margin as a percentage of the screen so it scales across devices; capped at 15%
    // to keep the overlay on-screen and useful.
    val overlayMarginPct by preferences.enhancementOverlayMarginPct().collectAsState()
    SliderItem(
        value = overlayMarginPct,
        valueRange = 0..15,
        label = stringResource(KMR.strings.upscaling_overlay_margin),
        valueString = "$overlayMarginPct%",
        onChange = { preferences.enhancementOverlayMarginPct().set(it) },
    )
}
