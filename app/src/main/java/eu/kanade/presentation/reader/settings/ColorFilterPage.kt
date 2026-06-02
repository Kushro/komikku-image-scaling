package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import eu.kanade.tachiyomi.ui.reader.setting.ConnectionStatus
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.Companion.ColorFilterMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun ColorFilterPage(screenModel: ReaderSettingsScreenModel) {
    val customBrightness by screenModel.preferences.customBrightness().collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_brightness),
        pref = screenModel.preferences.customBrightness(),
    )

    /*
     * Sets the brightness of the screen. Range is [-75, 100].
     * From -75 to -1 a semi-transparent black view is shown at the top with the minimum brightness.
     * From 1 to 100 it sets that value as brightness.
     * 0 sets system brightness and hides the overlay.
     */
    if (customBrightness) {
        val customBrightnessValue by screenModel.preferences.customBrightnessValue().collectAsState()
        SliderItem(
            value = customBrightnessValue,
            valueRange = -75..100,
            steps = 0,
            label = stringResource(MR.strings.pref_custom_brightness),
            onChange = { screenModel.preferences.customBrightnessValue().set(it) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

    val colorFilter by screenModel.preferences.colorFilter().collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_color_filter),
        pref = screenModel.preferences.colorFilter(),
    )
    if (colorFilter) {
        val colorFilterValue by screenModel.preferences.colorFilterValue().collectAsState()
        SliderItem(
            value = colorFilterValue.red,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_r_value),
            onChange = { newRValue ->
                screenModel.preferences.colorFilterValue().getAndSet {
                    getColorValue(it, newRValue, RED_MASK, 16)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.green,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_g_value),
            onChange = { newGValue ->
                screenModel.preferences.colorFilterValue().getAndSet {
                    getColorValue(it, newGValue, GREEN_MASK, 8)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.blue,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_b_value),
            onChange = { newBValue ->
                screenModel.preferences.colorFilterValue().getAndSet {
                    getColorValue(it, newBValue, BLUE_MASK, 0)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.alpha,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_a_value),
            onChange = { newAValue ->
                screenModel.preferences.colorFilterValue().getAndSet {
                    getColorValue(it, newAValue, ALPHA_MASK, 24)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )

        val colorFilterMode by screenModel.preferences.colorFilterMode().collectAsState()
        SettingsChipRow(MR.strings.pref_color_filter_mode) {
            ColorFilterMode.mapIndexed { index, it ->
                FilterChip(
                    selected = colorFilterMode == index,
                    onClick = { screenModel.preferences.colorFilterMode().set(index) },
                    label = { Text(stringResource(it.first)) },
                )
            }
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_grayscale),
        pref = screenModel.preferences.grayscale(),
    )
    CheckboxItem(
        label = stringResource(MR.strings.pref_inverted_colors),
        pref = screenModel.preferences.invertedColors(),
    )

    // KMK --> Image enhancement — 4-option mode selector
    val enhancementMode by screenModel.preferences.enhancementMode().collectAsState()

    HeadingItem(stringResource(KMR.strings.reader_image_enhancement))
    SettingsChipRow(KMR.strings.reader_enhancement_mode) {
        listOf(
            0 to stringResource(KMR.strings.reader_enhancement_none),
            1 to stringResource(KMR.strings.reader_enhancement_download_only),
            2 to stringResource(KMR.strings.reader_enhancement_live),
            3 to stringResource(KMR.strings.reader_enhancement_remote),
        ).forEach { (value, label) ->
            FilterChip(
                selected = enhancementMode == value,
                onClick = { screenModel.preferences.enhancementMode().set(value) },
                label = { Text(label) },
            )
        }
    }

    if (enhancementMode == 3) {
        // Remote mode: show server config + connection check + force re-upscale
        Text(
            text = stringResource(KMR.strings.reader_remote_upscaler_model_info),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val remoteHost by screenModel.preferences.remoteUpscalerHost().collectAsState()
            OutlinedTextField(
                modifier = Modifier.weight(2f),
                value = remoteHost,
                onValueChange = { screenModel.preferences.remoteUpscalerHost().set(it) },
                label = { Text(stringResource(KMR.strings.reader_remote_upscaler_host)) },
                placeholder = { Text("192.168.1.42") },
                singleLine = true,
            )
            val remotePort by screenModel.preferences.remoteUpscalerPort().collectAsState()
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = remotePort.toString(),
                onValueChange = { s ->
                    s.toIntOrNull()?.let { screenModel.preferences.remoteUpscalerPort().set(it) }
                },
                label = { Text(stringResource(KMR.strings.reader_remote_upscaler_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        }

        // Connection check row
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
            when (connectionStatus) {
                ConnectionStatus.Checking -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                is ConnectionStatus.Ok -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                    )
                    val modelName = (connectionStatus as ConnectionStatus.Ok).modelName
                    if (modelName != null) {
                        Text(
                            text = stringResource(KMR.strings.reader_remote_model_active, modelName),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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

        // Force re-upscale button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 4.dp),
        ) {
            OutlinedButton(onClick = { screenModel.forceReupscale() }) {
                Text(stringResource(KMR.strings.reader_force_reupscale))
            }
        }
    } else if (enhancementMode == 1 || enhancementMode == 2) {
        // Local mode: show model selection
        val realCuganModel by screenModel.preferences.realCuganModel().collectAsState()
        val realCuganNoiseLevel by screenModel.preferences.realCuganNoiseLevel().collectAsState()
        val realCuganScale by screenModel.preferences.realCuganScale().collectAsState()

        SettingsChipRow(KMR.strings.reader_model) {
            listOf("Real-CUGAN SE", "Real-CUGAN Pro", "Real-ESRGAN", "Real-CUGAN Nose", "Waifu2x", "Waifu2x (Fast)").mapIndexed { index, name ->
                FilterChip(
                    selected = realCuganModel == index,
                    onClick = { screenModel.preferences.realCuganModel().set(index) },
                    label = { Text(name) },
                )
            }
        }

        if (realCuganModel == 0 || realCuganModel == 1 || realCuganModel == 4 || realCuganModel == 5) {
            val levels = if (realCuganModel == 1) {
                listOf(0 to stringResource(KMR.strings.reader_none), 3 to "3x", 4 to stringResource(KMR.strings.reader_conservative))
            } else if (realCuganModel == 4) {
                listOf(0 to "1x", 1 to "2x", 2 to "3x")
            } else if (realCuganModel == 5) {
                listOf(0 to stringResource(KMR.strings.reader_none), 1 to "1x", 2 to "2x", 3 to "3x")
            } else {
                listOf(0 to stringResource(KMR.strings.reader_none), 1 to "1x", 2 to "2x", 3 to "3x", 4 to stringResource(KMR.strings.reader_conservative))
            }

            SettingsChipRow(KMR.strings.reader_denoise_level) {
                levels.map { (index, name) ->
                    FilterChip(
                        selected = realCuganNoiseLevel == index,
                        onClick = { screenModel.preferences.realCuganNoiseLevel().set(index) },
                        label = { Text(name) },
                    )
                }
            }
        }

        if (realCuganModel == 3 || realCuganModel == 4 || realCuganModel == 5) {
            SettingsChipRow(KMR.strings.reader_scale_factor) {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text(stringResource(KMR.strings.reader_scale_fixed_2x)) },
                )
            }
        } else if (realCuganModel == 1) {
            SettingsChipRow(KMR.strings.reader_scale_factor) {
                listOf(2, 3).map { scale ->
                    FilterChip(
                        selected = realCuganScale == scale,
                        onClick = { screenModel.preferences.realCuganScale().set(scale) },
                        label = { Text("${scale}x") },
                    )
                }
            }
        } else {
            SettingsChipRow(KMR.strings.reader_scale_factor) {
                listOf(2, 3, 4).map { scale ->
                    FilterChip(
                        selected = realCuganScale == scale,
                        onClick = { screenModel.preferences.realCuganScale().set(scale) },
                        label = { Text("${scale}x") },
                    )
                }
            }
        }

        SettingsChipRow(KMR.strings.reader_preload_pages) {
            listOf(1, 2, 3, 5, 8).map { size ->
                val realCuganPreloadSize by screenModel.preferences.realCuganPreloadSize().collectAsState()
                FilterChip(
                    selected = realCuganPreloadSize == size,
                    onClick = { screenModel.preferences.realCuganPreloadSize().set(size) },
                    label = { Text(stringResource(KMR.strings.reader_preload_pages_value, size)) },
                )
            }
        }

        SettingsChipRow(KMR.strings.reader_gpu_performance_mode) {
            val performanceMode by screenModel.preferences.realCuganPerformanceMode().collectAsState()
            listOf(
                0 to stringResource(KMR.strings.reader_gpu_performance_high),
                1 to stringResource(KMR.strings.reader_gpu_performance_balanced),
                2 to stringResource(KMR.strings.reader_gpu_performance_power_saving),
            ).map { (value, name) ->
                FilterChip(
                    selected = performanceMode == value,
                    onClick = { screenModel.preferences.realCuganPerformanceMode().set(value) },
                    label = { Text(name) },
                )
            }
        }

        Column {
            HeadingItem(stringResource(KMR.strings.reader_target_resolution))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val maxWidth by screenModel.preferences.realCuganMaxSizeWidth().collectAsState()
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = maxWidth.toString(),
                    onValueChange = { s ->
                        s.toIntOrNull()?.let { screenModel.preferences.realCuganMaxSizeWidth().set(it) }
                    },
                    label = { Text(stringResource(KMR.strings.reader_target_width)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                val maxHeight by screenModel.preferences.realCuganMaxSizeHeight().collectAsState()
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = maxHeight.toString(),
                    onValueChange = { s ->
                        s.toIntOrNull()?.let { screenModel.preferences.realCuganMaxSizeHeight().set(it) }
                    },
                    label = { Text(stringResource(KMR.strings.reader_target_height)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        }
    }

    if (enhancementMode != 0) {
        CheckboxItem(
            label = stringResource(KMR.strings.reader_show_processing_status),
            pref = screenModel.preferences.realCuganShowStatus(),
        )
    }
    // KMK <--
}

private fun getColorValue(currentColor: Int, color: Int, mask: Long, bitShift: Int): Int {
    return (color shl bitShift) or (currentColor and mask.inv().toInt())
}
private const val ALPHA_MASK: Long = 0xFF000000
private const val RED_MASK: Long = 0x00FF0000
private const val GREEN_MASK: Long = 0x0000FF00
private const val BLUE_MASK: Long = 0x000000FF
