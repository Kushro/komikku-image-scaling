package eu.kanade.presentation.library.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.theme.header

/**
 * Header row for a [eu.kanade.tachiyomi.ui.library.LibrarySection]. Level 1 (primary grouping)
 * gets a filled background and bolder text; level 2+ (secondary grouping) is indented and plain.
 */
@Composable
fun LibraryGroupHeader(
    title: String,
    count: Int,
    level: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(if (collapsed) -90f else 0f, label = "group_header_chevron")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let {
                if (level <= 1) it.background(MaterialTheme.colorScheme.surfaceContainerLow) else it
            }
            .combinedClickable(onClick = onToggle, onLongClick = onLongClick)
            .padding(
                start = MaterialTheme.padding.medium + ((level - 1).coerceAtLeast(0) * 16).dp,
                end = MaterialTheme.padding.medium,
                top = MaterialTheme.padding.small,
                bottom = MaterialTheme.padding.small,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = null,
            modifier = Modifier.rotate(rotation),
        )
        Spacer(Modifier.width(MaterialTheme.padding.small))
        Text(
            text = title,
            style = if (level <= 1) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.header
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "($count)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
