package `fun`.kirari.hanako.ui

import `fun`.kirari.hanako.data.AutomationActionRecord
import `fun`.kirari.hanako.data.AutomationActionType
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.ProcessingStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HistoryFormattersTest {

    @Test
    fun historyPreviewText_prefersErrorDetailForNonSuccessResult() {
        val result = ProcessingResult(
            assistantName = "助手",
            route = ProcessingRoute.OCR_THEN_LLM,
            status = ProcessingStatus.ERROR,
            detail = "处理失败",
            answer = "这条不该优先"
        )

        assertEquals("处理失败", historyPreviewText(result))
    }

    @Test
    fun historyPreviewText_prefersAutomationActionThenAnswerThenFallback() {
        val automation = ProcessingResult(
            assistantName = "助手",
            route = ProcessingRoute.OCR_THEN_LLM,
            automationAction = AutomationActionRecord(
                type = AutomationActionType.SET_CLIPBOARD,
                text = "42"
            ),
            answer = "answer"
        )
        val answerOnly = ProcessingResult(
            assistantName = "助手",
            route = ProcessingRoute.MULTIMODAL_DIRECT,
            answer = "最终答案"
        )
        val empty = ProcessingResult(
            assistantName = "助手",
            route = ProcessingRoute.MULTIMODAL_DIRECT
        )

        assertEquals("设置剪贴板：42", historyPreviewText(automation))
        assertEquals("最终答案", historyPreviewText(answerOnly))
        assertEquals("暂无回答", historyPreviewText(empty))
    }

    @Test
    fun automationActionLabel_coversKnownAndMissingTypes() {
        val clipboard = ProcessingResult(
            assistantName = "助手",
            route = ProcessingRoute.OCR_THEN_LLM,
            automationAction = AutomationActionRecord(AutomationActionType.SET_CLIPBOARD, "1")
        )
        val letters = ProcessingResult(
            assistantName = "助手",
            route = ProcessingRoute.OCR_THEN_LLM,
            automationAction = AutomationActionRecord(AutomationActionType.SHOW_BUBBLE_LETTERS, "AB")
        )
        val none = ProcessingResult(
            assistantName = "助手",
            route = ProcessingRoute.OCR_THEN_LLM
        )

        assertEquals("设置剪贴板", automationActionLabel(clipboard))
        assertEquals("显示悬浮球字母", automationActionLabel(letters))
        assertEquals("未调用工具", automationActionLabel(none))
    }

    @Test
    fun formatHistoryDetailHeader_extractsProviderNameFromModelSummary() {
        val result = ProcessingResult(
            assistantName = "题目解答助手",
            route = ProcessingRoute.MULTIMODAL_DIRECT,
            modelSummary = "gpt-4o（OpenAI）"
        )

        assertEquals("题目解答助手 · 多模态 · OpenAI", formatHistoryDetailHeader(result))
    }

    @Test
    fun formatHistoryDetailHeader_fallsBackToWholeModelSummaryWhenNoParentheses() {
        val result = ProcessingResult(
            assistantName = "题目解答助手",
            route = ProcessingRoute.OCR_THEN_LLM,
            modelSummary = "gpt-4.1-mini"
        )

        assertEquals("题目解答助手 · OCR · gpt-4.1-mini", formatHistoryDetailHeader(result))
    }

    @Test
    fun historyStorageBytes_countsExistingFilesAndBase64Length_only() {
        val file = File.createTempFile("history-formatters", ".txt")
        file.writeText("12345")
        file.deleteOnExit()

        val results = listOf(
            ProcessingResult(
                assistantName = "助手",
                route = ProcessingRoute.OCR_THEN_LLM,
                screenshotPath = file.absolutePath
            ),
            ProcessingResult(
                assistantName = "助手",
                route = ProcessingRoute.OCR_THEN_LLM,
                screenshotPath = file.absolutePath + ".missing"
            ),
            ProcessingResult(
                assistantName = "助手",
                route = ProcessingRoute.OCR_THEN_LLM,
                screenshotBase64 = "abcdef"
            )
        )

        assertEquals(11L, historyStorageBytes(results))
    }

    @Test
    fun formatHistorySize_coversBytesKilobytesAndMegabytes() {
        assertEquals("512B", formatHistorySize(512))
        assertEquals("1.5KB", formatHistorySize(1536))
        assertEquals("2.0MB", formatHistorySize(2L * 1024L * 1024L))
    }
}
