package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.waifu2x.EnhancementMode
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.RemoteUpscaleStrategy
import eu.kanade.tachiyomi.util.waifu2x.RemoteUpscaler
import eu.kanade.tachiyomi.util.waifu2x.UpscaleStats
import eu.kanade.tachiyomi.util.waifu2x.isUsableRemoteUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.ByteArrayOutputStream

/**
 * Holder of the webtoon reader for a single page of a chapter.
 *
 * @param frame the root view for this holder.
 * @param viewer the webtoon viewer.
 * @constructor creates a new webtoon holder.
 */
class WebtoonPageHolder(
    private val frame: ReaderPageImageView,
    viewer: WebtoonViewer,
    // KMK -->
    @ColorInt private val seedColor: Int? = null,
    // KMK <--
) : WebtoonBaseHolder(frame, viewer) {

    /**
     * Loading progress bar to indicate the current progress.
     */
    private val progressIndicator = createProgressIndicator()

    /**
     * Progress bar container. Needed to keep a minimum height size of the holder, otherwise the
     * adapter would create more views to fill the screen, which is not wanted.
     */
    private lateinit var progressContainer: ViewGroup

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    /**
     * Getter to retrieve the height of the recycler view.
     */
    private val parentHeight
        get() = viewer.recycler.height

    /**
     * Page of a chapter.
     */
    private var page: ReaderPage? = null

    private val scope = MainScope()

    /**
     * Job for loading the page.
     */
    private var loadJob: Job? = null

    // KMK --> Background remote enhancement job
    private var remoteEnhanceJob: Job? = null
    // KMK <--

    init {
        refreshLayoutParams()

        frame.onImageLoaded = { onImageDecoded() }
        frame.onImageLoadError = { error -> setError(error) }
        frame.onScaleChanged = { viewer.activity.hideMenu() }
    }

    /**
     * Binds the given [page] with this view holder, subscribing to its state.
     */
    fun bind(page: ReaderPage) {
        this.page = page
        loadJob?.cancel()
        loadJob = scope.launch { loadPageAndProcessStatus() }
        refreshLayoutParams()
    }

    private fun refreshLayoutParams() {
        frame.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            if (!viewer.isContinuous) {
                bottomMargin = 15.dpToPx
            }

            val margin = Resources.getSystem().displayMetrics.widthPixels * (viewer.config.sidePadding / 100f)
            marginEnd = margin.toInt()
            marginStart = margin.toInt()
        }
    }

    /**
     * Called when the view is recycled and added to the view pool.
     */
    override fun recycle() {
        loadJob?.cancel()
        loadJob = null
        // KMK -->
        remoteEnhanceJob?.cancel()
        remoteEnhanceJob = null
        // KMK <--
        removeErrorLayout()
        frame.recycle()
        progressIndicator.setProgress(0)
        progressContainer.isVisible = true
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if there is no page or the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val page = page ?: return
        val loader = page.chapter.pageLoader ?: return
        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading
     */
    private fun setDownloading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        progressIndicator.setProgress(0)

        val streamFn = page?.stream ?: return

        try {
            val (source, isAnimated) = withIOContext {
                val source = streamFn().use { process(Buffer().readFrom(it)) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                Pair(source, isAnimated)
            }
            // KMK --> Determine enhancement mode and handle remote "show original first"
            val prefs = viewer.activity.viewModel.readerPreferences
            val enhancementMode = prefs.enhancementMode().get()
            // When "only upscale when downloading" is on, the reader never enhances live.
            val liveEnhancement = enhancementMode != EnhancementMode.NONE && !prefs.enhanceOnDownload().get()
            val mangaId = page?.chapter?.chapter?.manga_id ?: -1L
            val chapterId = page?.chapter?.chapter?.id ?: -1L
            val pageIndex = page?.index ?: -1
            val pageVariant = page?.enhancementKeySuffix ?: ""
            val alreadyUpscaled = page?.alreadyUpscaled ?: false

            val isRemoteMode = enhancementMode == EnhancementMode.REMOTE && liveEnhancement && !alreadyUpscaled

            val cropBorders = (viewer.config.imageCropBorders && viewer.isContinuous) ||
                (viewer.config.continuousCropBorders && !viewer.isContinuous)

            // Every display path below shares the same viewer Config; only the enhancement flags differ.
            fun imageConfig(enhanced: Boolean, alreadyUpscaled: Boolean) = ReaderPageImageView.Config(
                zoomDuration = viewer.config.doubleTapAnimDuration,
                minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
                cropBorders = cropBorders,
                enhanced = enhanced,
                mangaId = mangaId,
                chapterId = chapterId,
                pageIndex = pageIndex,
                alreadyUpscaled = alreadyUpscaled,
            )

            if (isRemoteMode) {
                val remoteHost = prefs.remoteUpscalerHost().get()
                val remotePort = prefs.remoteUpscalerPort().get()
                // URL strategies ask the server to download the source image. The visible page
                // always upscales individually (batch is handled by the prefetch queue), so both
                // URL strategies use the single-URL path here.
                val remoteStrategy = prefs.remoteUpscaleStrategy().get()
                val remoteUrl = if (remoteStrategy == RemoteUpscaleStrategy.URL || remoteStrategy == RemoteUpscaleStrategy.BATCH_URL) {
                    page?.imageUrl?.takeIf { it.isUsableRemoteUrl() }
                } else {
                    null
                }
                ImageEnhancementCache.init(frame.context)
                val configHash = ImageEnhancementCache.getRemoteConfigHash(remoteHost, remotePort)
                val cachedFile = ImageEnhancementCache.getCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant)

                if (cachedFile != null) {
                    // KMK -->
                    UpscaleStats.recordCacheHit()
                    // KMK <--
                    val cachedSource = Buffer().readFrom(cachedFile.inputStream())
                    withUIContext {
                        frame.setImage(cachedSource, false, imageConfig(enhanced = false, alreadyUpscaled = true))
                        removeErrorLayout()
                    }
                } else {
                    val sourceBytes = source.readByteArray()
                    withUIContext {
                        frame.setImage(Buffer().write(sourceBytes), isAnimated, imageConfig(enhanced = false, alreadyUpscaled = false))
                        removeErrorLayout()
                    }
                    remoteEnhanceJob?.cancel()
                    remoteEnhanceJob = scope.launch(Dispatchers.IO) {
                        try {
                            val statusCb: suspend (String) -> Unit = { msg ->
                                viewer.activity.viewModel.updateProcessingStatus(msg)
                            }
                            // KMK --> Defer to the prefetch queue when it already owns this page,
                            // instead of firing a duplicate server request for every bound holder.
                            // Focused (visible) pages skip this and keep the fast individual path.
                            val queuedResult = ImageEnhancer.awaitQueuedResult(
                                mangaId,
                                chapterId,
                                pageIndex,
                                configHash,
                                pageVariant = pageVariant,
                                timeoutMs = if (remoteStrategy == RemoteUpscaleStrategy.BATCH_IMAGE || remoteStrategy == RemoteUpscaleStrategy.BATCH_URL) {
                                    120_000L
                                } else {
                                    45_000L
                                },
                            )
                            if (queuedResult != null) {
                                val cachedSource = Buffer().readFrom(queuedResult.inputStream())
                                withUIContext {
                                    frame.setImage(cachedSource, false, imageConfig(enhanced = false, alreadyUpscaled = true))
                                }
                                return@launch
                            }
                            // KMK <--
                            // Try the server-side download first for URL strategies; fall back to
                            // sending the decoded bitmap when no usable URL or the server can't fetch it.
                            val sourceHeaders = viewer.activity.viewModel.getSourceHeaders()
                            // KMK -->
                            val enhanceStart = System.currentTimeMillis()
                            // KMK <--
                            val enhanced: Bitmap? = if (remoteUrl != null) {
                                RemoteUpscaler.processUrl(remoteUrl, remoteHost, remotePort, sourceHeaders, statusCb)
                            } else {
                                null
                            } ?: run {
                                val input = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
                                    ?: return@launch
                                RemoteUpscaler.process(input, remoteHost, remotePort, statusCb)
                            }
                            if (enhanced != null) {
                                // Tall upscaled pages can exceed the WebP/GL texture limits —
                                // clamp before encoding or compress() fails silently.
                                val result = ImageEnhancementCache.clampToDisplayLimits(enhanced)
                                val saved = ImageEnhancementCache.saveToCache(mangaId, chapterId, pageIndex, configHash, result, pageVariant)
                                // Reuse the cached WebP for display instead of compressing twice.
                                val displaySource = if (saved != null) {
                                    Buffer().readFrom(saved.inputStream())
                                } else {
                                    val baos = ByteArrayOutputStream()
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        result.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, baos)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        result.compress(Bitmap.CompressFormat.WEBP, 90, baos)
                                    }
                                    Buffer().write(baos.toByteArray())
                                }
                                withUIContext {
                                    frame.setImage(displaySource, false, imageConfig(enhanced = false, alreadyUpscaled = true))
                                }
                                // KMK -->
                                UpscaleStats.recordEnhanced(UpscaleStats.MODE_REMOTE, System.currentTimeMillis() - enhanceStart)
                                // KMK <--
                            }
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) { "WebtoonPageHolder: Remote enhancement failed for page $pageIndex" }
                        } finally {
                            viewer.activity.viewModel.updateProcessingStatus(null)
                        }
                    }
                }
            } else {
                withUIContext {
                    frame.setImage(
                        source,
                        isAnimated,
                        imageConfig(
                            enhanced = enhancementMode == EnhancementMode.LOCAL && liveEnhancement,
                            alreadyUpscaled = alreadyUpscaled,
                        ),
                    )
                    removeErrorLayout()
                }
            }
            // KMK <--
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    private fun process(imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (viewer.config.dualPageSplit) {
            val isDoublePage = ImageUtil.isWideImage(imageSource)
            if (isDoublePage) {
                val upperSide = if (viewer.config.dualPageInvert) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT
                return ImageUtil.splitAndMerge(imageSource, upperSide)
            }
        }

        return imageSource
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressContainer.isVisible = false
        initErrorLayout(error)
    }

    /**
     * Called when the image is decoded and going to be displayed.
     */
    private fun onImageDecoded() {
        progressContainer.isVisible = false
        removeErrorLayout()
    }

    /**
     * Creates a new progress bar.
     */
    private fun createProgressIndicator(): ReaderProgressIndicator {
        progressContainer = FrameLayout(context)
        frame.addView(progressContainer, MATCH_PARENT, parentHeight)

        val progress = ReaderProgressIndicator(
            context,
            // KMK -->
            seedColor = seedColor,
            // KMK <--
        ).apply {
            updateLayoutParams<FrameLayout.LayoutParams> {
                updateMargins(top = parentHeight / 4)
            }
        }
        progressContainer.addView(progress)
        return progress
    }

    /**
     * Initializes a button to retry pages.
     */
    private fun initErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), frame, true)
            errorLayout?.root?.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, (parentHeight * 0.8).toInt())
            errorLayout?.actionRetry?.setOnClickListener {
                page?.let { it.chapter.pageLoader?.retryPage(it) }
            }
        }

        val imageUrl = page?.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.let {
            frame.removeView(it.root)
            errorLayout = null
        }
    }
}
