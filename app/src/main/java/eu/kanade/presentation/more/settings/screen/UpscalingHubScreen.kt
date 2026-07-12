package eu.kanade.presentation.more.settings.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.waifu2x.EnhancerState
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.RemoteUpscaler
import eu.kanade.tachiyomi.util.waifu2x.UpscaleStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.plus
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class UpscalingHubScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { HubScreenModel(context) }

        val enhancementMode by screenModel.enhancementMode.collectAsState()
        val serverStatus by screenModel.serverStatus.collectAsState()
        val testUpscaleResult by screenModel.testUpscaleResult.collectAsState()
        val enhancerState by ImageEnhancer.enhancerState.collectAsState()
        val cacheSize by screenModel.cacheSize.collectAsState()
        val cacheFiles by screenModel.cacheFiles.collectAsState()
        val maxCacheMb by screenModel.maxCacheMb.collectAsState()
        val showClearConfirm by screenModel.showClearConfirm.collectAsState()
        val statsSession by UpscaleStats.session.collectAsState()
        val lifetimeRemote by screenModel.lifetimeRemote.collectAsState()
        val lifetimeLocal by screenModel.lifetimeLocal.collectAsState()

        LaunchedEffect(maxCacheMb) {
            ImageEnhancementCache.maxCacheSizeMb = maxCacheMb
        }

        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { screenModel.showClearConfirm.value = false },
                title = { Text(stringResource(KMR.strings.upscaling_hub_clear_cache)) },
                text = {
                    Text(
                        stringResource(
                            KMR.strings.upscaling_hub_clear_cache_confirm,
                            formatBytes(cacheSize),
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { screenModel.clearCache(context) }) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { screenModel.showClearConfirm.value = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KMR.strings.upscaling_hub_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            LazyColumn(
                contentPadding = contentPadding + PaddingValues(all = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (enhancementMode == 3) {
                    item {
                        ServerStatusCard(
                            serverStatus = serverStatus,
                            testUpscaleResult = testUpscaleResult,
                            onTestConnection = { screenModel.testConnection() },
                            onTestUpscale = { screenModel.testUpscale() },
                        )
                    }
                }
                item {
                    ProgressCard(state = enhancerState)
                }
                item {
                    StatsCard(
                        session = statsSession,
                        lifetimeRemote = lifetimeRemote,
                        lifetimeLocal = lifetimeLocal,
                    )
                }
                item {
                    CacheCard(
                        cacheSize = cacheSize,
                        cacheFiles = cacheFiles,
                        maxCacheMb = maxCacheMb,
                        onClearClick = { screenModel.showClearConfirm.value = true },
                        onLimitChange = { screenModel.preferences.enhancementCacheMaxSizeMb().set(it) },
                    )
                }
                item {
                    ConfigCard(preferences = screenModel.preferences)
                }
            }
        }
    }

    @Composable
    private fun ServerStatusCard(
        serverStatus: HubScreenModel.ServerStatus,
        testUpscaleResult: String?,
        onTestConnection: () -> Unit,
        onTestUpscale: () -> Unit,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(KMR.strings.upscaling_hub_server_section),
                    style = MaterialTheme.typography.titleMedium,
                )

                val (statusText, statusColor) = when (serverStatus) {
                    HubScreenModel.ServerStatus.Loading -> "…" to MaterialTheme.colorScheme.onSurfaceVariant
                    HubScreenModel.ServerStatus.Unreachable ->
                        stringResource(KMR.strings.upscaling_hub_server_unreachable) to MaterialTheme.colorScheme.error
                    is HubScreenModel.ServerStatus.NotReady ->
                        stringResource(KMR.strings.upscaling_hub_server_not_ready) to MaterialTheme.colorScheme.error
                    is HubScreenModel.ServerStatus.Ready -> {
                        val binariesReady = serverStatus.statusMap["binaries_ready"] as? Boolean ?: true
                        if (!binariesReady) {
                            stringResource(KMR.strings.upscaling_hub_binaries_missing) to MaterialTheme.colorScheme.error
                        } else {
                            stringResource(KMR.strings.upscaling_hub_server_ready) to MaterialTheme.colorScheme.primary
                        }
                    }
                }
                Text(text = statusText, color = statusColor, style = MaterialTheme.typography.bodyMedium)

                if (serverStatus is HubScreenModel.ServerStatus.Ready) {
                    val map = serverStatus.statusMap
                    val uptime = map["uptime_seconds"]
                    val requests = map["requests_processed"]
                    val model = map["model"]
                    val scale = map["scale"]
                    val noise = map["noise"]
                    val gpu = map["gpu"]

                    if (uptime != null) {
                        Text(
                            text = stringResource(KMR.strings.upscaling_hub_uptime, formatUptime(uptime)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (requests != null) {
                        Text(
                            text = stringResource(KMR.strings.upscaling_hub_requests_processed, requests.toString()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (model != null || scale != null || noise != null || gpu != null) {
                        Text(
                            text = buildString {
                                if (model != null) append("Model: $model")
                                if (scale != null) append(" · Scale: $scale")
                                if (noise != null) append(" · Noise: $noise")
                                if (gpu != null) append(" · GPU: $gpu")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (testUpscaleResult != null) {
                    Text(
                        text = testUpscaleResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onTestConnection) {
                        Text(stringResource(KMR.strings.upscaling_hub_test_connection))
                    }
                    if (serverStatus is HubScreenModel.ServerStatus.Ready) {
                        OutlinedButton(onClick = onTestUpscale) {
                            Text(stringResource(KMR.strings.upscaling_hub_test_upscale))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ProgressCard(state: EnhancerState) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(KMR.strings.upscaling_hub_progress_section),
                    style = MaterialTheme.typography.titleMedium,
                )

                if (state.queueSize == 0 && state.activePage == -1) {
                    Text(
                        text = stringResource(KMR.strings.upscaling_hub_idle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (state.queueSize > 0) {
                    Text(
                        text = stringResource(KMR.strings.upscaling_hub_queued, state.queueSize.toString()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Text(
                    text = stringResource(KMR.strings.upscaling_hub_completed, state.sessionCompleted.toString()),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(KMR.strings.upscaling_hub_failed, state.sessionFailed.toString()),
                    style = MaterialTheme.typography.bodySmall,
                )

                if (state.lastError != null) {
                    Text(
                        text = stringResource(KMR.strings.upscaling_hub_last_error, state.lastError),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    @Composable
    private fun StatsCard(
        session: UpscaleStats.Session,
        lifetimeRemote: Long,
        lifetimeLocal: Long,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(KMR.strings.upscaling_hub_stats_section),
                    style = MaterialTheme.typography.titleMedium,
                )

                Text(
                    text = stringResource(KMR.strings.upscaling_hub_stats_session),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(KMR.strings.upscaling_hub_stats_enhanced, session.pagesEnhanced.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(KMR.strings.upscaling_hub_stats_cache_hits, session.cacheHits.toString()),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (session.pagesEnhanced > 0) {
                    Text(
                        text = stringResource(KMR.strings.upscaling_hub_stats_avg_time, session.avgMs.toString()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (session.bytesIn > 0L || session.bytesOut > 0L) {
                    Text(
                        text = stringResource(
                            KMR.strings.upscaling_hub_stats_bytes,
                            formatBytes(session.bytesIn),
                            formatBytes(session.bytesOut),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(KMR.strings.upscaling_hub_stats_lifetime),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(KMR.strings.upscaling_hub_stats_lifetime_remote, lifetimeRemote.toString()),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(KMR.strings.upscaling_hub_stats_lifetime_local, lifetimeLocal.toString()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    @Composable
    private fun CacheCard(
        cacheSize: Long,
        cacheFiles: Int,
        maxCacheMb: Int,
        onClearClick: () -> Unit,
        onLimitChange: (Int) -> Unit,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(KMR.strings.upscaling_hub_cache_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(KMR.strings.upscaling_hub_cache_size, formatBytes(cacheSize)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(KMR.strings.upscaling_hub_cache_files, cacheFiles.toString()),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(KMR.strings.upscaling_hub_cache_limit),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(512, 1024, 2048, 3 * 1024, 4 * 1024, 6 * 1024, 8 * 1024).forEach { mb ->
                        FilterChip(
                            selected = maxCacheMb == mb,
                            onClick = { onLimitChange(mb) },
                            label = { Text(formatBytes(mb.toLong() * 1024L * 1024L)) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(onClick = onClearClick) {
                    Text(stringResource(KMR.strings.upscaling_hub_clear_cache))
                }
            }
        }
    }

    @Composable
    private fun ConfigCard(preferences: ReaderPreferences) {
        val enhancementMode by preferences.enhancementMode().collectAsState()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(KMR.strings.upscaling_hub_config_section),
                    style = MaterialTheme.typography.titleMedium,
                )

                Text(
                    text = stringResource(KMR.strings.reader_enhancement_mode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        0 to stringResource(KMR.strings.reader_enhancement_none),
                        2 to stringResource(KMR.strings.reader_enhancement_live),
                        3 to stringResource(KMR.strings.reader_enhancement_remote),
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = enhancementMode == value,
                            onClick = { preferences.enhancementMode().set(value) },
                            label = { Text(label) },
                        )
                    }
                }

                if (enhancementMode == 3) {
                    RemoteConfigSection(preferences = preferences)
                } else if (enhancementMode == 2) {
                    LocalConfigSection(preferences = preferences)
                }

                if (enhancementMode != 0) {
                    val enhanceOnDownload by preferences.enhanceOnDownload().collectAsState()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = enhanceOnDownload,
                            onCheckedChange = { preferences.enhanceOnDownload().set(it) },
                        )
                        Text(
                            text = stringResource(KMR.strings.reader_enhance_on_download),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun RemoteConfigSection(preferences: ReaderPreferences) {
        val remoteStrategy by preferences.remoteUpscaleStrategy().collectAsState()

        Text(
            text = stringResource(KMR.strings.reader_remote_strategy),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                0 to stringResource(KMR.strings.reader_remote_strategy_image),
                1 to stringResource(KMR.strings.reader_remote_strategy_batch_image),
                2 to stringResource(KMR.strings.reader_remote_strategy_url),
                3 to stringResource(KMR.strings.reader_remote_strategy_batch_url),
            ).forEach { (value, label) ->
                FilterChip(
                    selected = remoteStrategy == value,
                    onClick = { preferences.remoteUpscaleStrategy().set(value) },
                    label = { Text(label) },
                )
            }
        }
        Text(
            text = stringResource(KMR.strings.reader_remote_strategy_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(KMR.strings.reader_remote_upscaler_model_info),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val remoteHost by preferences.remoteUpscalerHost().collectAsState()
            OutlinedTextField(
                modifier = Modifier.weight(2f),
                value = remoteHost,
                onValueChange = { preferences.remoteUpscalerHost().set(it) },
                label = { Text(stringResource(KMR.strings.reader_remote_upscaler_host)) },
                placeholder = { Text("192.168.1.42") },
                singleLine = true,
            )
            val remotePort by preferences.remoteUpscalerPort().collectAsState()
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = remotePort.toString(),
                onValueChange = { s -> s.toIntOrNull()?.let { preferences.remoteUpscalerPort().set(it) } },
                label = { Text(stringResource(KMR.strings.reader_remote_upscaler_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        }

        Text(
            text = stringResource(KMR.strings.reader_preload_pages),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        val preloadSize by preferences.realCuganPreloadSize().collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 3, 5, 8).forEach { size ->
                FilterChip(
                    selected = preloadSize == size,
                    onClick = { preferences.realCuganPreloadSize().set(size) },
                    label = { Text(stringResource(KMR.strings.reader_preload_pages_value, size)) },
                )
            }
        }
    }

    @Composable
    private fun LocalConfigSection(preferences: ReaderPreferences) {
        val realCuganModel by preferences.realCuganModel().collectAsState()
        val realCuganNoiseLevel by preferences.realCuganNoiseLevel().collectAsState()
        val realCuganScale by preferences.realCuganScale().collectAsState()
        val preloadSize by preferences.realCuganPreloadSize().collectAsState()
        val performanceMode by preferences.realCuganPerformanceMode().collectAsState()

        Text(
            text = stringResource(KMR.strings.reader_model),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("Real-CUGAN SE", "Real-CUGAN Pro", "Real-ESRGAN", "Real-CUGAN Nose", "Waifu2x", "Waifu2x (Fast)").forEachIndexed { index, name ->
                FilterChip(
                    selected = realCuganModel == index,
                    onClick = { preferences.realCuganModel().set(index) },
                    label = { Text(name) },
                )
            }
        }

        if (realCuganModel == 0 || realCuganModel == 1 || realCuganModel == 4 || realCuganModel == 5) {
            val levels = when (realCuganModel) {
                1 -> listOf(0 to stringResource(KMR.strings.reader_none), 3 to "3x", 4 to stringResource(KMR.strings.reader_conservative))
                4 -> listOf(0 to "1x", 1 to "2x", 2 to "3x")
                5 -> listOf(0 to stringResource(KMR.strings.reader_none), 1 to "1x", 2 to "2x", 3 to "3x")
                else -> listOf(0 to stringResource(KMR.strings.reader_none), 1 to "1x", 2 to "2x", 3 to "3x", 4 to stringResource(KMR.strings.reader_conservative))
            }
            Text(
                text = stringResource(KMR.strings.reader_denoise_level),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                levels.forEach { (index, name) ->
                    FilterChip(
                        selected = realCuganNoiseLevel == index,
                        onClick = { preferences.realCuganNoiseLevel().set(index) },
                        label = { Text(name) },
                    )
                }
            }
        }

        Text(
            text = stringResource(KMR.strings.reader_scale_factor),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        when {
            realCuganModel == 3 || realCuganModel == 4 || realCuganModel == 5 -> Row {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text(stringResource(KMR.strings.reader_scale_fixed_2x)) },
                )
            }
            realCuganModel == 1 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 3).forEach { scale ->
                    FilterChip(
                        selected = realCuganScale == scale,
                        onClick = { preferences.realCuganScale().set(scale) },
                        label = { Text("${scale}x") },
                    )
                }
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 3, 4).forEach { scale ->
                    FilterChip(
                        selected = realCuganScale == scale,
                        onClick = { preferences.realCuganScale().set(scale) },
                        label = { Text("${scale}x") },
                    )
                }
            }
        }

        Text(
            text = stringResource(KMR.strings.reader_preload_pages),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 3, 5, 8).forEach { size ->
                FilterChip(
                    selected = preloadSize == size,
                    onClick = { preferences.realCuganPreloadSize().set(size) },
                    label = { Text(stringResource(KMR.strings.reader_preload_pages_value, size)) },
                )
            }
        }

        Text(
            text = stringResource(KMR.strings.reader_gpu_performance_mode),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

        Text(
            text = stringResource(KMR.strings.reader_target_resolution),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val maxWidth by preferences.realCuganMaxSizeWidth().collectAsState()
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = maxWidth.toString(),
                onValueChange = { s -> s.toIntOrNull()?.let { preferences.realCuganMaxSizeWidth().set(it) } },
                label = { Text(stringResource(KMR.strings.reader_target_width)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            val maxHeight by preferences.realCuganMaxSizeHeight().collectAsState()
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = maxHeight.toString(),
                onValueChange = { s -> s.toIntOrNull()?.let { preferences.realCuganMaxSizeHeight().set(it) } },
                label = { Text(stringResource(KMR.strings.reader_target_height)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }

    private fun formatUptime(value: Any?): String {
        val secs = when (value) {
            is Int -> value.toLong()
            is Long -> value
            is Double -> value.toLong()
            is Number -> value.toLong()
            else -> return value.toString()
        }
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return if (h > 0) {
            "${h}h ${m}m"
        } else if (m > 0) {
            "${m}m ${s}s"
        } else {
            "${s}s"
        }
    }

    class HubScreenModel(private val context: Context) : ScreenModel {

        val preferences by lazy { Injekt.get<ReaderPreferences>() }

        val enhancementMode = preferences.enhancementMode().stateIn(ioCoroutineScope)
        val serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Loading)
        val testUpscaleResult = MutableStateFlow<String?>(null)
        val cacheSize = MutableStateFlow(0L)
        val cacheFiles = MutableStateFlow(0)
        val maxCacheMb = preferences.enhancementCacheMaxSizeMb().stateIn(ioCoroutineScope)
        val showClearConfirm = MutableStateFlow(false)
        val lifetimeRemote = preferences.totalPagesEnhancedRemote().stateIn(ioCoroutineScope)
        val lifetimeLocal = preferences.totalPagesEnhancedLocal().stateIn(ioCoroutineScope)

        init {
            ImageEnhancementCache.init(context)
            ioCoroutineScope.launch {
                cacheSize.value = ImageEnhancementCache.cacheSizeBytes()
                cacheFiles.value = ImageEnhancementCache.cacheFileCount()
            }
            ioCoroutineScope.launch {
                enhancementMode.collect { mode ->
                    if (mode == 3) probeServer()
                }
            }
        }

        fun testConnection() {
            ioCoroutineScope.launch {
                probeServer()
            }
        }

        fun testUpscale() {
            ioCoroutineScope.launch {
                testUpscaleResult.value = "…"
                val host = preferences.remoteUpscalerHost().get()
                val port = preferences.remoteUpscalerPort().get()
                val start = System.currentTimeMillis()
                try {
                    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawColor(Color.WHITE)
                    val result = RemoteUpscaler.process(bitmap, host, port)
                    val ms = System.currentTimeMillis() - start
                    testUpscaleResult.value = if (result != null) {
                        "OK — ${ms}ms (${result.width}×${result.height})"
                    } else {
                        "Failed"
                    }
                } catch (e: Exception) {
                    testUpscaleResult.value = "Error: ${e.message}"
                }
            }
        }

        fun clearCache(context: Context) {
            ioCoroutineScope.launch {
                showClearConfirm.value = false
                ImageEnhancementCache.clear(context)
                cacheSize.value = ImageEnhancementCache.cacheSizeBytes()
                cacheFiles.value = ImageEnhancementCache.cacheFileCount()
            }
        }

        private suspend fun probeServer() {
            val host = preferences.remoteUpscalerHost().get()
            val port = preferences.remoteUpscalerPort().get()
            if (host.isBlank()) {
                serverStatus.value = ServerStatus.Unreachable
                return
            }
            serverStatus.value = ServerStatus.Loading
            val statusMap = RemoteUpscaler.checkStatus(host, port)
            serverStatus.value = when {
                statusMap == null -> ServerStatus.Unreachable
                statusMap["upscaler_ready"] as? Boolean != true -> ServerStatus.NotReady(statusMap)
                else -> ServerStatus.Ready(statusMap)
            }
        }

        sealed class ServerStatus {
            object Loading : ServerStatus()
            object Unreachable : ServerStatus()
            data class NotReady(val statusMap: Map<String, Any?>) : ServerStatus()
            data class Ready(val statusMap: Map<String, Any?>) : ServerStatus()
        }
    }
}
