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
    object Checking : ConnectionStatus()
    data class Ok(val modelName: String?) : ConnectionStatus()
    object Failed : ConnectionStatus()
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
            val ok = RemoteUpscaler.checkHealth(host, port)
            if (ok) {
                val statusMap = RemoteUpscaler.checkStatus(host, port)
                val modelName = statusMap?.get("model_display_name") as? String
                _connectionStatus.value = ConnectionStatus.Ok(modelName)
            } else {
                _connectionStatus.value = ConnectionStatus.Failed
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
            val configHash = ImageEnhancementCache.getConfigHash(
                noise = 0,
                scale = 0,
                inputScale = 100,
                model = -1,
                maxWidth = 0,
                maxHeight = 0,
                resizeEnabled = false,
                remoteHash = "$host:$port",
            )
            ImageEnhancementCache.clearForChapter(mangaId, chapterId, configHash)
            // Rebuild the viewer so the now-uncached pages re-run the remote upscale pipeline.
            onRequestReload()
        }
    }
    // KMK <--
}
