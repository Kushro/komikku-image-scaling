package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.waifu2x.EnhancementMode
import eu.kanade.tachiyomi.util.waifu2x.RemoteUpscaleStrategy
import eu.kanade.tachiyomi.util.waifu2x.UpscaleModels
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

// KMK -->
/** Success green shared by the upscaling status UI (Material 3 has no semantic success color). */
internal val UpscalingSuccessColor = Color(0xFF4CAF50)

/**
 * Shared upscaling configuration controls, used by both the reader sheet tab
 * ([UpscalingPage]) and the Upscaling Hub settings screen. Each composable reads and
 * writes the same [ReaderPreferences], so both entry points always stay in sync.
 */
@Composable
internal fun EnhancementModeSetting(preferences: ReaderPreferences) {
    val enhancementMode by preferences.enhancementMode().collectAsState()
    SettingsChipRow(KMR.strings.reader_enhancement_mode) {
        listOf(
            EnhancementMode.NONE to stringResource(KMR.strings.reader_enhancement_none),
            EnhancementMode.LOCAL to stringResource(KMR.strings.reader_enhancement_live),
            EnhancementMode.REMOTE to stringResource(KMR.strings.reader_enhancement_remote),
        ).forEach { (value, label) ->
            FilterChip(
                selected = enhancementMode == value,
                onClick = { preferences.enhancementMode().set(value) },
                label = { Text(label) },
            )
        }
    }
}

/**
 * Remote mode controls: strategy, server host/port and preload window.
 * [extraContent] renders between the server fields and the preload chips — the reader
 * tab injects its connection-check and force-reupscale rows there.
 */
@Composable
internal fun RemoteUpscalerSettings(
    preferences: ReaderPreferences,
    extraContent: @Composable () -> Unit = {},
) {
    val remoteStrategy by preferences.remoteUpscaleStrategy().collectAsState()
    SettingsChipRow(KMR.strings.reader_remote_strategy) {
        listOf(
            RemoteUpscaleStrategy.IMAGE to stringResource(KMR.strings.reader_remote_strategy_image),
            RemoteUpscaleStrategy.BATCH_IMAGE to stringResource(KMR.strings.reader_remote_strategy_batch_image),
            RemoteUpscaleStrategy.URL to stringResource(KMR.strings.reader_remote_strategy_url),
            RemoteUpscaleStrategy.BATCH_URL to stringResource(KMR.strings.reader_remote_strategy_batch_url),
        ).forEach { (value, label) ->
            FilterChip(
                selected = remoteStrategy == value,
                onClick = { preferences.remoteUpscaleStrategy().set(value) },
                label = { Text(label) },
            )
        }
    }
    SettingsInfoText(KMR.strings.reader_remote_strategy_summary)
    SettingsInfoText(KMR.strings.reader_remote_upscaler_model_info)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val remoteHost by preferences.remoteUpscalerHost().collectAsState()
        OutlinedTextField(
            modifier = Modifier.weight(2f),
            value = remoteHost,
            onValueChange = { preferences.remoteUpscalerHost().set(it.trim()) },
            label = { Text(stringResource(KMR.strings.reader_remote_upscaler_host)) },
            placeholder = { Text("192.168.1.42") },
            singleLine = true,
        )
        IntPreferenceTextField(
            pref = preferences.remoteUpscalerPort(),
            label = stringResource(KMR.strings.reader_remote_upscaler_port),
            modifier = Modifier.weight(1f),
            range = 1..65535,
        )
    }

    extraContent()

    PagePreloadSetting(preferences)
}

/** Local (on-device NCNN) mode controls: model, denoise, scale, preload, GPU and resolution. */
@Composable
internal fun LocalUpscalerSettings(preferences: ReaderPreferences) {
    val realCuganModel by preferences.realCuganModel().collectAsState()

    SettingsChipRow(KMR.strings.reader_model) {
        UpscaleModels.names.forEachIndexed { index, name ->
            FilterChip(
                selected = realCuganModel == index,
                onClick = { preferences.realCuganModel().set(index) },
                label = { Text(name) },
            )
        }
    }

    DenoiseLevelSetting(preferences, realCuganModel)
    ScaleFactorSetting(preferences, realCuganModel)
    PagePreloadSetting(preferences)
    PerformanceModeSetting(preferences)
    TargetResolutionSetting(preferences)
}

