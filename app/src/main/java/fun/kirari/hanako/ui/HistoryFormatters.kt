package `fun`.kirari.hanako.ui

import `fun`.kirari.hanako.data.AutomationActionType
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.ProcessingStatus
import java.io.File
import java.util.Locale

internal fun historyPreviewText(result: ProcessingResult): String {
    return when {
        result.detail.isNotBlank() && result.status != ProcessingStatus.SUCCESS -> result.detail
        result.automationAction != null -> "${automationActionLabel(result)}：${result.automationAction.text}"
        result.answer.isNotBlank() -> result.answer
        else -> "暂无回答"
    }
}

internal fun automationActionLabel(result: ProcessingResult): String {
    return when (result.automationAction?.type) {
        AutomationActionType.SET_CLIPBOARD -> "设置剪贴板"
        AutomationActionType.SHOW_BUBBLE_LETTERS -> "显示悬浮球字母"
        null -> "未调用工具"
    }
}

internal fun ProcessingRoute.displayName(): String = when (this) {
    ProcessingRoute.OCR_THEN_LLM -> "OCR"
    ProcessingRoute.MULTIMODAL_DIRECT -> "多模态"
}

internal fun formatHistoryDetailHeader(result: ProcessingResult): String {
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

internal fun historyStorageBytes(results: List<ProcessingResult>): Long {
    val fileBytes = results.sumOf { result ->
        result.screenshotPath?.let { path ->
            runCatching { File(path).length() }.getOrDefault(0)
        } ?: 0
    }
    val base64Bytes = results.sumOf { it.screenshotBase64?.length ?: 0 }.toLong()
    return fileBytes + base64Bytes
}

internal fun formatHistorySize(bytes: Long): String {
    if (bytes < 1024L) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1fKB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1fMB", mb)
}
