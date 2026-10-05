package jp.co.updates.swingcheck.ui.list

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import jp.co.updates.swingcheck.AppContainer
import jp.co.updates.swingcheck.R
import jp.co.updates.swingcheck.data.AnalysisStatus
import jp.co.updates.swingcheck.data.SwingEntity
import jp.co.updates.swingcheck.ui.common.BackButton
import jp.co.updates.swingcheck.ui.common.rememberSwingDateFormat
import jp.co.updates.swingcheck.ui.debug.DEBUG_TOOLS_ENABLED
import jp.co.updates.swingcheck.ui.debug.rememberVideoImportLauncher
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SwingListScreen(container: AppContainer, onBack: () -> Unit, onOpenSwing: (Long) -> Unit) {
    val vm: SwingListViewModel = viewModel(
        factory = viewModelFactory { initializer { SwingListViewModel(container.swingRepository) } },
    )
    val swings by vm.swings.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val selecting = selected.isNotEmpty()
    val dateFormat = rememberSwingDateFormat()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    BackHandler(enabled = selecting) { vm.clearSelection() }

    val notReadyMessage = stringResource(R.string.list_not_ready)
    val importFailedMessage = stringResource(R.string.list_import_failed)
    val importVideo = rememberVideoImportLauncher(
        container.videoImporter,
        onStarted = { importing = true },
        onFinished = { ok ->
            importing = false
            if (!ok) scope.launch { snackbar.showSnackbar(importFailedMessage) }
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selecting) stringResource(R.string.list_selected_count, selected.size)
                        else stringResource(R.string.list_title),
                    )
                },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = vm::clearSelection) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.list_cancel_selection))
                        }
                    } else {
                        BackButton(onBack)
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.list_delete))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (DEBUG_TOOLS_ENABLED && !selecting) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = importVideo, enabled = !importing) { Text(stringResource(R.string.list_import_video)) }
                    if (importing) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.list_importing))
                    }
                }
            }
            val list = swings
            when {
                list == null -> Unit
                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.list_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { swing ->
                        SwingRow(
                            swing = swing,
                            dateText = dateFormat.format(swing.createdAt),
                            selecting = selecting,
                            isSelected = swing.id in selected,
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    when {
                                        selecting -> vm.toggle(swing.id)
                                        swing.analysisStatus == AnalysisStatus.DONE -> onOpenSwing(swing.id)
                                        else -> scope.launch {
                                            snackbar.currentSnackbarData?.dismiss()
                                            snackbar.showSnackbar(notReadyMessage)
                                        }
                                    }
                                },
                                onLongClick = { if (!selecting) vm.startSelection(swing.id) else vm.toggle(swing.id) },
                            ),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val count = selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.list_delete_title)) },
            text = { Text(pluralStringResource(R.plurals.list_delete_message, count, count)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSelected()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun SwingRow(
    swing: SwingEntity,
    dateText: String,
    selecting: Boolean,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = isSelected, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
        }
        Text(dateText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        val status = when (swing.analysisStatus) {
            AnalysisStatus.PENDING -> R.string.list_status_pending
            AnalysisStatus.RUNNING -> R.string.list_status_running
            AnalysisStatus.FAILED -> R.string.list_status_failed
            AnalysisStatus.DONE -> null
        }
        if (status != null) {
            Text(
                stringResource(status),
                style = MaterialTheme.typography.bodyMedium,
                color = if (swing.analysisStatus == AnalysisStatus.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
