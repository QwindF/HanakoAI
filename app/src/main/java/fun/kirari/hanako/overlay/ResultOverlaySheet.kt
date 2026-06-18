package `fun`.kirari.hanako.overlay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.copyToClipboardWithToast
import `fun`.kirari.hanako.data.ProcessingRoute

@Composable
internal fun ResultOverlaySheet(
    uiState: OverlayUiState,
    onClose: () -> Unit,
    panelHeightPx: Int
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val panelMaxHeight = with(density) { panelHeightPx.toDp() }
    val answerText = uiState.liveAnswerText

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        var closeRequested by remember { mutableStateOf(false) }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(panelMaxHeight)
                .align(Alignment.BottomCenter),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                SheetTitleRow(
                    title = { Text("Hanako") },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, top = 16.dp, end = 12.dp, bottom = 8.dp),
                    onClose = {
                        if (closeRequested) return@SheetTitleRow
                        closeRequested = true
                        onClose()
                    }
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ResultImageCard(uiState)
                    if (uiState.settings.processingRoute == ProcessingRoute.OCR_THEN_LLM) {
                        OcrResultCard(uiState)
                    }
                    AnswerResultCard(
                        answerText = answerText,
                        working = uiState.working
                    )
                    uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        SideEffect { closeRequested }
    }
}

@Composable
private fun ResultImageCard(uiState: OverlayUiState) {
    ResultCard(title = "原图") {
        uiState.selectedBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp)),
                contentScale = ContentScale.FillWidth
            )
        }
    }
}

@Composable
private fun OcrResultCard(uiState: OverlayUiState) {
    val context = LocalContext.current
    ResultCard(
        title = "OCR 结果",
        actions = {
            if (!uiState.working && uiState.liveOcrText.isNotBlank()) {
                SmallHeaderAction(
                    label = "复制原文",
                    onClick = {
                        copyToClipboardWithToast(context, "Hanako OCR 原文", uiState.liveOcrText, "已复制 OCR 原文")
                    }
                )
            }
        }
    ) {
        if (uiState.liveOcrText.isBlank() && uiState.working) {
            LoadingLine("正在识别文字…")
        } else {
            val ocrText = uiState.liveOcrText
            if (ocrText.isNotBlank()) {
                MarkdownLatexText(
                    content = ocrText,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text("暂无内容")
            }
        }
    }
}

@Composable
private fun AnswerResultCard(
    answerText: String,
    working: Boolean
) {
    val context = LocalContext.current
    ResultCard(
        title = "答案",
        actions = {
            if (!working && answerText.isNotBlank()) {
                SmallHeaderAction(
                    label = "复制",
                    onClick = {
                        copyToClipboardWithToast(context, "Hanako 原始答案", answerText, "已复制全文")
                    }
                )
            }
        }
    ) {
        when {
            answerText.isNotBlank() -> MarkdownLatexText(
                content = answerText,
                modifier = Modifier.fillMaxWidth()
            )
            working -> LoadingLine("正在生成答案…")
            else -> Text("暂无内容")
        }
    }
}

@Composable
private fun ResultCard(
    title: String,
    actions: @Composable (RowScope.() -> Unit)? = null,
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
                if (actions != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun SmallHeaderAction(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (active) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun LoadingLine(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp)
        Text(text)
    }
}
