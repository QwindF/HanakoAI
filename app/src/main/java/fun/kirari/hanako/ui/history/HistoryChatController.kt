package `fun`.kirari.hanako.ui.history

import `fun`.kirari.hanako.data.AppSettings
import `fun`.kirari.hanako.data.FollowUpTurn
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.latestAnswerText
import `fun`.kirari.hanako.data.loadHistoryBitmaps
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.network.UnifiedLLMClient
import `fun`.kirari.hanako.overlay.state.OverlayUiState
import `fun`.kirari.hanako.overlay.workflow.ProcessingPipeline
import `fun`.kirari.hanako.overlay.workflow.assistantPromptWithCopyMarker
import `fun`.kirari.hanako.overlay.workflow.textMessage
import `fun`.kirari.hanako.overlay.workflow.toBase64Jpeg
import `fun`.kirari.hanako.overlay.workflow.userMessage
import `fun`.kirari.hanako.runtime.WorkflowTaskManager
import `fun`.kirari.llm.core.ChatMessage
import `fun`.kirari.llm.core.LlmEvent
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class HistoryChatRequestState(
    val sending: Boolean = false,
    val activeTurnId: String? = null
)

internal class HistoryChatController(
    private val scope: CoroutineScope,
    private val settings: StateFlow<AppSettings>,
    private val processingPipeline: ProcessingPipeline,
    private val workflowTaskManager: WorkflowTaskManager,
    private val unifiedLLMClient: UnifiedLLMClient
) {
    private val tag = "HanakoHistoryChat"
    private val jobs = mutableMapOf<String, Job>()
    private val _requestStates = MutableStateFlow<Map<String, HistoryChatRequestState>>(emptyMap())
    val requestStates: StateFlow<Map<String, HistoryChatRequestState>> = _requestStates.asStateFlow()

    fun send(historyId: String, prompt: String) {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank() || jobs[historyId]?.isActive == true) return
        launchTurn(historyId = historyId, prompt = trimmedPrompt, retryIndex = null)
    }

    fun retry(historyId: String, turnIndex: Int) {
        if (jobs[historyId]?.isActive == true) return
        scope.launch {
            val result = workflowTaskManager.latestHistoryResult(historyId) ?: return@launch
            val prompt = result.followUpTurns.getOrNull(turnIndex)?.userText ?: return@launch
            launchTurn(historyId = historyId, prompt = prompt, retryIndex = turnIndex)
        }
    }

    private fun launchTurn(historyId: String, prompt: String, retryIndex: Int?) {
        val turnId = UUID.randomUUID().toString()
        _requestStates.update { it + (historyId to HistoryChatRequestState(true, turnId)) }
        val job = scope.launch {
            try {
                val existing = workflowTaskManager.latestHistoryResult(historyId) ?: return@launch
                val currentSettings = settings.value
                val models = processingPipeline.resolveModels(OverlayUiState(settings = currentSettings))
                    .copy(route = existing.route)
                val provider = when (existing.route) {
                    ProcessingRoute.OCR_THEN_LLM -> {
                        require(models.textModel.isNotBlank()) { "请先在模型设置中配置文本模型" }
                        requireNotNull(models.textProvider) { "请先在模型设置中配置文本模型" }
                    }
                    ProcessingRoute.MULTIMODAL_DIRECT -> {
                        processingPipeline.validateVisionModels(models)
                        requireNotNull(models.visionProvider)
                    }
                }
                val model = when (existing.route) {
                    ProcessingRoute.OCR_THEN_LLM -> models.textModel
                    ProcessingRoute.MULTIMODAL_DIRECT -> models.visionModel
                }
                val modelSummary = processingPipeline.buildModelSummary(model, provider.name)
                val baseTurns = retryIndex?.let { existing.followUpTurns.take(it) } ?: existing.followUpTurns
                val pendingTurn = FollowUpTurn(
                    id = turnId,
                    userText = prompt,
                    modelSummary = modelSummary
                )
                val started = existing.copy(followUpTurns = baseTurns + pendingTurn)
                workflowTaskManager.updateHistoryResult(historyId) { started }

                val messages = buildMessages(started, models.assistant.let { assistant ->
                    when (existing.route) {
                        ProcessingRoute.OCR_THEN_LLM -> assistant.textPrompt
                        ProcessingRoute.MULTIMODAL_DIRECT -> assistant.visionPrompt
                    }
                })
                val answer = StringBuilder()
                withTimeout(models.firstDeltaTimeoutMillis.coerceAtLeast(90_000L)) {
                    unifiedLLMClient.streamMessages(
                        provider = provider,
                        model = model,
                        messages = messages,
                        firstDeltaTimeoutMillis = models.firstDeltaTimeoutMillis,
                        trustAllHttpsCertificates = models.trustAllHttpsCertificates
                    ).collect { event ->
                        when (event) {
                            is LlmEvent.TextDelta -> {
                                answer.append(event.text)
                                updateTurn(historyId, turnId) { turn ->
                                    turn.copy(assistantText = answer.toString())
                                }
                            }
                            is LlmEvent.Done -> Unit
                            is LlmEvent.ToolCall -> Unit
                        }
                    }
                }
                updateTurn(historyId, turnId) { turn ->
                    turn.copy(completed = true, errorMessage = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AppDebugLogStore.e(tag, "follow-up failed historyId=$historyId", error)
                updateTurn(historyId, turnId) { turn ->
                    turn.copy(
                        completed = true,
                        errorMessage = error.message?.takeIf(String::isNotBlank) ?: "请求失败"
                    )
                }
            } finally {
                jobs.remove(historyId)
                _requestStates.update { it - historyId }
            }
        }
        jobs[historyId] = job
    }

    private suspend fun buildMessages(result: ProcessingResult, assistantPrompt: String): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        messages += textMessage(
            role = "system",
            text = assistantPromptWithCopyMarker(assistantPrompt) +
                "\n\n你正在历史记录页面继续与用户对话。请结合首轮题目、首轮回答和后续消息直接回答。"
        )
        val initialPrompt = when (result.route) {
            ProcessingRoute.OCR_THEN_LLM -> result.extractedText.takeIf(String::isNotBlank)?.let {
                "以下是用户通过悬浮窗提交题目的 OCR 结果，请完成任务：\n$it"
            } ?: "用户通过悬浮窗提交了一道题目。"
            ProcessingRoute.MULTIMODAL_DIRECT -> "请直接基于图片内容完成任务。"
        }
        val images = if (result.route == ProcessingRoute.MULTIMODAL_DIRECT) {
            result.loadHistoryBitmaps().map { it.toBase64Jpeg() }
        } else {
            emptyList()
        }
        messages += userMessage(initialPrompt, images)
        messages += textMessage(role = "assistant", text = result.initialAssistantContext())
        result.followUpTurns.forEach { turn ->
            messages += textMessage(role = "user", text = turn.userText)
            if (turn.completed && turn.assistantText.isNotBlank() && turn.errorMessage == null) {
                messages += textMessage(role = "assistant", text = turn.assistantText)
            }
        }
        return messages
    }

    private suspend fun updateTurn(
        historyId: String,
        turnId: String,
        transform: (FollowUpTurn) -> FollowUpTurn
    ) {
        workflowTaskManager.updateHistoryResult(historyId) { result ->
            result.copy(
                followUpTurns = result.followUpTurns.map { turn ->
                    if (turn.id == turnId) transform(turn) else turn
                }
            )
        }
    }
}

private fun ProcessingResult.initialAssistantContext(): String {
    latestAnswerText().takeIf(String::isNotBlank)?.let { return it }
    return buildString {
        if (automationThought.isNotBlank()) append(automationThought)
        automationAction?.text?.takeIf(String::isNotBlank)?.let { actionText ->
            if (isNotEmpty()) append("\n\n")
            append("执行结果：")
            append(actionText)
        }
    }.ifBlank { "首轮处理没有生成文本回答。" }
}