@Composable
private fun DenoiseLevelSetting(preferences: ReaderPreferences, model: Int) {
    // Real-ESRGAN and Real-CUGAN Nose have no denoise parameter.
    val levels = when (model) {
        UpscaleModels.REAL_CUGAN_PRO -> listOf(
            0 to stringResource(KMR.strings.reader_none),
            3 to "3x",
            4 to stringResource(KMR.strings.reader_conservative),
        )
        UpscaleModels.WAIFU2X -> listOf(0 to "1x", 1 to "2x", 2 to "3x")
        UpscaleModels.WAIFU2X_FAST -> listOf(
            0 to stringResource(KMR.strings.reader_none),
            1 to "1x",
            2 to "2x",
            3 to "3x",
        )
        UpscaleModels.REAL_CUGAN_SE -> listOf(
            0 to stringResource(KMR.strings.reader_none),
            1 to "1x",
            2 to "2x",
            3 to "3x",
            4 to stringResource(KMR.strings.reader_conservative),
        )
        else -> return
    }

    val realCuganNoiseLevel by preferences.realCuganNoiseLevel().collectAsState()
    SettingsChipRow(KMR.strings.reader_denoise_level) {
        levels.forEach { (value, name) ->
            FilterChip(
                selected = realCuganNoiseLevel == value,
                onClick = { preferences.realCuganNoiseLevel().set(value) },
                label = { Text(name) },
            )
        }
    }
}

@Composable
private fun ScaleFactorSetting(preferences: ReaderPreferences, model: Int) {
    val scales = when (model) {
        // Nose and Waifu2x variants only support 2x.
        UpscaleModels.REAL_CUGAN_NOSE, UpscaleModels.WAIFU2X, UpscaleModels.WAIFU2X_FAST -> emptyList()
        UpscaleModels.REAL_CUGAN_PRO -> listOf(2, 3)
        else -> listOf(2, 3, 4)
    }

    val realCuganScale by preferences.realCuganScale().collectAsState()
    SettingsChipRow(KMR.strings.reader_scale_factor) {
        if (scales.isEmpty()) {
            FilterChip(
                selected = true,
                onClick = {},
                label = { Text(stringResource(KMR.strings.reader_scale_fixed_2x)) },
            )
        } else {
            scales.forEach { scale ->
                FilterChip(
                    selected = realCuganScale == scale,
                    onClick = { preferences.realCuganScale().set(scale) },
                    label = { Text("${scale}x") },
                )
            }
        }
    }
}

/**
 * Quick access to the reader's page-preload amount (the same preference as
 * Settings → Reader → Page downloading). Upscaling has no window of its own — it follows
 * the download frontier — so this is the setting that decides how far ahead it runs.
 */
@Composable
internal fun PagePreloadSetting(preferences: ReaderPreferences) {
    val preloadSize by preferences.preloadSize().collectAsState()
    SettingsChipRow(SYMR.strings.reader_preload_amount) {
        listOf(4, 6, 8, 10, 12, 14, 16, 20).forEach { amount ->
            FilterChip(
                selected = preloadSize == amount,
                onClick = { preferences.preloadSize().set(amount) },
                label = { Text("$amount") },
            )
        }
    }
    SettingsInfoText(KMR.strings.upscaling_follows_page_preload)
}

@Composable
private fun PerformanceModeSetting(preferences: ReaderPreferences) {
    val performanceMode by preferences.realCuganPerformanceMode().collectAsState()
    SettingsChipRow(KMR.strings.reader_gpu_performance_mode) {
        listOf(
            0 to stringResource(KMR.strings.reader_gpu_performance_high),
            1 to stringResource(KMR.strings.reader_gpu_performance_balanced),
            2 to stringResource(KMR.strings.reader_gpu_performance_power_saving),
        ).forEach { (value, name) ->
            FilterChip(
                selected = performanceMode == value,
                onClick = { preferences.realCuganPerformanceMode().set(value) },
                label = { Text(name) },
            )
        }
    }
}

@Composable
private fun TargetResolutionSetting(preferences: ReaderPreferences) {
    Column {
        HeadingItem(KMR.strings.reader_target_resolution)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IntPreferenceTextField(
                pref = preferences.realCuganMaxSizeWidth(),
                label = stringResource(KMR.strings.reader_target_width),
                modifier = Modifier.weight(1f),
            )
            IntPreferenceTextField(
                pref = preferences.realCuganMaxSizeHeight(),
                label = stringResource(KMR.strings.reader_target_height),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun EnhanceOnDownloadSetting(preferences: ReaderPreferences) {
    CheckboxItem(
        label = stringResource(KMR.strings.reader_enhance_on_download),
        pref = preferences.enhanceOnDownload(),
    )
    SettingsInfoText(KMR.strings.reader_enhance_on_download_summary)
}

@Composable
internal fun SettingsInfoText(textRes: StringResource) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Numeric text field bound to an int preference. Keeps a local text state so the field
 * can be cleared while typing (a bare `pref.toString()` binding snaps back on every
 * keystroke and makes the value impossible to erase); only valid in-range values are
 * committed to the preference.
 */
@Composable
private fun IntPreferenceTextField(
    pref: Preference<Int>,
    label: String,
    modifier: Modifier = Modifier,
    range: IntRange = 1..99999,
) {
    val prefValue by pref.collectAsState()
    var text by remember(prefValue) { mutableStateOf(prefValue.toString()) }
    OutlinedTextField(
        modifier = modifier,
        value = text,
        onValueChange = { input ->
            val digits = input.filter { it.isDigit() }
            text = digits
            digits.toIntOrNull()?.takeIf { it in range }?.let(pref::set)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}
// KMK <--
