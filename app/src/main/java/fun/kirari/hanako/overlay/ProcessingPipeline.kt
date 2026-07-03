package `fun`.kirari.hanako.overlay

import android.content.Context
import android.graphics.Bitmap
import `fun`.kirari.hanako.automation.AutomationResult
import `fun`.kirari.hanako.automation.validateAutomationAction
import `fun`.kirari.hanako.data.AssistantPreset
import `fun`.kirari.hanako.data.AutomationActionRecord
import `fun`.kirari.hanako.data.AutomationActionType
import `fun`.kirari.hanako.data.LOCAL_OCR_PROVIDER_ID
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ModelPurpose
import `fun`.kirari.hanako.data.ProcessingEvent
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.ProcessingStatus
import `fun`.kirari.hanako.data.WebSearchSettings
import `fun`.kirari.hanako.data.resolveModelName
import `fun`.kirari.hanako.data.resolveModelProvider
import `fun`.kirari.hanako.data.saveToHistoryFile
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.localocr.LocalOcrManager
import `fun`.kirari.hanako.network.ToolRegistry
import `fun`.kirari.hanako.network.ToolDef
import `fun`.kirari.hanako.network.UnifiedLLMClient
import `fun`.kirari.hanako.network.search.SearchContext
import `fun`.kirari.hanako.network.search.SearchOrchestrator
import `fun`.kirari.hanako.network.search.SearchOutcome
import `fun`.kirari.hanako.network.search.SearchSkipReason
import `fun`.kirari.llm.core.LlmEvent
import `fun`.kirari.llm.core.visibleWhitespaceForLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class ProcessingPipeline(
    private val appContext: Context,
    private val unifiedClient: UnifiedLLMClient,
    private val localOcrManager: LocalOcrManager,
    private val searchOrchestrator: SearchOrchestrator? = null
) {
    private val tag = "HanakoPipeline"

    data class ResolvedModels(
        val assistant: AssistantPreset,
        val ocrProvider: ModelProviderConfig?,
        val ocrModel: String,
        val textProvider: ModelProviderConfig?,
        val textModel: String,
        val visionProvider: ModelProviderConfig?,
        val visionModel: String,
        val firstDeltaTimeoutMillis: Long,
        val route: ProcessingRoute,
        val usingLocalOcr: Boolean,
        val trustAllHttpsCertificates: Boolean,
        val webSearchSettings: WebSearchSettings,
        val searchProvider: ModelProviderConfig?,
        val searchModel: String
    )

    fun resolveModels(state: OverlayUiState): ResolvedModels {
        val assistant = state.settings.assistants.firstOrNull { it.id == state.settings.selectedAssistantId }
            ?: error("请先配置助手")
        return ResolvedModels(
            assistant = assistant,
            ocrProvider = state.settings.resolveModelProvider(ModelPurpose.OCR),
            ocrModel = state.settings.resolveModelName(ModelPurpose.OCR),
            textProvider = state.settings.resolveModelProvider(ModelPurpose.TEXT),
            textModel = state.settings.resolveModelName(ModelPurpose.TEXT),
            visionProvider = state.settings.resolveModelProvider(ModelPurpose.VISION),
            visionModel = state.settings.resolveModelName(ModelPurpose.VISION),
            firstDeltaTimeoutMillis = state.settings.automation.autoModeTimeoutSeconds.coerceAtLeast(1) * 1000L,
            route = state.settings.processingRoute,
            usingLocalOcr = state.settings.ocrModelSelection.providerId == LOCAL_OCR_PROVIDER_ID,
            trustAllHttpsCertificates = state.settings.trustAllHttpsCertificates,
            webSearchSettings = state.settings.webSearch,
            searchProvider = state.settings.resolveModelProvider(ModelPurpose.TEXT),
            searchModel = state.settings.resolveModelName(ModelPurpose.TEXT)
        )
    }

    fun buildModelSummary(model: String, providerName: String?): String {
        val trimmedModel = model.trim()
        val trimmedProvider = providerName?.trim().orEmpty()
        if (trimmedModel.isBlank()) return ""
        return if (trimmedProvider.isBlank()) trimmedModel else "$trimmedModel（$trimmedProvider）"
    }

    fun createBaseResult(
        models: ResolvedModels,
        bitmaps: List<Bitmap>,
        detail: String
    ): Triple<ProcessingResult, String, List<String>> {
        val historyId = java.util.UUID.randomUUID().toString()
        val screenshotPaths = bitmaps.mapIndexed { index, bitmap ->
            bitmap.saveToHistoryFile(appContext, "${historyId}_$index")
        }
        val baseResult = ProcessingResult(
            id = historyId,
            assistantName = models.assistant.name,
            route = models.route,
            status = ProcessingStatus.RUNNING,
            modelSummary = when (models.route) {
                ProcessingRoute.OCR_THEN_LLM -> buildModelSummary(models.textModel, models.textProvider?.name)
                ProcessingRoute.MULTIMODAL_DIRECT -> buildModelSummary(models.visionModel, models.visionProvider?.name)
            },
            detail = detail,
            screenshotPath = screenshotPaths.firstOrNull(),
            screenshotPaths = screenshotPaths,
            events = listOf(ProcessingEvent(title = "请求开始", detail = "已创建处理记录"))
        )
        return Triple(baseResult, historyId, screenshotPaths)
    }

    fun validateOcrThenLlmModels(models: ResolvedModels) {
        if ((!models.usingLocalOcr && (models.ocrProvider == null || models.ocrModel.isBlank())) ||
            models.textProvider == null || models.textModel.isBlank()
        ) {
            error("请先在模型设置中配置 OCR 和文本模型")
        }
    }

    fun validateVisionModels(models: ResolvedModels) {
        if (models.visionProvider == null || models.visionModel.isBlank()) {
            error("请先在模型设置中配置多模态模型")
        }
    }

    suspend fun runLocalOcr(bitmap: Bitmap): String {
        return withContext(Dispatchers.Default) {
            localOcrManager.recognize(bitmap)
        }
    }

    private suspend fun collectTextStream(
        provider: ModelProviderConfig,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imagesBase64: List<String> = emptyList(),
        firstDeltaTimeoutMillis: Long,
        trustAllHttpsCertificates: Boolean,
        onDelta: (String) -> Unit
    ): String {
        val text = StringBuilder()
        unifiedClient.stream(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imagesBase64 = imagesBase64,
            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
            trustAllHttpsCertificates = trustAllHttpsCertificates
        ).collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> {
                    text.append(event.text)
                    onDelta(event.text)
                }
                is LlmEvent.Done -> {}
                else -> {}
            }
        }
        val result = text.toString()
        AppDebugLogStore.d(tag, "collectTextStream resultLength=${result.length} preview=${result.visibleWhitespaceForLog(240)}")
        return result
    }

    private data class StreamResult(
        val thought: String,
        val toolCall: LlmEvent.ToolCall?
    )

    private data class SearchAwareTextResult(
        val text: String,
        val toolCalls: List<LlmEvent.ToolCall>
    )

    private data class ToolLoopResult(
        val text: String,
        val toolCalls: List<LlmEvent.ToolCall>
    )

    private suspend fun collectToolStream(
        provider: ModelProviderConfig,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imagesBase64: List<String> = emptyList(),
        firstDeltaTimeoutMillis: Long,
        trustAllHttpsCertificates: Boolean,
        onThoughtDelta: (String) -> Unit
    ): StreamResult {
        val thought = StringBuilder()
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
                    thought.append(event.text)
                    onThoughtDelta(event.text)
                }
                is LlmEvent.ToolCall -> {
                    toolCall = event
                }
                is LlmEvent.Done -> {}
            }
        }
        return StreamResult(thought.toString().trim(), toolCall)
    }

    private suspend fun collectSearchAwareTextStream(
        provider: ModelProviderConfig,
        model: String,
        messages: List<`fun`.kirari.llm.core.ChatMessage>,
        firstDeltaTimeoutMillis: Long,
        trustAllHttpsCertificates: Boolean,
        onAnswerDelta: (String) -> Unit
    ): SearchAwareTextResult {
        val text = StringBuilder()
        val toolCalls = mutableListOf<LlmEvent.ToolCall>()
        logMessages("searchAware", model, messages)
        unifiedClient.streamMessages(
            provider = provider,
            model = model,
            messages = messages,
            tools = listOf(ToolRegistry.WEB_SEARCH_TOOL),
            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
            trustAllHttpsCertificates = trustAllHttpsCertificates
        ).collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> {
                    text.append(event.text)
                    onAnswerDelta(event.text)
                }
                is LlmEvent.ToolCall -> {
                    if (event.name == ToolRegistry.WEB_SEARCH_TOOL.name) {
                        toolCalls += event
                    }
                }
                is LlmEvent.Done -> {}
            }
        }
        return SearchAwareTextResult(text = text.toString(), toolCalls = toolCalls)
    }

    private suspend fun collectToolLoopStream(
        provider: ModelProviderConfig,
        model: String,
        messages: List<`fun`.kirari.llm.core.ChatMessage>,
        tools: List<ToolDef>,
        firstDeltaTimeoutMillis: Long,
        trustAllHttpsCertificates: Boolean,
        onThoughtDelta: (String) -> Unit
    ): ToolLoopResult {
        val text = StringBuilder()
        val toolCalls = mutableListOf<LlmEvent.ToolCall>()
        logMessages("toolLoop", model, messages)
        unifiedClient.streamMessages(
            provider = provider,
            model = model,
            messages = messages,
            tools = tools,
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

        // 尝试解析 LLM 的工具调用
        if (tc != null) {
            val rawText = tc.arguments["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val action = try {
                validateAutomationAction(tc.name, rawText)
            } catch (e: IllegalArgumentException) {
                // 工具名未知或参数格式不合法，降级到剪贴板
                null
            }
            if (action != null) {
                return AutomationResult(thought = thought, action = action)
            }
        }

        // 兜底：无工具调用或工具调用无效。
        // 尝试从推理文本中提取简洁答案（最后一行非空文本），
        // 避免把整个推理过程塞进剪贴板。
        val fallbackText = extractFallbackAnswer(thought)
        return AutomationResult(
            thought = thought,
            action = AutomationActionRecord(
                type = AutomationActionType.SET_CLIPBOARD,
                text = fallbackText
            )
        )
    }

    /**
     * 从 LLM 推理文本中提取兜底答案。
     *
     * 策略：取最后一个非空行，去掉常见的前缀标记（"答案:"、"所以"等）。
     * 如果整个推理为空则返回空字符串，不抛异常。
     */
    private fun extractFallbackAnswer(thought: String): String {
        val trimmed = thought.trim()
        if (trimmed.isBlank()) return ""

        // 尝试取最后一行非空文本
        val lastLine = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .lastOrNull()
            ?: return trimmed

        // 去掉常见前缀
        val prefixes = listOf("答案：", "答案:", "所以：", "所以:", "因此：", "因此:", "最终答案：", "最终答案:")
        for (prefix in prefixes) {
            if (lastLine.startsWith(prefix, ignoreCase = true)) {
                val stripped = lastLine.substring(prefix.length).trim()
                if (stripped.isNotBlank()) return stripped
            }
        }
        return lastLine
    }

    suspend fun streamOcrThenChat(
        models: ResolvedModels,
        bitmaps: List<Bitmap>,
        onOcrDelta: (String) -> Unit,
        onAnswerDelta: (String) -> Unit,
        onSearchEvent: suspend (ProcessingEvent) -> Unit = {}
    ): Triple<String, String, SearchOutcome?> {
        AppDebugLogStore.i(tag, "streamOcrThenChat ocrModel=${models.ocrModel} textModel=${models.textModel} imageCount=${bitmaps.size}")
        val ocrTexts = mutableListOf<String>()
        bitmaps.forEach { bitmap ->
            val ocrText = if (models.usingLocalOcr) {
                runLocalOcr(bitmap)
            } else {
                collectTextStream(
                    provider = requireNotNull(models.ocrProvider),
                    model = models.ocrModel,
                    systemPrompt = models.assistant.ocrPrompt,
                    userPrompt = "请执行 OCR。",
                    imagesBase64 = listOf(bitmap.toBase64Jpeg()),
                    firstDeltaTimeoutMillis = models.firstDeltaTimeoutMillis,
                    trustAllHttpsCertificates = models.trustAllHttpsCertificates,
                    onDelta = {}
                )
            }
            ocrTexts.add(ocrText)
        }
        val combinedOcrText = ocrTexts.joinToString("\n\n---\n\n")
        onOcrDelta(combinedOcrText)

        val answerRequest = "以下是 OCR 结果，请完成任务：\n$combinedOcrText"
        val (answer, searchOutcome) = runSearchAwareAnswer(
            provider = requireNotNull(models.textProvider),
            model = models.textModel,
            assistantPrompt = assistantPromptWithCopyMarker(models.assistant.textPrompt),
            basePrompt = answerRequest,
            imagesBase64 = emptyList(),
            searchModels = models,
            isAutomation = false,
            onSearchEvent = onSearchEvent,
            onAnswerDelta = onAnswerDelta
        )
        AppDebugLogStore.i(tag, "streamOcrThenChat success ocrLength=${combinedOcrText.length} answerLength=${answer.length}")
        return Triple(combinedOcrText, answer, searchOutcome)
    }

    suspend fun streamVisionDirect(
        models: ResolvedModels,
        bitmaps: List<Bitmap>,
        onAnswerDelta: (String) -> Unit,
        onSearchEvent: suspend (ProcessingEvent) -> Unit = {}
    ): Pair<String, SearchOutcome?> {
        AppDebugLogStore.i(tag, "streamVisionDirect visionModel=${models.visionModel} imageCount=${bitmaps.size}")
        val imagesBase64 = bitmaps.map { it.toBase64Jpeg() }
        val result = runSearchAwareAnswer(
            provider = requireNotNull(models.visionProvider),
            model = models.visionModel,
            assistantPrompt = assistantPromptWithCopyMarker(models.assistant.visionPrompt),
            basePrompt = "请直接基于图片内容完成任务。",
            imagesBase64 = imagesBase64,
            searchModels = models,
            isAutomation = false,
            onSearchEvent = onSearchEvent,
            onAnswerDelta = onAnswerDelta
        )
        AppDebugLogStore.i(tag, "streamVisionDirect success answerLength=${result.first.length}")
        return result
    }

    suspend fun streamOcrThenAutomation(
        models: ResolvedModels,
        bitmaps: List<Bitmap>,
        onOcrDelta: (String) -> Unit,
        onThoughtDelta: (String) -> Unit,
        onSearchEvent: suspend (ProcessingEvent) -> Unit = {}
    ): Triple<String, AutomationResult, SearchOutcome?> {
        AppDebugLogStore.i(tag, "streamOcrThenAutomation ocrModel=${models.ocrModel} textModel=${models.textModel} imageCount=${bitmaps.size}")
        val ocrTexts = mutableListOf<String>()
        bitmaps.forEach { bitmap ->
            val ocrText = if (models.usingLocalOcr) {
                runLocalOcr(bitmap)
            } else {
                collectTextStream(
                    provider = requireNotNull(models.ocrProvider),
                    model = models.ocrModel,
                    systemPrompt = models.assistant.ocrPrompt,
                    userPrompt = "请执行 OCR。",
                    imagesBase64 = listOf(bitmap.toBase64Jpeg()),
                    firstDeltaTimeoutMillis = models.firstDeltaTimeoutMillis,
                    trustAllHttpsCertificates = models.trustAllHttpsCertificates,
                    onDelta = {}
                )
            }
            ocrTexts.add(ocrText)
        }
        val combinedOcrText = ocrTexts.joinToString("\n\n---\n\n")
        onOcrDelta(combinedOcrText)
        val (result, searchOutcome) = runAutomationToolLoop(
            provider = requireNotNull(models.textProvider),
            model = models.textModel,
            assistantPrompt = models.assistant.textPrompt,
            basePrompt = "以下是 OCR 结果，请先输出思考过程，再通过一次工具调用给出自动模式动作：\n$combinedOcrText",
            imagesBase64 = emptyList(),
            models = models,
            onSearchEvent = onSearchEvent,
            onThoughtDelta = onThoughtDelta
        )
        AppDebugLogStore.i(tag, "streamOcrThenAutomation success ocrLength=${combinedOcrText.length} thoughtLength=${result.thought.length} action=${result.action.type}")
        return Triple(combinedOcrText, result, searchOutcome)
    }

    suspend fun streamAutomationDirect(
        models: ResolvedModels,
        bitmaps: List<Bitmap>,
        onThoughtDelta: (String) -> Unit,
        onSearchEvent: suspend (ProcessingEvent) -> Unit = {}
    ): Pair<AutomationResult, SearchOutcome?> {
        AppDebugLogStore.i(tag, "streamAutomationDirect visionModel=${models.visionModel} imageCount=${bitmaps.size}")
        val imagesBase64 = bitmaps.map { it.toBase64Jpeg() }
        val result = runAutomationToolLoop(
            provider = requireNotNull(models.visionProvider),
            model = models.visionModel,
            assistantPrompt = models.assistant.visionPrompt,
            basePrompt = "请根据整张屏幕截图先输出思考过程，再通过一次工具调用给出自动模式动作。",
            imagesBase64 = imagesBase64,
            models = models,
            onSearchEvent = onSearchEvent,
            onThoughtDelta = onThoughtDelta
        )
        AppDebugLogStore.i(tag, "streamAutomationDirect success thoughtLength=${result.first.thought.length} action=${result.first.action.type} actionText=${result.first.action.text}")
        return result
    }

    private suspend fun runSearchAwareAnswer(
        provider: ModelProviderConfig,
        model: String,
        assistantPrompt: String,
        basePrompt: String,
        imagesBase64: List<String>,
        searchModels: ResolvedModels,
        isAutomation: Boolean,
        onSearchEvent: suspend (ProcessingEvent) -> Unit,
        onAnswerDelta: (String) -> Unit
    ): Pair<String, SearchOutcome?> {
        if (!searchModels.webSearchSettings.enabled || searchOrchestrator == null) {
            val answer = collectTextStream(
                provider = provider,
                model = model,
                systemPrompt = assistantPrompt,
                userPrompt = basePrompt,
                imagesBase64 = imagesBase64,
                firstDeltaTimeoutMillis = searchModels.firstDeltaTimeoutMillis,
                trustAllHttpsCertificates = searchModels.trustAllHttpsCertificates,
                onDelta = onAnswerDelta
            )
            return answer to null
        }
        val messages = mutableListOf<`fun`.kirari.llm.core.ChatMessage>()
        if (assistantPrompt.isNotBlank()) {
            messages += textMessage(role = "system", text = searchEnabledAssistantPrompt(assistantPrompt))
        }
        messages += userMessage(basePrompt, imagesBase64)

        var latestSearchOutcome: SearchOutcome? = null
        repeat(3) {
            val pass = collectSearchAwareTextStream(
                provider = provider,
                model = model,
                messages = messages,
                firstDeltaTimeoutMillis = searchModels.firstDeltaTimeoutMillis,
                trustAllHttpsCertificates = searchModels.trustAllHttpsCertificates,
                onAnswerDelta = onAnswerDelta
            )
            val toolCall = pass.toolCalls.firstOrNull()
            if (toolCall == null) {
                val finalText = pass.text.trim()
                AppDebugLogStore.v(tag, "searchAware noToolCall finalTextLength=${finalText.length}")
                if (finalText.isNotBlank()) {
                    return pass.text to (latestSearchOutcome ?: SearchOutcome(
                        performed = false,
                        results = emptyList(),
                        formattedText = null,
                        keywords = null,
                        skipReason = SearchSkipReason.LLM_NO_TOOL_CALL
                    ))
                }
                val fallbackPrompt = buildEnhancedUserPrompt(basePrompt, latestSearchOutcome)
                val fallbackAnswer = collectTextStream(
                    provider = provider,
                    model = model,
                    systemPrompt = assistantPrompt,
                    userPrompt = fallbackPrompt,
                    imagesBase64 = imagesBase64,
                    firstDeltaTimeoutMillis = searchModels.firstDeltaTimeoutMillis,
                    trustAllHttpsCertificates = searchModels.trustAllHttpsCertificates,
                    onDelta = onAnswerDelta
                )
                return fallbackAnswer to (latestSearchOutcome ?: SearchOutcome(
                    performed = false,
                    results = emptyList(),
                    formattedText = null,
                    keywords = null,
                    skipReason = SearchSkipReason.LLM_NO_TOOL_CALL
                ))
            }
            val query = toolCall.arguments["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
            AppDebugLogStore.v(tag, "searchAware toolCall id=${toolCall.id} name=${toolCall.name} query=$query")
            if (query.isNotBlank()) {
                onSearchEvent(ProcessingEvent(title = "正在联网搜索", detail = "关键词：$query"))
            }
            val searchOutcome = searchOrchestrator.execute(
                SearchContext(
                    query = query,
                    settings = searchModels.webSearchSettings,
                    trustAllHttps = searchModels.trustAllHttpsCertificates,
                    isAutomation = isAutomation
                )
            )
            latestSearchOutcome = searchOutcome
            searchEvent(searchOutcome)?.let { onSearchEvent(it) }
            val toolCallId = toolCall.id ?: "call_web_search_${it + 1}"
            messages += assistantToolCallMessage(text = pass.text, toolCallId = toolCallId, toolName = toolCall.name, arguments = toolCall.arguments)
            messages += toolResultMessage(toolCallId, searchOutcome.formattedText ?: searchOutcome.skipReason?.displayText.orEmpty())
            AppDebugLogStore.v(tag, "searchAware toolResult toolCallId=$toolCallId result=${(searchOutcome.formattedText ?: searchOutcome.skipReason?.displayText.orEmpty()).take(1000)}")
        }

        val fallbackPrompt = buildEnhancedUserPrompt(basePrompt, latestSearchOutcome)
        val answer = collectTextStream(
            provider = provider,
            model = model,
            systemPrompt = assistantPrompt,
            userPrompt = fallbackPrompt,
            imagesBase64 = imagesBase64,
            firstDeltaTimeoutMillis = searchModels.firstDeltaTimeoutMillis,
            trustAllHttpsCertificates = searchModels.trustAllHttpsCertificates,
            onDelta = onAnswerDelta
        )
        return answer to latestSearchOutcome
    }

    private suspend fun runAutomationToolLoop(
        provider: ModelProviderConfig,
        model: String,
        assistantPrompt: String,
        basePrompt: String,
        imagesBase64: List<String>,
        models: ResolvedModels,
        onSearchEvent: suspend (ProcessingEvent) -> Unit,
        onThoughtDelta: (String) -> Unit
    ): Pair<AutomationResult, SearchOutcome?> {
        if (searchOrchestrator == null || !models.webSearchSettings.enabled) {
            val streamResult = collectToolStream(
                provider = provider,
                model = model,
                systemPrompt = automationSystemPrompt(assistantPrompt),
                userPrompt = basePrompt,
                imagesBase64 = imagesBase64,
                firstDeltaTimeoutMillis = models.firstDeltaTimeoutMillis,
                trustAllHttpsCertificates = models.trustAllHttpsCertificates,
                onThoughtDelta = onThoughtDelta
            )
            return buildAutomationResult(streamResult) to null
        }

        val messages = mutableListOf<`fun`.kirari.llm.core.ChatMessage>()
        messages += textMessage(
            role = "system",
            text = automationSystemPrompt(searchEnabledAssistantPrompt(assistantPrompt))
        )
        messages += userMessage(basePrompt, imagesBase64)
        var latestSearchOutcome: SearchOutcome? = null
        val tools = listOf(ToolRegistry.WEB_SEARCH_TOOL) + ToolRegistry.AUTOMATION_TOOLS

        repeat(4) { index ->
            val pass = collectToolLoopStream(
                provider = provider,
                model = model,
                messages = messages,
                tools = tools,
                firstDeltaTimeoutMillis = models.firstDeltaTimeoutMillis,
                trustAllHttpsCertificates = models.trustAllHttpsCertificates,
                onThoughtDelta = onThoughtDelta
            )
            val toolCall = pass.toolCalls.firstOrNull { it.name != ToolRegistry.WEB_SEARCH_TOOL.name }
                ?: pass.toolCalls.firstOrNull()
            if (toolCall == null) {
                AppDebugLogStore.v(tag, "automation toolLoop noToolCall textLength=${pass.text.trim().length}")
                return buildAutomationResult(
                    StreamResult(thought = pass.text.trim(), toolCall = null)
                ) to latestSearchOutcome
            }
            if (toolCall.name == ToolRegistry.WEB_SEARCH_TOOL.name) {
                val query = toolCall.arguments["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
                AppDebugLogStore.v(tag, "automation webSearch toolCall id=${toolCall.id} query=$query")
                if (query.isNotBlank()) {
                    onSearchEvent(ProcessingEvent(title = "正在联网搜索", detail = "关键词：$query"))
                }
                val searchOutcome = searchOrchestrator.execute(
                    SearchContext(
                        query = query,
                        settings = models.webSearchSettings,
                        trustAllHttps = models.trustAllHttpsCertificates,
                        isAutomation = true
                    )
                )
                latestSearchOutcome = searchOutcome
                searchEvent(searchOutcome)?.let { onSearchEvent(it) }
                val toolCallId = toolCall.id ?: "call_web_search_${index + 1}"
                messages += assistantToolCallMessage(
                    text = pass.text,
                    toolCallId = toolCallId,
                    toolName = toolCall.name,
                    arguments = toolCall.arguments
                )
                messages += toolResultMessage(
                    toolCallId,
                    searchOutcome.formattedText ?: searchOutcome.skipReason?.displayText.orEmpty()
                )
                AppDebugLogStore.v(tag, "automation webSearch toolResult toolCallId=$toolCallId result=${(searchOutcome.formattedText ?: searchOutcome.skipReason?.displayText.orEmpty()).take(1000)}")
                return@repeat
            }
            AppDebugLogStore.v(tag, "automation finalToolCall id=${toolCall.id} name=${toolCall.name} args=${toolCall.arguments}")
            return buildAutomationResult(
                StreamResult(thought = pass.text.trim(), toolCall = toolCall)
            ) to latestSearchOutcome
        }

        val fallback = collectToolStream(
            provider = provider,
            model = model,
            systemPrompt = automationSystemPrompt(assistantPrompt),
            userPrompt = buildEnhancedUserPrompt(basePrompt, latestSearchOutcome),
            imagesBase64 = imagesBase64,
            firstDeltaTimeoutMillis = models.firstDeltaTimeoutMillis,
            trustAllHttpsCertificates = models.trustAllHttpsCertificates,
            onThoughtDelta = onThoughtDelta
        )
        return buildAutomationResult(fallback) to latestSearchOutcome
    }

    /**
     * 将搜索结果注入到 user prompt 前面。
     */
    private fun buildEnhancedUserPrompt(
        basePrompt: String,
        searchOutcome: SearchOutcome?
    ): String {
        if (searchOutcome?.formattedText == null) return basePrompt
        return "${searchOutcome.formattedText}\n\n$basePrompt"
    }

    /**
     * 将搜索结果转换成处理事件（用于历史记录展示）。
     */
    fun searchEvent(outcome: SearchOutcome?): ProcessingEvent? {
        if (outcome == null) return null
        return if (outcome.performed && outcome.results.isNotEmpty()) {
            ProcessingEvent(
                title = "联网搜索完成",
                detail = "关键词：${outcome.keywords}，获取 ${outcome.results.size} 条结果"
            )
        } else {
            outcome.skipReason?.let {
                ProcessingEvent(
                    title = "联网搜索已跳过",
                    detail = it.displayText
                )
            }
        }
    }

    fun buildChatResult(
        base: ProcessingResult,
        models: ResolvedModels,
        ocrText: String,
        answer: String,
        historyId: String,
        screenshotPaths: List<String>,
        searchOutcome: `fun`.kirari.hanako.network.search.SearchOutcome? = null,
        progressEvents: List<ProcessingEvent> = emptyList()
    ): ProcessingResult {
        val events = base.events.toMutableList()
        if (models.route == ProcessingRoute.OCR_THEN_LLM) {
            events.add(ProcessingEvent(title = "OCR 完成", detail = "已提取 ${ocrText.length} 个字符"))
        }
        events.addAll(progressEvents)
        if (progressEvents.none { it.title.startsWith("联网搜索") }) {
            searchEvent(searchOutcome)?.let { events.add(it) }
        }
        events.add(ProcessingEvent(title = "答案完成", detail = "已生成 ${answer.length} 个字符"))
        return ProcessingResult(
            id = historyId,
            assistantName = models.assistant.name,
            route = models.route,
            status = ProcessingStatus.SUCCESS,
            modelSummary = when (models.route) {
                ProcessingRoute.OCR_THEN_LLM -> buildModelSummary(models.textModel, models.textProvider?.name)
                ProcessingRoute.MULTIMODAL_DIRECT -> buildModelSummary(models.visionModel, models.visionProvider?.name)
            },
            detail = "处理完成",
            extractedText = ocrText,
            answer = answer,
            screenshotPath = screenshotPaths.firstOrNull(),
            screenshotPaths = screenshotPaths,
            events = events,
            createdAtMillis = base.createdAtMillis
        )
    }

    fun buildAutomationResult(
        base: ProcessingResult,
        models: ResolvedModels,
        ocrText: String,
        automationResult: AutomationResult,
        historyId: String,
        screenshotPaths: List<String>,
        searchOutcome: `fun`.kirari.hanako.network.search.SearchOutcome? = null,
        progressEvents: List<ProcessingEvent> = emptyList()
    ): Pair<AutomationActionRecord, ProcessingResult> {
        val events = base.events.toMutableList()
        if (models.route == ProcessingRoute.OCR_THEN_LLM) {
            events.add(ProcessingEvent(title = "OCR 完成", detail = "已提取 ${ocrText.length} 个字符"))
        }
        events.addAll(progressEvents)
        if (progressEvents.none { it.title.startsWith("联网搜索") }) {
            searchEvent(searchOutcome)?.let { events.add(it) }
        }
        events.add(
            ProcessingEvent(
                title = "工具动作完成",
                detail = "${automationResult.action.type}: ${automationResult.action.text}"
            )
        )
        val result = ProcessingResult(
            id = historyId,
            assistantName = models.assistant.name,
            route = models.route,
            status = ProcessingStatus.SUCCESS,
            modelSummary = when (models.route) {
                ProcessingRoute.OCR_THEN_LLM -> buildModelSummary(models.textModel, models.textProvider?.name)
                ProcessingRoute.MULTIMODAL_DIRECT -> buildModelSummary(models.visionModel, models.visionProvider?.name)
            },
            detail = "自动处理完成",
            extractedText = ocrText,
            answer = "",
            automationThought = automationResult.thought,
            automationAction = automationResult.action,
            screenshotPath = screenshotPaths.firstOrNull(),
            screenshotPaths = screenshotPaths,
            events = events,
            createdAtMillis = base.createdAtMillis
        )
        return automationResult.action to result
    }
}

private fun assistantPromptWithCopyMarker(systemPrompt: String): String {
    val trimmed = systemPrompt.trim()
    if (trimmed.isBlank()) return trimmed
    return """
        你可以在回答中插入如下格式的可复制文本块：
        [copy:内容]
        其中"内容"会显示为一个小标签，点击复制图标后会写入同样的文本到剪贴板。
        对于问题的答案或用户需要填写到某个表单中的内容，你必须给出一键复制的标签。

        $trimmed
    """.trimIndent()
}

private fun searchEnabledAssistantPrompt(systemPrompt: String): String {
    val trimmed = systemPrompt.trim()
    return """
        当问题依赖最新事实、新闻、政策法规更新、体育赛果、统计数据、公众人物近况、产品/软件/模型版本、公司动态，或明确要求联网查询时，先调用 web_search。
        web_search 的 query 参数必须是简洁关键词或短语，不要写成长句。
        不需要搜索时，直接继续完成任务，不要解释你是否联网。

        $trimmed
    """.trimIndent()
}

private fun textMessage(role: String, text: String): `fun`.kirari.llm.core.ChatMessage =
    `fun`.kirari.llm.core.ChatMessage(
        role = role,
        content = buildJsonArray {
            add(buildJsonObject {
                put("type", "input_text")
                put("text", text)
            })
        }
    )

private fun userMessage(text: String, imagesBase64: List<String>): `fun`.kirari.llm.core.ChatMessage =
    `fun`.kirari.llm.core.ChatMessage(
        role = "user",
        content = buildJsonArray {
            if (text.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", text)
                })
            }
            imagesBase64.forEach { imageBase64 ->
                add(buildJsonObject {
                    put("type", "input_image")
                    put("image_url", "data:image/jpeg;base64,$imageBase64")
                })
            }
        }
    )

