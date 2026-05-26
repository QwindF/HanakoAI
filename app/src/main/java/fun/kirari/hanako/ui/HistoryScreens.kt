@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package `fun`.kirari.hanako.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.data.AppSettings
import `fun`.kirari.hanako.data.AutomationActionType
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.ProcessingStatus
import `fun`.kirari.hanako.data.decodeHistoryBitmap
import `fun`.kirari.hanako.overlay.MarkdownLatexText
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
        formatHistorySize(settings.history.sumOf { it.screenshotBase64?.length ?: 0 })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
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
                    historyStorageText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onClearHistory,
                    enabled = settings.history.isNotEmpty()
                ) {
                    Text("清空")
                }
            }
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
        AlertDialog(
            onDismissRequest = { deleteTargetId = null },
            title = { Text("删除历史记录") },
            text = { Text("确认删除 ${deleteTarget.assistantName} 的这条记录？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteHistoryItem(deleteTarget.id)
                        deleteTargetId = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetId = null }) {
                    Text("取消")
                }
            },
            icon = {
                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }
        )
    }
}

@Composable
private fun HistoryListItem(
    result: ProcessingResult,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val thumbnail = remember(result.screenshotBase64) {
        result.screenshotBase64?.decodeHistoryBitmap()
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
fun HistoryDetailScreen(result: ProcessingResult?) {
    if (result == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "未找到该历史记录。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val screenshot = remember(result.screenshotBase64) {
        result.screenshotBase64?.decodeHistoryBitmap()
    }
    val context = LocalContext.current
    var previewImage by remember(screenshot) { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = formatHistoryDetailHeader(result),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        screenshot?.let { bitmap ->
            item {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { previewImage = true },
                            onLongClick = {
                                val saved = saveBitmapToPictures(context, bitmap, "hanako_history_${result.id}.png")
                                Toast.makeText(
                                    context,
                                    if (saved) "已保存图片" else "保存失败",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        ),
                    contentScale = ContentScale.FillWidth
                )
            }
        }
        if (result.route == ProcessingRoute.OCR_THEN_LLM) {
            item {
                HistoryResultCard(
                    title = "OCR 结果",
                    action = {
                        TextButton(
                            onClick = {
                                copyToClipboard(context, "Hanako OCR 原文", result.extractedText)
                                Toast.makeText(context, "已复制 OCR 原文", Toast.LENGTH_SHORT).show()
                            },
                            enabled = result.extractedText.isNotBlank()
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("复制原文")
                        }
                    }
                ) {
                    if (result.extractedText.isNotBlank()) {
                        MarkdownLatexText(
                            content = result.extractedText,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("暂无内容")
                    }
                }
            }
        }
        if (result.detail.isNotBlank()) {
            item {
                HistoryResultCard(title = "请求详情") {
                    Text(result.detail)
                }
            }
        }
        if (result.events.isNotEmpty()) {
            item {
                HistoryResultCard(title = "处理步骤") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        result.events.forEach { event ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(event.title, fontWeight = FontWeight.SemiBold)
                                if (event.detail.isNotBlank()) {
                                    Text(
                                        event.detail,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (result.automationAction != null || result.automationThought.isNotBlank()) {
            item {
                HistoryResultCard(title = "思考过程") {
                    if (result.automationThought.isNotBlank()) {
                        MarkdownLatexText(
                            content = result.automationThought,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("暂无内容")
                    }
                }
            }
            item {
                val actionText = result.automationAction?.text.orEmpty()
                HistoryResultCard(
                    title = "工具调用",
                    action = {
                        TextButton(
                            onClick = {
                                copyToClipboard(context, "Hanako 自动模式工具内容", actionText)
                                Toast.makeText(context, "已复制工具内容", Toast.LENGTH_SHORT).show()
                            },
                            enabled = actionText.isNotBlank()
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("复制内容")
                        }
                    }
                ) {
                    Text("调用了工具：${automationActionLabel(result)}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(actionText.ifBlank { "暂无内容" })
                }
            }
        } else {
            item {
                HistoryResultCard(
                    title = "答案",
                    action = {
                        TextButton(
                            onClick = {
                                copyToClipboard(context, "Hanako 原始答案", result.answer)
                                Toast.makeText(context, "已复制原文", Toast.LENGTH_SHORT).show()
                            },
                            enabled = result.answer.isNotBlank()
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("复制原文")
                        }
                    }
                ) {
                    if (result.answer.isNotBlank()) {
                        MarkdownLatexText(
                            content = result.answer,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("暂无内容")
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    if (previewImage && screenshot != null) {
        AlertDialog(
            onDismissRequest = { previewImage = false },
            confirmButton = {
                TextButton(onClick = { previewImage = false }) {
                    Text("关闭")
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                ) {
                    Image(
                        bitmap = screenshot.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        )
    }
}

private fun historyPreviewText(result: ProcessingResult): String {
    return when {
        result.detail.isNotBlank() && result.status != ProcessingStatus.SUCCESS -> result.detail
        result.automationAction != null -> "${automationActionLabel(result)}：${result.automationAction.text}"
        result.answer.isNotBlank() -> result.answer
        else -> "暂无回答"
    }
}

@Composable
private fun historyStatusColor(status: ProcessingStatus): Color {
    return when (status) {
        ProcessingStatus.RUNNING -> MaterialTheme.colorScheme.primary
        ProcessingStatus.SUCCESS -> MaterialTheme.colorScheme.tertiary
        ProcessingStatus.ERROR -> MaterialTheme.colorScheme.error
        ProcessingStatus.TIMEOUT -> MaterialTheme.colorScheme.error
    }
}

private fun ProcessingStatus.displayName(): String = when (this) {
    ProcessingStatus.RUNNING -> "进行中"
    ProcessingStatus.SUCCESS -> "成功"
    ProcessingStatus.ERROR -> "失败"
    ProcessingStatus.TIMEOUT -> "超时"
}

private fun automationActionLabel(result: ProcessingResult): String {
    return when (result.automationAction?.type) {
        AutomationActionType.SET_CLIPBOARD -> "设置剪贴板"
        AutomationActionType.SHOW_BUBBLE_LETTERS -> "显示悬浮球字母"
        null -> "未调用工具"
    }
}

@Composable
private fun HistoryResultCard(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                action?.invoke()
            }
            content()
        }
    }
}

private fun ProcessingRoute.displayName(): String = when (this) {
    ProcessingRoute.OCR_THEN_LLM -> "OCR"
    ProcessingRoute.MULTIMODAL_DIRECT -> "多模态"
}

private fun formatHistoryDetailHeader(result: ProcessingResult): String {
    val providerOrModel = result.modelSummary
        .substringAfter('（', missingDelimiterValue = "")
        .substringBefore('）')
        .ifBlank { result.modelSummary }

    return buildList {
        add(result.assistantName)
        add(result.route.displayName())
        providerOrModel.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(" · ")
}

private fun formatHistorySize(charCount: Int): String {
    val bytes = charCount.toLong()
    if (bytes < 1024L) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1fKB", kb)
    val mb = kb / 1024.0
    return String.format(java.util.Locale.US, "%.1fMB", mb)
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun saveBitmapToPictures(context: Context, bitmap: Bitmap, fileName: String): Boolean {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Hanako")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return runCatching {
        resolver.openOutputStream(uri)?.use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        } ?: error("openOutputStream failed")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    }.getOrElse {
        resolver.delete(uri, null, null)
        false
    }
}
