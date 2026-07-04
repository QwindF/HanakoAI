package `fun`.kirari.hanako.overlay.workflow

import `fun`.kirari.hanako.agent.AgentTool
import `fun`.kirari.hanako.agent.ToolContext
import `fun`.kirari.hanako.automation.AutomationResult
import `fun`.kirari.hanako.automation.validateAutomationAction
import `fun`.kirari.hanako.data.AutomationActionRecord
import `fun`.kirari.hanako.data.AutomationActionType
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProcessingEvent
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.network.ToolRegistry
import `fun`.kirari.hanako.network.UnifiedLLMClient
import `fun`.kirari.hanako.network.search.SearchOutcome
import `fun`.kirari.hanako.workflow.NodeResult
import `fun`.kirari.hanako.workflow.WorkflowContext
import `fun`.kirari.hanako.workflow.WorkflowNode
import `fun`.kirari.llm.core.ChatMessage
import `fun`.kirari.llm.core.ChatToolCall
import `fun`.kirari.llm.core.ChatToolFunction
import `fun`.kirari.llm.core.LlmEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class AutomationAgentNode(
    private val unifiedClient: UnifiedLLMClient,
    private val onThoughtDelta: suspend (String) -> Unit,
    private val onProgressEvent: suspend (ProcessingEvent) -> Unit,
    private val toolsProvider: (AutomationNodeInput) -> List<AgentTool>
) : WorkflowNode<AutomationNodeInput, AutomationNodeOutput> {
    override val id: String = "automation_agent"

    override suspend fun run(input: AutomationNodeInput, ctx: WorkflowContext): NodeResult<AutomationNodeOutput> {
        val models = input.models
        val provider = when (models.route) {
            ProcessingRoute.OCR_THEN_LLM -> requireNotNull(models.textProvider)
            ProcessingRoute.MULTIMODAL_DIRECT -> requireNotNull(models.visionProvider)
        }
        val model = when (models.route) {
            ProcessingRoute.OCR_THEN_LLM -> models.textModel
            ProcessingRoute.MULTIMODAL_DIRECT -> models.visionModel
        }
        val assistantPrompt = when (models.route) {
            ProcessingRoute.OCR_THEN_LLM -> models.assistant.textPrompt
            ProcessingRoute.MULTIMODAL_DIRECT -> models.assistant.visionPrompt
        }
        val basePrompt = input.ocrOutput?.let {
            "以下是 OCR 结果，请先输出思考过程，再通过一次工具调用给出自动模式动作：\n${it.text}"
        } ?: "请根据整张屏幕截图先输出思考过程，再通过一次工具调用给出自动模式动作。"
        val imagesBase64 = if (input.ocrOutput == null) {
            input.capturedImages.bitmaps.map { it.toBase64Jpeg() }
        } else {
            emptyList()
        }
        val runtime = AutomationAgentRuntime(unifiedClient)
        val output = runtime.run(
            provider = provider,
            model = model,
            assistantPrompt = assistantPrompt,
            basePrompt = basePrompt,
            imagesBase64 = imagesBase64,
            firstDeltaTimeoutMillis = models.firstDeltaTimeoutMillis,
            trustAllHttpsCertificates = models.trustAllHttpsCertificates,
            tools = toolsProvider(input),
            toolContext = ToolContext(workflowId = ctx.workflowId, nodeId = id),
            onThoughtDelta = onThoughtDelta,
            onToolEvents = { events ->
                events.forEach { onProgressEvent(it) }
            }
        )
        AppDebugLogStore.i(
            "HanakoAutomationNode",
            "automation complete thoughtLength=${output.automationResult.thought.length} action=${output.automationResult.action.type}"
        )
        return NodeResult(
            output = output,
            checkpointSummary = "automation action ${output.automationResult.action.type}"
        )
    }
}