private fun assistantToolCallMessage(
    text: String,
    toolCallId: String,
    toolName: String,
    arguments: JsonObject
): `fun`.kirari.llm.core.ChatMessage =
    `fun`.kirari.llm.core.ChatMessage(
        role = "assistant",
        content = buildJsonArray {
            if (text.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "output_text")
                    put("text", text)
                })
            }
        },
        toolCalls = listOf(
            `fun`.kirari.llm.core.ChatToolCall(
                id = toolCallId,
                function = `fun`.kirari.llm.core.ChatToolFunction(
                    name = toolName,
                    arguments = arguments.toString()
                )
            )
        )
    )

private fun toolResultMessage(toolCallId: String, result: String): `fun`.kirari.llm.core.ChatMessage =
    `fun`.kirari.llm.core.ChatMessage(
        role = "tool",
        toolCallId = toolCallId,
        content = JsonPrimitive(result)
    )

private fun ProcessingPipeline.logMessages(
    phase: String,
    model: String,
    messages: List<`fun`.kirari.llm.core.ChatMessage>
) {
    if (!AppDebugLogStore.verboseLlmEnabled) return
    val summary = messages.joinToString(separator = "\n") { message ->
        val toolCalls = message.toolCalls.joinToString { "${it.function.name}#${it.id}" }
        val contentPreview = when (val content = message.content) {
            is JsonPrimitive -> content.content.take(400)
            else -> content?.toString().orEmpty().take(400)
        }
        "role=${message.role} toolCallId=${message.toolCallId.orEmpty()} toolCalls=${toolCalls.ifBlank { "-" }} content=${contentPreview}"
    }
    AppDebugLogStore.v(tag = "HanakoPipeline", message = "$phase messages model=$model count=${messages.size}\n$summary")
}

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

private fun Bitmap.toBase64Jpeg(quality: Int = 92): String {
    val output = java.io.ByteArrayOutputStream()
    compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, output)
    return android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
}
