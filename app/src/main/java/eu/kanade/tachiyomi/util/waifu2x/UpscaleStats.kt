package eu.kanade.tachiyomi.util.waifu2x

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK -->
object UpscaleStats {
    const val MODE_LOCAL = 2
    const val MODE_REMOTE = 3

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val preferences by lazy { Injekt.get<ReaderPreferences>() }

    private val _session = MutableStateFlow(Session())
    val session: StateFlow<Session> = _session.asStateFlow()

    data class Session(
        val pagesEnhanced: Int = 0,
        val cacheHits: Int = 0,
        val totalMs: Long = 0L,
        val bytesIn: Long = 0L,
        val bytesOut: Long = 0L,
    ) {
        val avgMs: Long get() = if (pagesEnhanced > 0) totalMs / pagesEnhanced else 0L
        val cacheHitRate: Float
            get() {
                val d = pagesEnhanced + cacheHits
                return if (d > 0) cacheHits.toFloat() / d else 0f
            }
    }

    fun recordEnhanced(mode: Int, ms: Long = 0L, bytesIn: Long = 0L, bytesOut: Long = 0L) {
        _session.value = _session.value.copy(
            pagesEnhanced = _session.value.pagesEnhanced + 1,
            totalMs = _session.value.totalMs + ms,
            bytesIn = _session.value.bytesIn + bytesIn,
            bytesOut = _session.value.bytesOut + bytesOut,
        )
        scope.launch {
            when (mode) {
                MODE_REMOTE -> preferences.totalPagesEnhancedRemote().let { pref ->
                    pref.set(pref.get() + 1)
                }
                MODE_LOCAL -> preferences.totalPagesEnhancedLocal().let { pref ->
                    pref.set(pref.get() + 1)
                }
            }
        }
    }

    fun recordBatch(mode: Int, count: Int, totalMs: Long = 0L, bytesOut: Long = 0L) {
        if (count <= 0) return
        _session.value = _session.value.copy(
            pagesEnhanced = _session.value.pagesEnhanced + count,
            totalMs = _session.value.totalMs + totalMs,
            bytesOut = _session.value.bytesOut + bytesOut,
        )
        scope.launch {
            when (mode) {
                MODE_REMOTE -> preferences.totalPagesEnhancedRemote().let { pref ->
                    pref.set(pref.get() + count.toLong())
                }
                MODE_LOCAL -> preferences.totalPagesEnhancedLocal().let { pref ->
                    pref.set(pref.get() + count.toLong())
                }
            }
        }
    }

    fun recordCacheHit() {
        _session.value = _session.value.copy(cacheHits = _session.value.cacheHits + 1)
    }

    fun resetSession() {
        _session.value = Session()
    }
}
// KMK <--
