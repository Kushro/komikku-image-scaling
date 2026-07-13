package eu.kanade.presentation.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.util.waifu2x.EnhancementOverlayType
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

// KMK -->
/**
 * Compact reader overlay reporting live upscaling progress. Every variant has a bounded
 * width so the position preference (corner alignment) is actually visible — a fillMaxWidth
 * child here would stretch the overlay across the whole screen edge.
 *
 * @param type one of [EnhancementOverlayType] (callers must not pass OFF)
 * @param sizeLevel 0=small, 1=medium, 2=large
 */
@Composable
fun UpscalingStatusOverlay(
    type: Int,
    sizeLevel: Int,
    opacityPct: Int,
    status: ReaderViewModel.PreloadStatus,
    hasError: Boolean,
    processingStatus: String?,
    modifier: Modifier = Modifier,
) {
    val progress = if (status.max > 0) status.loaded.toFloat() / status.max else 0f
    val complete = status.max > 0 && status.loaded >= status.max

    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = opacityPct / 100f)
    val progressColor = when {
        hasError -> MaterialTheme.colorScheme.error
        complete -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val textStyle = when (sizeLevel) {
        0 -> MaterialTheme.typography.labelSmall
        2 -> MaterialTheme.typography.bodySmall
        else -> MaterialTheme.typography.labelMedium
    }
    val barWidth = when (sizeLevel) {
        0 -> 72.dp
        2 -> 140.dp
        else -> 104.dp
    }
    val barHeight = when (sizeLevel) {
        0 -> 3.dp
        2 -> 5.dp
        else -> 4.dp
    }

    when (type) {
        EnhancementOverlayType.BAR -> OverlayPill(surfaceColor, hasError, modifier) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .width(barWidth)
                    .height(barHeight),
                color = progressColor,
            )
        }

        EnhancementOverlayType.COUNTER -> OverlayPill(surfaceColor, hasError, modifier) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .width(barWidth)
                        .height(barHeight),
                    color = progressColor,
                )
                Text(
                    text = if (hasError) "⚠ ${status.loaded}/${status.max}" else "${status.loaded}/${status.max}",
                    style = textStyle,
                    color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        EnhancementOverlayType.DETAILED -> Surface(
            color = surfaceColor,
            shape = RoundedCornerShape(10.dp),
            border = if (hasError) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
            modifier = modifier,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = when {
                        hasError -> stringResource(KMR.strings.upscaling_overlay_error)
                        complete -> stringResource(KMR.strings.upscaling_overlay_done)
                        else -> stringResource(KMR.strings.upscaling_overlay_progress, status.loaded, status.max)
                    },
                    style = textStyle,
                    color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(barHeight),
                    color = progressColor,
                )
                if (processingStatus != null) {
                    Text(
                        text = processingStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        EnhancementOverlayType.RING -> {
            val ringSize = when (sizeLevel) {
                0 -> 36.dp
                2 -> 56.dp
                else -> 44.dp
            }
            Surface(
                color = surfaceColor,
                shape = CircleShape,
                border = if (hasError) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
                modifier = modifier,
            ) {
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(ringSize),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(ringSize),
                        color = progressColor,
                        strokeWidth = barHeight,
                    )
                    Text(
                        text = if (hasError) "⚠" else "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** Rounded pill wrapper shared by the bar/counter variants. */
@Composable
private fun OverlayPill(
    surfaceColor: androidx.compose.ui.graphics.Color,
    hasError: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        color = surfaceColor,
        shape = RoundedCornerShape(50),
        border = if (hasError) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
        modifier = modifier,
    ) {
        Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            content()
        }
    }
}
// KMK <--
