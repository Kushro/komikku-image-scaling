package eu.kanade.tachiyomi.util.waifu2x

// KMK -->
/**
 * Enhancement pipeline modes, persisted in `ReaderPreferences.enhancementMode()`.
 * Legacy mode 1 (DOWNLOAD_ONLY) was removed; it migrates to [LOCAL] + `enhanceOnDownload()`.
 */
object EnhancementMode {
    const val NONE = 0
    const val LOCAL = 2
    const val REMOTE = 3
}

/**
 * How images reach the remote upscaler, persisted in `ReaderPreferences.remoteUpscaleStrategy()`.
 * Batch strategies are driven by the prefetch queue; the visible page always uses a
 * single-image/url request for instant feedback.
 */
object RemoteUpscaleStrategy {
    /** One request per image (POST /upscale). */
    const val IMAGE = 0

    /** Several images per request (POST /upscale/batch). */
    const val BATCH_IMAGE = 1

    /** The server downloads the image itself (POST /upscale/url). */
    const val URL = 2

    /** The server downloads a batch of URLs itself (POST /upscale/batch/url). */
    const val BATCH_URL = 3
}

/** Local NCNN model catalog; the index is the `ReaderPreferences.realCuganModel()` value. */
object UpscaleModels {
    const val REAL_CUGAN_SE = 0
    const val REAL_CUGAN_PRO = 1
    const val REAL_ESRGAN = 2
    const val REAL_CUGAN_NOSE = 3
    const val WAIFU2X = 4
    const val WAIFU2X_FAST = 5

    val names = listOf(
        "Real-CUGAN SE",
        "Real-CUGAN Pro",
        "Real-ESRGAN",
        "Real-CUGAN Nose",
        "Waifu2x",
        "Waifu2x (Fast)",
    )

    fun displayName(index: Int): String = names.getOrElse(index) { names[REAL_CUGAN_SE] }
}

/**
 * True when the URL is something the remote upscaler server could download by itself:
 * http(s) and not a device-local placeholder some sources use for streamed pages.
 */
fun String.isUsableRemoteUrl(): Boolean =
    startsWith("http", ignoreCase = true) && !contains("127.0.0.1") && !contains("localhost")
// KMK <--
