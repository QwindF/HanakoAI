@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package `fun`.kirari.hanako.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.data.AppSettings
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.decodeHistoryBitmap
import `fun`.kirari.hanako.data.loadHistoryBitmap
import `fun`.kirari.hanako.ui.components.SectionCard

@Composable
fun HistorySubScreen(
    settings: AppSettings,
    onClearHistory: () -> Unit,
    onDeleteHistoryItem: (String) -> Unit,
    onOpenHistoryDetail: (String) -> Unit
) {
    var deleteTargetId by remember { mutableStateOf<String?>(null) }
    val historyStorageText = remember(settings.history) {
        formatHistorySize(historyStorageBytes(settings.history))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HistoryListHeader(
                storageText = historyStorageText,
                clearEnabled = settings.history.isNotEmpty(),
                onClearHistory = onClearHistory
            )
        }
        if (settings.history.isEmpty()) {
            item {
                SectionCard(title = "暂无历史") {
                    Text(
                        "悬浮窗处理过的记录会显示在这里。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(settings.history, key = { it.id }) { result ->
                HistoryListItem(
                    result = result,
                    onClick = { onOpenHistoryDetail(result.id) },
                    onLongClick = { deleteTargetId = result.id }
                )
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    val deleteTarget = settings.history.firstOrNull { it.id == deleteTargetId }
    if (deleteTarget != null) {
        DeleteHistoryDialog(
            result = deleteTarget,
            onDismiss = { deleteTargetId = null },
            onConfirm = {
                onDeleteHistoryItem(deleteTarget.id)
                deleteTargetId = null
            }
        )
    }
}

@Composable
private fun HistoryListHeader(
    storageText: String,
    clearEnabled: Boolean,
    onClearHistory: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "历史记录",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            storageText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = onClearHistory,
            enabled = clearEnabled
        ) {
            Text("清空")
        }
    }
}

@Composable
private fun HistoryListItem(
    result: ProcessingResult,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val thumbnail = remember(result.screenshotPath, result.screenshotBase64) {
        result.screenshotPath?.loadHistoryBitmap()
            ?: result.screenshotBase64?.decodeHistoryBitmap()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    result.assistantName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "模式：${result.route.displayName()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "状态：${result.status.displayName()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = historyStatusColor(result.status)
                )
                Text(
                    text = historyPreviewText(result),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                modifier = Modifier.size(width = 84.dp, height = 112.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteHistoryDialog(
    result: ProcessingResult,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除历史记录") },
        text = { Text("确认删除 ${result.assistantName} 的这条记录？") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        icon = {
            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        }
    )
}
