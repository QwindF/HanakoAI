package `fun`.kirari.hanako.feature.history.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.platform.clipboard.copyToClipboardWithToast
import `fun`.kirari.hanako.core.model.ProcessingResult
import `fun`.kirari.hanako.core.model.ProcessingRoute
import `fun`.kirari.hanako.core.model.displayedAnswerVersions
import `fun`.kirari.hanako.core.model.latestAnswerText
import `fun`.kirari.hanako.core.model.decodeHistoryBitmap
import `fun`.kirari.hanako.core.model.loadHistoryBitmap
import `fun`.kirari.hanako.core.ui.richtext.MarkdownLatexText
import `fun`.kirari.hanako.feature.home.presentation.RegisterScrollToTopHandler
import `fun`.kirari.hanako.core.ui.components.AnswerActionBar
import `fun`.kirari.hanako.core.ui.components.AnswerSwitchDirection
import `fun`.kirari.hanako.core.ui.components.AnimatedAnswerVersionContent
import `fun`.kirari.hanako.core.ui.image.ImagePreviewOverlay
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun HistoryDetailScreen(
    scrollRoute: String,
    result: ProcessingResult?,
    regenerating: Boolean = false,
    chatSending: Boolean = false,
    runningAnswerVersionIndex: Int? = null,
    onRegenerate: ((ProcessingResult) -> Unit)? = null,
    onSendFollowUp: ((String) -> Unit)? = null,
    onRetryFollowUp: ((Int) -> Unit)? = null
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
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var currentVersionIndex by remember(result.id, answerVersions.size) {
        mutableStateOf((answerVersions.size - 1).coerceAtLeast(0))
    }
    var switchDirection by remember { mutableStateOf(AnswerSwitchDirection.NONE) }
    var followUpDraft by remember(result.id) { mutableStateOf("") }
    var pendingRetryIndex by remember { mutableStateOf<Int?>(null) }
    var confirmOriginalRegeneration by remember { mutableStateOf(false) }
    LaunchedEffect(regenerating, runningAnswerVersionIndex, answerVersions.size) {
        if (regenerating && answerVersions.isNotEmpty()) {
            currentVersionIndex = runningAnswerVersionIndex
                ?.coerceIn(0, answerVersions.lastIndex)
                ?: answerVersions.lastIndex
        }
    }
    RegisterScrollToTopHandler(route = scrollRoute) {
        coroutineScope.launch {
            listState.animateScrollToItem(0)
        }
    }
    val displayedAnswer = answerVersions.getOrNull(currentVersionIndex)?.text ?: result.latestAnswerText()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 132.dp),
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
            result.automationAction?.let { action ->
                item {
                    val actionText = action.text
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
            }
        } else {
            item {
                HistoryResultCard(
                    title = "答案",
                    action = {
                        AnswerActionBar(
                            versionCount = answerVersions.size,
                            currentVersionIndex = currentVersionIndex,
                            canRegenerate = onRegenerate != null && !chatSending,
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
                                if (result.followUpTurns.isEmpty()) {
                                    onRegenerate?.invoke(result)
                                } else {
                                    confirmOriginalRegeneration = true
                                }
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
        if (result.followUpTurns.isNotEmpty()) {
            item {
                Text(
                    text = "继续对话",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            itemsIndexed(result.followUpTurns, key = { _, turn -> turn.id }) { index, turn ->
                HistoryChatTurn(
                    turn = turn,
                    sending = chatSending && index == result.followUpTurns.lastIndex,
                    retryEnabled = !chatSending && onRetryFollowUp != null,
                    onRetry = {
                        if (index == result.followUpTurns.lastIndex) {
                            onRetryFollowUp?.invoke(index)
                        } else {
                            pendingRetryIndex = index
                        }
                    }
                )
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
        }

        HistoryChatComposer(
            value = followUpDraft,
            enabled = !chatSending && !regenerating && onSendFollowUp != null,
            sending = chatSending,
            onValueChange = { followUpDraft = it },
            onSend = {
                val prompt = followUpDraft.trim()
                if (prompt.isNotBlank()) {
                    followUpDraft = ""
                    onSendFollowUp?.invoke(prompt)
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    pendingRetryIndex?.let { retryIndex ->
        AlertDialog(
            onDismissRequest = { pendingRetryIndex = null },
            title = { Text("重新生成这轮回答？") },
            text = { Text("这会删除该轮之后的所有对话，然后使用当前模型配置重新回答。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRetryIndex = null
                    onRetryFollowUp?.invoke(retryIndex)
                }) { Text("删除并重试") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRetryIndex = null }) { Text("取消") }
            }
        )
    }

    if (confirmOriginalRegeneration) {
        AlertDialog(
            onDismissRequest = { confirmOriginalRegeneration = false },
            title = { Text("重新生成首轮回答？") },
            text = { Text("这会删除全部后续对话，然后使用当前模型配置重新生成首轮答案。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmOriginalRegeneration = false
                    onRegenerate?.invoke(result)
                }) { Text("删除并重试") }
            },
            dismissButton = {
                TextButton(onClick = { confirmOriginalRegeneration = false }) { Text("取消") }
            }
        )
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
private fun HistoryChatTurn(
    turn: `fun`.kirari.hanako.core.model.FollowUpTurn,
    sending: Boolean,
    retryEnabled: Boolean,
    onRetry: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .align(Alignment.End)
                .widthIn(max = 320.dp)
        ) {
            Text(
                text = turn.userText,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .align(Alignment.Start)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = turn.modelSummary.ifBlank { "AI" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onRetry,
                        enabled = retryEnabled,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "重新生成此轮",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (turn.assistantText.isNotBlank()) {
                    HistoryMarkdownOrEmpty(turn.assistantText)
                } else if (sending || !turn.completed) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在回答", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                turn.errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryChatComposer(
    value: String,
    enabled: Boolean,
    sending: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .imePadding(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                placeholder = { Text("继续提问") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )
            IconButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank(),
                modifier = Modifier.size(48.dp)
            ) {
                if (sending) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
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

private fun searchStatusText(events: List<`fun`.kirari.hanako.core.model.ProcessingEvent>): String? {
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
