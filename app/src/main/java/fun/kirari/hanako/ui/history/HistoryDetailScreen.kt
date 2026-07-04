package `fun`.kirari.hanako.ui.history

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.copyToClipboardWithToast
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.displayedAnswerVersions
import `fun`.kirari.hanako.data.latestAnswerText
import `fun`.kirari.hanako.data.decodeHistoryBitmap
import `fun`.kirari.hanako.data.loadHistoryBitmap
import `fun`.kirari.hanako.overlay.text.MarkdownLatexText
import `fun`.kirari.hanako.ui.answer.AnswerActionBar
import `fun`.kirari.hanako.ui.answer.AnswerSwitchDirection
import `fun`.kirari.hanako.ui.answer.AnimatedAnswerVersionContent
import `fun`.kirari.hanako.ui.image.ImagePreviewOverlay
import kotlin.math.roundToInt

@Composable
fun HistoryDetailScreen(
    result: ProcessingResult?,
    regenerating: Boolean = false,
    runningAnswerVersionIndex: Int? = null,
    onRegenerate: ((ProcessingResult) -> Unit)? = null
) {
    if (result == null) {
        MissingHistoryDetail()
        return
    }

    val answerVersions = remember(result.id, result.answerVersions, result.answer) {
        result.displayedAnswerVersions()
    }
    val screenshots = remember(result.allScreenshotPaths, result.screenshotBase64) {
        val bitmaps = result.allScreenshotPaths.mapNotNull { it.loadHistoryBitmap() }.toMutableList()
        if (bitmaps.isEmpty()) {
            result.screenshotBase64?.decodeHistoryBitmap()?.let { bitmaps.add(it) }
        }
        bitmaps
    }
    val context = LocalContext.current
    var previewImageIndex by remember { mutableStateOf(-1) }
    var imageBounds by remember { mutableStateOf<android.graphics.Rect?>(null) }
    var currentVersionIndex by remember(result.id, answerVersions.size) {
        mutableStateOf((answerVersions.size - 1).coerceAtLeast(0))
    }
    var switchDirection by remember { mutableStateOf(AnswerSwitchDirection.NONE) }
    LaunchedEffect(regenerating, runningAnswerVersionIndex, answerVersions.size) {
        if (regenerating && answerVersions.isNotEmpty()) {
            currentVersionIndex = runningAnswerVersionIndex
                ?.coerceIn(0, answerVersions.lastIndex)
                ?: answerVersions.lastIndex
        }
    }
    val displayedAnswer = answerVersions.getOrNull(currentVersionIndex)?.text ?: result.latestAnswerText()

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
        if (screenshots.isNotEmpty()) {
            item {
                HistoryScreenshots(
                    screenshots = screenshots,
                    onSingleImagePositioned = { imageBounds = it },
                    onPreviewImage = { previewImageIndex = it }
                )
            }
        }
        if (result.route == ProcessingRoute.OCR_THEN_LLM) {
            item {
                HistoryResultCard(
                    title = "OCR 结果",
                    action = {
                        CopyTextButton(
                            enabled = result.extractedText.isNotBlank(),
                            label = "复制原文",
                            onClick = {
                                copyToClipboardWithToast(context, "Hanako OCR 原文", result.extractedText, "已复制 OCR 原文")
                            }
                        )
                    }
                ) {
                    HistoryMarkdownOrEmpty(result.extractedText)
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
        if (result.automationAction != null || result.automationThought.isNotBlank()) {
            item {
                HistoryResultCard(title = "思考过程") {
                    HistoryMarkdownOrEmpty(result.automationThought)
                }
            }
            item {
                val actionText = result.automationAction?.text.orEmpty()
                HistoryResultCard(
                    title = "工具调用",
                    action = {
                        CopyTextButton(
                            enabled = actionText.isNotBlank(),
                            label = "复制内容",
                            onClick = {
                                copyToClipboardWithToast(context, "Hanako 自动模式工具内容", actionText, "已复制工具内容")
                            }
                        )
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
                        AnswerActionBar(
                            versionCount = answerVersions.size,
                            currentVersionIndex = currentVersionIndex,
                            canRegenerate = onRegenerate != null,
                            regenerating = regenerating,
                            onPreviousVersion = {
                                if (currentVersionIndex > 0) {
                                    switchDirection = AnswerSwitchDirection.PREVIOUS
                                    currentVersionIndex -= 1
                                }
                            },
                            onNextVersion = {
                                if (currentVersionIndex < answerVersions.lastIndex) {
                                    switchDirection = AnswerSwitchDirection.NEXT
                                    currentVersionIndex += 1
                                }
                            },
                            onCopy = {
                                copyToClipboardWithToast(context, "Hanako 原始答案", displayedAnswer, "已复制原文")
                            },
                            onRegenerate = {
                                onRegenerate?.invoke(result)
                            }
                        )
                    }
                ) {
                    searchStatusText(result.events)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    if (regenerating) {
                        HistoryMarkdownOrEmpty(displayedAnswer)
                    } else {
                        AnimatedAnswerVersionContent(
                            text = displayedAnswer,
                            direction = switchDirection
                        ) { currentText ->
                            HistoryMarkdownOrEmpty(currentText)
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    if (previewImageIndex >= 0 && previewImageIndex < screenshots.size) {
        ImagePreviewOverlay(
            visible = true,
            bitmap = screenshots[previewImageIndex],
            fileName = "hanako_history_${result.id}_$previewImageIndex",
            onDismiss = { previewImageIndex = -1 },
            sourceBounds = imageBounds
        )
    }
}

@Composable
private fun MissingHistoryDetail() {
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
}

@Composable
private fun HistoryScreenshots(
    screenshots: List<android.graphics.Bitmap>,
    onSingleImagePositioned: (android.graphics.Rect) -> Unit,
    onPreviewImage: (Int) -> Unit
) {
    if (screenshots.size == 1) {
        Image(
            bitmap = screenshots[0].asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInWindow()
                    val size = coords.size
                    onSingleImagePositioned(
                        android.graphics.Rect(
                            pos.x.roundToInt(),
                            pos.y.roundToInt(),
                            (pos.x + size.width).roundToInt(),
                            (pos.y + size.height).roundToInt()
                        )
                    )
                }
                .clickable { onPreviewImage(0) },
            contentScale = ContentScale.FillWidth
        )
    } else {
        Column {
            Text(
                text = "截图（${screenshots.size} 张）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                itemsIndexed(screenshots) { index, bitmap ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .width(280.dp)
                            .heightIn(min = 200.dp, max = 400.dp)
                            .clickable { onPreviewImage(index) }
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "截图 ${index + 1}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryMarkdownOrEmpty(content: String) {
    if (content.isNotBlank()) {
        MarkdownLatexText(
            content = content,
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        Text("暂无内容")
    }
}

private fun searchStatusText(events: List<`fun`.kirari.hanako.data.ProcessingEvent>): String? {
    val searchEvent = events.lastOrNull { it.title == "正在联网搜索" || it.title == "联网搜索完成" } ?: return null
    val keyword = Regex("关键词：([^，]+)").find(searchEvent.detail)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    if (keyword.isBlank()) return null
    val count = Regex("获取\\s*(\\d+)\\s*条结果").find(searchEvent.detail)?.groupValues?.getOrNull(1)
    return if (count.isNullOrBlank()) {
        "已搜索 $keyword"
    } else {
        "已搜索 $keyword（共${count}条结果）"
    }
}

@Composable
private fun CopyTextButton(
    enabled: Boolean,
    label: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled
    ) {
        Icon(
            Icons.Default.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(label)
    }
}
