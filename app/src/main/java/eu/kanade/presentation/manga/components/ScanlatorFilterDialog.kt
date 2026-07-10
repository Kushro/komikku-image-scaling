package eu.kanade.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.DisabledByDefault
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ScanlatorFilterDialog(
    availableScanlators: ImmutableSet<String>,
    excludedScanlators: ImmutableSet<String>,
    // KMK -->
    priorityMode: Boolean,
    scanlatorPriorities: ImmutableList<String>,
    // KMK <--
    onDismissRequest: () -> Unit,
    // KMK -->
    onConfirm: (excludedScanlators: Set<String>, priorityMode: Boolean, priorities: List<String>) -> Unit,
    // KMK <--
) {
    val sortedAvailableScanlators = remember(availableScanlators) {
        availableScanlators.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }
    val mutableExcludedScanlators = remember(excludedScanlators) { excludedScanlators.toMutableStateList() }
    // KMK -->
    var mutablePriorityMode by remember(priorityMode) { mutableStateOf(priorityMode) }
    val prioritizedList = remember(availableScanlators) {
        computeInitialPriorityOrder(availableScanlators, excludedScanlators, scanlatorPriorities).toMutableStateList()
    }

    fun toggleExcluded(scanlator: String) {
        if (mutableExcludedScanlators.contains(scanlator)) {
            mutableExcludedScanlators.remove(scanlator)
            if (scanlator !in prioritizedList) prioritizedList.add(scanlator)
        } else {
            mutableExcludedScanlators.add(scanlator)
            prioritizedList.remove(scanlator)
        }
    }
    // KMK <--

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.exclude_scanlators)) },
        text = textFunc@{
            if (sortedAvailableScanlators.isEmpty()) {
                Text(text = stringResource(MR.strings.no_scanlators_found))
                return@textFunc
            }
            Column {
                // Section 1: scanlator selection (include/exclude)
                Box {
                    val state = rememberLazyListState()
                    LazyColumn(
                        state = state,
                        modifier = Modifier.heightIn(max = 240.dp),
                    ) {
                        items(
                            items = sortedAvailableScanlators,
                            contentType = { "item" },
                            key = { it },
                        ) { scanlator ->
                            val isExcluded = mutableExcludedScanlators.contains(scanlator)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable {
                                        // KMK -->
                                        toggleExcluded(scanlator)
                                        // KMK <--
                                    }
                                    .minimumInteractiveComponentSize()
                                    .clip(MaterialTheme.shapes.small)
                                    .fillMaxWidth()
                                    .padding(horizontal = MaterialTheme.padding.small),
                            ) {
                                Icon(
                                    imageVector = if (isExcluded) {
                                        Icons.Rounded.DisabledByDefault
                                    } else {
                                        Icons.Rounded.CheckBoxOutlineBlank
                                    },
                                    tint = if (isExcluded) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        LocalContentColor.current
                                    },
                                    contentDescription = null,
                                )
                                Text(
                                    text = scanlator,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 24.dp),
                                )
                            }
                        }
                    }
                    if (state.canScrollBackward) HorizontalDivider(modifier = Modifier.align(Alignment.TopCenter))
                    if (state.canScrollForward) HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter))
                }

                // KMK -->
                // Section 2: priority mode switch
                HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.padding.small))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { mutablePriorityMode = !mutablePriorityMode }
                        .padding(vertical = MaterialTheme.padding.small),
                ) {
                    Text(
                        text = stringResource(KMR.strings.scanlator_filter_mode_priority),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = mutablePriorityMode, onCheckedChange = { mutablePriorityMode = it })
                }
                if (mutablePriorityMode) {
                    Text(
                        text = stringResource(KMR.strings.scanlator_priority_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = MaterialTheme.padding.small),
                    )

                    // Section 3: drag & drop priority order
                    Text(
                        text = stringResource(KMR.strings.scanlator_priority_section),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = MaterialTheme.padding.small),
                    )
                    val priorityListState = rememberLazyListState()
                    val reorderableState = rememberReorderableLazyListState(priorityListState) { from, to ->
                        prioritizedList.add(to.index, prioritizedList.removeAt(from.index))
                    }
                    LazyColumn(
                        state = priorityListState,
                        modifier = Modifier.heightIn(max = 240.dp),
                    ) {
                        items(
                            items = prioritizedList,
                            key = { it },
                        ) { scanlator ->
                            ReorderableItem(reorderableState, key = scanlator) { _ ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = MaterialTheme.padding.small),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DragHandle,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .padding(MaterialTheme.padding.small)
                                            .draggableHandle(),
                                    )
                                    Text(
                                        text = "${prioritizedList.indexOf(scanlator) + 1}. " +
                                            scanlator.ifEmpty { stringResource(KMR.strings.scanlator_unknown) },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
                // KMK <--
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = true,
        ),
        confirmButton = {
            if (sortedAvailableScanlators.isEmpty()) {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            } else {
                FlowRow {
                    if (mutableExcludedScanlators.isEmpty()) {
                        TextButton(
                            onClick = {
                                mutableExcludedScanlators.addAll(availableScanlators)
                                // KMK -->
                                prioritizedList.clear()
                                // KMK <--
                            },
                        ) {
                            Text(text = stringResource(MR.strings.action_select_all))
                        }
                    } else {
                        TextButton(
                            onClick = {
                                // KMK -->
                                val previouslyExcluded = mutableExcludedScanlators.toList()
                                mutableExcludedScanlators.clear()
                                val newlyIncluded = previouslyExcluded
                                    .filterNot { it in prioritizedList }
                                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
                                prioritizedList.addAll(newlyIncluded)
                                // KMK <--
                            },
                        ) {
                            Text(text = stringResource(MR.strings.action_reset))
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismissRequest) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            // KMK -->
                            onConfirm(mutableExcludedScanlators.toSet(), mutablePriorityMode, prioritizedList.toList())
                            // KMK <--
                            onDismissRequest()
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                }
            }
        },
    )
}

// KMK -->
/**
 * Priority order for scanlators not yet excluded: previously recorded priorities first (filtered to
 * only those still available and not excluded), then any remaining available scanlator alphabetically.
 */
private fun computeInitialPriorityOrder(
    available: Set<String>,
    excluded: Set<String>,
    recorded: List<String>,
): List<String> {
    val included = available - excluded
    val ordered = recorded.filter { it in included }
    val remaining = (included - ordered.toSet()).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    return ordered + remaining
}
// KMK <--