private class AutomationAgentRuntime(
    private val unifiedClient: UnifiedLLMClient
) {
    suspend fun run(
        provider: ModelProviderConfig,
        model: String,
        assistantPrompt: String,
        basePrompt: String,
        imagesBase64: List<String>,
        firstDeltaTimeoutMillis: Long,
        trustAllHttpsCertificates: Boolean,
        tools: List<AgentTool>,
        toolContext: ToolContext,
        onThoughtDelta: suspend (String) -> Unit,
        onToolEvents: suspend (List<ProcessingEvent>) -> Unit
    ): AutomationNodeOutput {
        val messages = mutableListOf<ChatMessage>()
        messages += textMessage(
            role = "system",
            text = automationSystemPrompt(
                if (tools.any { it.name == ToolRegistry.WEB_SEARCH_TOOL.name }) {
                    searchEnabledAssistantPrompt(assistantPrompt)
                } else {
                    assistantPrompt
                }
            )
        )
        messages += userMessage(basePrompt, imagesBase64)

        var latestSearchOutcome: SearchOutcome? = null
        repeat(4) { index ->
            val pass = collectToolLoopStream(
                provider = provider,
                model = model,
                messages = messages,
                tools = tools,
                firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
                trustAllHttpsCertificates = trustAllHttpsCertificates,
                onThoughtDelta = onThoughtDelta
            )
            val toolCall = pass.toolCalls.firstOrNull { it.name != ToolRegistry.WEB_SEARCH_TOOL.name }
                ?: pass.toolCalls.firstOrNull()
            if (toolCall == null) {
                return AutomationNodeOutput(
                    automationResult = buildAutomationResult(StreamResult(thought = pass.text.trim(), toolCall = null)),
                    searchOutcome = latestSearchOutcome,
                    toolTraceSummary = "tool_loop_${index + 1}_no_call"
                )
            }

            val tool = tools.firstOrNull { it.name == toolCall.name }
            if (tool == null) {
                return AutomationNodeOutput(
                    automationResult = buildAutomationResult(StreamResult(thought = pass.text.trim(), toolCall = toolCall)),
                    searchOutcome = latestSearchOutcome,
                    toolTraceSummary = "unknown_tool_${toolCall.name}"
                )
            }
            val toolResult = tool.invoke(toolCall.arguments, toolContext)
            onToolEvents(toolResult.events)
            if (tool.name == ToolRegistry.WEB_SEARCH_TOOL.name) {
                latestSearchOutcome = SearchOutcome(
                    performed = true,
                    results = emptyList(),
                    formattedText = toolResult.text.takeIf { it.isNotBlank() },
                    keywords = toolCall.arguments["query"]?.jsonPrimitive?.contentOrNull,
                    skipReason = null
                )
                val toolCallId = toolCall.id ?: "call_${tool.name}_${index + 1}"
                messages += assistantToolCallMessage(
                    text = pass.text,
                    toolCallId = toolCallId,
                    toolName = toolCall.name,
                    arguments = toolCall.arguments
                )
                messages += toolResultMessage(toolCallId, toolResult.text)
                return@repeat
            }
            return AutomationNodeOutput(
                automationResult = buildAutomationResult(StreamResult(thought = pass.text.trim(), toolCall = toolCall)),
                searchOutcome = latestSearchOutcome,
                toolTraceSummary = "final_tool_${toolCall.name}"
            )
        }

        val fallback = collectSingleToolPass(
            provider = provider,
            model = model,
            systemPrompt = automationSystemPrompt(assistantPrompt),
            userPrompt = buildEnhancedUserPrompt(basePrompt, latestSearchOutcome),
            imagesBase64 = imagesBase64,
            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
            trustAllHttpsCertificates = trustAllHttpsCertificates,
            onThoughtDelta = onThoughtDelta
        )
        return AutomationNodeOutput(
            automationResult = buildAutomationResult(fallback),
            searchOutcome = latestSearchOutcome,
            toolTraceSummary = "fallback_after_tool_loop"
        )
    }

    private suspend fun collectSingleToolPass(
        provider: ModelProviderConfig,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imagesBase64: List<String>,
        firstDeltaTimeoutMillis: Long,
        trustAllHttpsCertificates: Boolean,
        onThoughtDelta: suspend (String) -> Unit
    ): StreamResult {
        val text = StringBuilder()
        var toolCall: LlmEvent.ToolCall? = null
        unifiedClient.stream(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imagesBase64 = imagesBase64,
            tools = ToolRegistry.AUTOMATION_TOOLS,
            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
            trustAllHttpsCertificates = trustAllHttpsCertificates
        ).collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> {
                    text.append(event.text)
                    onThoughtDelta(event.text)
                }
                is LlmEvent.ToolCall -> toolCall = event
                is LlmEvent.Done -> {}
            }
        }
        return StreamResult(text.toString().trim(), toolCall)
    }

    private suspend fun collectToolLoopStream(
        provider: ModelProviderConfig,
        model: String,
        messages: List<ChatMessage>,
        tools: List<AgentTool>,
        firstDeltaTimeoutMillis: Long,
        trustAllHttpsCertificates: Boolean,
        onThoughtDelta: suspend (String) -> Unit
    ): ToolLoopResult {
        val text = StringBuilder()
        val toolCalls = mutableListOf<LlmEvent.ToolCall>()
        unifiedClient.streamMessages(
            provider = provider,
            model = model,
            messages = messages,
            tools = tools.map { it.definition },
            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
            trustAllHttpsCertificates = trustAllHttpsCertificates
        ).collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> {
                    text.append(event.text)
                    onThoughtDelta(event.text)
                }
                is LlmEvent.ToolCall -> toolCalls += event
                is LlmEvent.Done -> {}
            }
        }
        return ToolLoopResult(text = text.toString(), toolCalls = toolCalls)
    }

    private fun buildAutomationResult(streamResult: StreamResult): AutomationResult {
        val tc = streamResult.toolCall
        val thought = streamResult.thought
        if (tc != null) {
            val rawText = tc.arguments["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val action = try {
                validateAutomationAction(tc.name, rawText)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (action != null) {
                return AutomationResult(thought = thought, action = action)
            }
        }
        return AutomationResult(
            thought = thought,
            action = AutomationActionRecord(
                type = AutomationActionType.SET_CLIPBOARD,
                text = extractFallbackAnswer(thought)
            )
        )
    }

    private fun extractFallbackAnswer(thought: String): String {
        val trimmed = thought.trim()
        if (trimmed.isBlank()) return ""
        val lastLine = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .lastOrNull()
            ?: return trimmed
        val prefixes = listOf("答案：", "答案:", "所以：", "所以:", "因此：", "因此:", "最终答案：", "最终答案:")
        for (prefix in prefixes) {
            if (lastLine.startsWith(prefix, ignoreCase = true)) {
                val stripped = lastLine.substring(prefix.length).trim()
                if (stripped.isNotBlank()) return stripped
            }
        }
        return lastLine
    }
}

private data class StreamResult(
    val thought: String,
    val toolCall: LlmEvent.ToolCall?
)

private data class ToolLoopResult(
    val text: String,
    val toolCalls: List<LlmEvent.ToolCall>
)

private fun automationSystemPrompt(userPrompt: String): String {
    val trimmed = userPrompt.trim()
    return """
        你当前处于自动答题模式。
        你必须先输出简短、清晰的思考过程，说明你识别到了什么题型以及为什么这样判断。
        思考过程结束后，你必须且只能调用一个工具，不能在工具调用后继续输出额外文本。
        当答案适合直接填写到输入框、文本框、填空题空格时，调用 set_clipboard。
        当答案适合让用户直接在悬浮球上查看选项字母时，调用 show_bubble_letters。
        show_bubble_letters 的 text 参数可以是 1-8 个英文字母（大小写均可），或者"对""错""√""×"。不能包含空格、标点或其他解释。
        set_clipboard 的 text 参数必须是用户可以直接粘贴使用的最终答案。
        不允许调用多个工具，不允许省略工具调用。

        ${trimmed.ifBlank { "请根据截图内容判断题目类型，并选择最合适的自动动作。" }}
    """.trimIndent()
}
