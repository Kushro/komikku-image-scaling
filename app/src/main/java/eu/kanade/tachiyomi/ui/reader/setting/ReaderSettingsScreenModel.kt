package eu.kanade.tachiyomi.ui.reader.setting

import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.RemoteUpscaler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

sealed class ConnectionStatus {
    data object Checking : ConnectionStatus()
    data class Ok(val modelName: String?) : ConnectionStatus()

    /** Server reachable, but reports it can't upscale yet (missing binaries/model). */
    data object NotReady : ConnectionStatus()
    data object Failed : ConnectionStatus()
}

class ReaderSettingsScreenModel(
    private val readerState: StateFlow<ReaderViewModel.State>,
    val onChangeReadingMode: (ReadingMode) -> Unit,
    val onChangeOrientation: (ReaderOrientation) -> Unit,
    val preferences: ReaderPreferences = Injekt.get(),
    // KMK --> Triggers a viewer reload so cleared pages re-run enhancement (e.g. force re-upscale)
    val onRequestReload: () -> Unit = {},
    // KMK <--
) : ScreenModel {

    val viewerFlow = readerState
        .map { it.viewer }
        .distinctUntilChanged()
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, null)

    val mangaFlow = readerState
        .map { it.manga }
        .distinctUntilChanged()
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, null)

    // KMK --> Remote upscaler connection check
    private val _connectionStatus = MutableStateFlow<ConnectionStatus?>(null)
    val connectionStatus: StateFlow<ConnectionStatus?> = _connectionStatus.asStateFlow()

    fun checkRemoteConnection() {
        ioCoroutineScope.launch {
            _connectionStatus.value = ConnectionStatus.Checking
            val host = preferences.remoteUpscalerHost().get()
            val port = preferences.remoteUpscalerPort().get()
            val statusMap = RemoteUpscaler.checkStatus(host, port)
            _connectionStatus.value = when {
                statusMap == null -> ConnectionStatus.Failed
                statusMap["upscaler_ready"] as? Boolean == false -> ConnectionStatus.NotReady
                else -> ConnectionStatus.Ok(statusMap["model_display_name"] as? String)
            }
        }
    }

    fun forceReupscale() {
        ioCoroutineScope.launch {
            val state = readerState.value
            val mangaId = state.manga?.id ?: return@launch
            val chapterId = state.currentChapter?.chapter?.id ?: return@launch
            val host = preferences.remoteUpscalerHost().get()
            val port = preferences.remoteUpscalerPort().get()
            val configHash = ImageEnhancementCache.getRemoteConfigHash(host, port)
            ImageEnhancementCache.clearForChapter(mangaId, chapterId, configHash)
            // Rebuild the viewer so the now-uncached pages re-run the remote upscale pipeline.
            onRequestReload()
        }
    }
    // KMK <--
}
