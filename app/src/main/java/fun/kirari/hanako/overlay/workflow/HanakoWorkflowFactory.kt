package `fun`.kirari.hanako.overlay.workflow

import android.content.Context
import android.graphics.Bitmap
import `fun`.kirari.hanako.data.AnswerVersion
import `fun`.kirari.hanako.data.AutomationActionRecord
import `fun`.kirari.hanako.data.ProcessingEvent
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.ProcessingStatus
import `fun`.kirari.hanako.data.latestAnswerText
import `fun`.kirari.hanako.localocr.LocalOcrManager
import `fun`.kirari.hanako.network.UnifiedLLMClient
import `fun`.kirari.hanako.network.search.SearchContext
import `fun`.kirari.hanako.network.search.SearchOrchestrator
import `fun`.kirari.hanako.overlay.workflow.ProcessingPipeline
import `fun`.kirari.hanako.workflow.WorkflowContext
import `fun`.kirari.hanako.workflow.WorkflowRunner

internal class HanakoWorkflowFactory(
    appContext: Context,
    private val unifiedClient: UnifiedLLMClient,
    private val localOcrManager: LocalOcrManager,
    private val searchOrchestrator: SearchOrchestrator?,
    private val pipeline: ProcessingPipeline,
    private val workflowRunner: WorkflowRunner = WorkflowRunner()
) : HanakoWorkflowEngine {
    private val capturePersistNode = CapturePersistNode(appContext)

    override suspend fun prepareBaseResult(
        models: ProcessingPipeline.ResolvedModels,
        bitmaps: List<Bitmap>,
        detail: String
    ): Pair<ProcessingResult, CapturedImages> {
        val workflowId = java.util.UUID.randomUUID().toString()
        val workflowContext = WorkflowContext(workflowId = workflowId)
        val captureResult = workflowRunner.runNode(capturePersistNode, bitmaps, workflowContext)
        val captured = captureResult.output
        val baseResult = ProcessingResult(
            id = captured.historyId,
            assistantName = models.assistant.name,
            route = models.route,
            status = ProcessingStatus.RUNNING,
            modelSummary = when (models.route) {
                ProcessingRoute.OCR_THEN_LLM -> pipeline.buildModelSummary(models.textModel, models.textProvider?.name)
                ProcessingRoute.MULTIMODAL_DIRECT -> pipeline.buildModelSummary(models.visionModel, models.visionProvider?.name)
            },
            detail = detail,
            screenshotPath = captured.screenshotPaths.firstOrNull(),
            screenshotPaths = captured.screenshotPaths,
            events = listOf(ProcessingEvent(title = "请求开始", detail = "已创建处理记录"))
        )
        return baseResult to captured
    }

    override fun prepareRegenerationBaseResult(
        existingResult: ProcessingResult,
        models: ProcessingPipeline.ResolvedModels,
        bitmaps: List<Bitmap>,
        detail: String
    ): Pair<ProcessingResult, CapturedImages> {
        val capturedImages = CapturedImages(
            historyId = existingResult.id,
            bitmaps = bitmaps,
            screenshotPaths = existingResult.allScreenshotPaths
        )
        val baseResult = existingResult.copy(
            assistantName = models.assistant.name,
            route = models.route,
            status = ProcessingStatus.RUNNING,
            modelSummary = when (models.route) {
                ProcessingRoute.OCR_THEN_LLM -> pipeline.buildModelSummary(models.textModel, models.textProvider?.name)
                ProcessingRoute.MULTIMODAL_DIRECT -> pipeline.buildModelSummary(models.visionModel, models.visionProvider?.name)
            },
            detail = detail,
            extractedText = "",
            answer = existingResult.latestAnswerText(),
            automationThought = "",
            automationAction = null,
            events = listOf(ProcessingEvent(title = "重新生成开始", detail = "沿用原截图重新生成")),
            checkpoints = emptyList()
        )
        return baseResult to capturedImages
    }

    override suspend fun runAnswerWorkflow(
        models: ProcessingPipeline.ResolvedModels,
        capturedImages: CapturedImages,
        onOcrDelta: suspend (String) -> Unit,
        onAnswerDelta: suspend (String) -> Unit,
        onProgressEvent: suspend (ProcessingEvent) -> Unit
    ): AnswerWorkflowOutput {
        val ctx = WorkflowContext(workflowId = capturedImages.historyId)
        val ocrOutput = if (models.route == ProcessingRoute.OCR_THEN_LLM) {
            pipeline.validateOcrThenLlmModels(models)
            val result = workflowRunner.runNode(
                OcrNode(unifiedClient = unifiedClient, localOcrManager = localOcrManager),
                OcrNodeInput(capturedImages = capturedImages, models = models),
                ctx
            )
            onOcrDelta(result.output.text)
            result.output
        } else {
            pipeline.validateVisionModels(models)
            null
        }
        val answerNode = AnswerAgentNode(
            unifiedClient = unifiedClient,
            onAnswerDelta = onAnswerDelta,
            onProgressEvent = onProgressEvent,
            toolsProvider = { input ->
                if (!input.models.webSearchSettings.enabled || searchOrchestrator == null) {
                    emptyList()
                } else {
                    listOf(
                        WebSearchAgentTool(
                            orchestrator = searchOrchestrator,
                            settingsProvider = {
                                SearchContext(
                                    query = "",
                                    settings = input.models.webSearchSettings,
                                    trustAllHttps = input.models.trustAllHttpsCertificates,
                                    isAutomation = false
                                )
                            }
                        )
                    )
                }
            }
        )
        val answerResult = workflowRunner.runNode(
            answerNode,
            AnswerNodeInput(capturedImages = capturedImages, models = models, ocrOutput = ocrOutput),
            ctx
        )
        return AnswerWorkflowOutput(
            capturedImages = capturedImages,
            ocrOutput = ocrOutput,
            answerOutput = answerResult.output,
            checkpoints = ctx.checkpoints()
        )
    }

    override suspend fun runAutomationWorkflow(
        models: ProcessingPipeline.ResolvedModels,
        capturedImages: CapturedImages,
        onOcrDelta: suspend (String) -> Unit,
        onThoughtDelta: suspend (String) -> Unit,
        onProgressEvent: suspend (ProcessingEvent) -> Unit
    ): AutomationWorkflowOutput {
        val ctx = WorkflowContext(workflowId = capturedImages.historyId)
        val ocrOutput = if (models.route == ProcessingRoute.OCR_THEN_LLM) {
            pipeline.validateOcrThenLlmModels(models)
            val result = workflowRunner.runNode(
                OcrNode(unifiedClient = unifiedClient, localOcrManager = localOcrManager),
                OcrNodeInput(capturedImages = capturedImages, models = models),
                ctx
            )
            onOcrDelta(result.output.text)
            result.output
        } else {
            pipeline.validateVisionModels(models)
            null
        }
        val automationNode = AutomationAgentNode(
            unifiedClient = unifiedClient,
            onThoughtDelta = onThoughtDelta,
            onProgressEvent = onProgressEvent,
            toolsProvider = { input ->
                val tools = mutableListOf<`fun`.kirari.hanako.agent.AgentTool>()
                if (input.models.webSearchSettings.enabled && searchOrchestrator != null) {
                    tools += WebSearchAgentTool(
                        orchestrator = searchOrchestrator,
                        settingsProvider = {
                            SearchContext(
                                query = "",
                                settings = input.models.webSearchSettings,
                                trustAllHttps = input.models.trustAllHttpsCertificates,
                                isAutomation = true
                            )
                        }
                    )
                }
                tools += automationAgentTools()
                tools
            }
        )
        val automationResult = workflowRunner.runNode(
            automationNode,
            AutomationNodeInput(capturedImages = capturedImages, models = models, ocrOutput = ocrOutput),
            ctx
        )
        return AutomationWorkflowOutput(
            capturedImages = capturedImages,
            ocrOutput = ocrOutput,
            automationOutput = automationResult.output,
            checkpoints = ctx.checkpoints()
        )
    }

    override fun buildAnswerResult(
        base: ProcessingResult,
        models: ProcessingPipeline.ResolvedModels,
        output: AnswerWorkflowOutput,
        progressEvents: List<ProcessingEvent>
    ): ProcessingResult {
        val events = base.events.toMutableList()
        output.ocrOutput?.let {
            events += ProcessingEvent(title = "OCR 完成", detail = "已提取 ${it.text.length} 个字符")
        }
        events += progressEvents
        if (progressEvents.none { it.title.startsWith("联网搜索") }) {
            buildSearchEvent(output.answerOutput.searchOutcome)?.let(events::add)
        }
        events += ProcessingEvent(title = "答案完成", detail = "已生成 ${output.answerOutput.answer.length} 个字符")
        return base.copy(
            status = ProcessingStatus.SUCCESS,
            detail = "处理完成",
            extractedText = output.ocrOutput?.text.orEmpty(),
            answer = output.answerOutput.answer,
            answerVersions = listOf(AnswerVersion(output.answerOutput.answer)),
            events = events,
            checkpoints = output.checkpoints.toProcessingCheckpointSummaries()
        )
    }

    override fun buildAutomationResult(
        base: ProcessingResult,
        models: ProcessingPipeline.ResolvedModels,
        output: AutomationWorkflowOutput,
        progressEvents: List<ProcessingEvent>
    ): Pair<AutomationActionRecord, ProcessingResult> {
        val events = base.events.toMutableList()
        output.ocrOutput?.let {
            events += ProcessingEvent(title = "OCR 完成", detail = "已提取 ${it.text.length} 个字符")
        }
        events += progressEvents
        if (progressEvents.none { it.title.startsWith("联网搜索") }) {
            buildSearchEvent(output.automationOutput.searchOutcome)?.let(events::add)
        }
        events += ProcessingEvent(
            title = "工具动作完成",
            detail = "${output.automationOutput.automationResult.action.type}: ${output.automationOutput.automationResult.action.text}"
        )
        val result = base.copy(
            status = ProcessingStatus.SUCCESS,
            detail = "自动处理完成",
            extractedText = output.ocrOutput?.text.orEmpty(),
            answer = "",
            automationThought = output.automationOutput.automationResult.thought,
            automationAction = output.automationOutput.automationResult.action,
            events = events,
            checkpoints = output.checkpoints.toProcessingCheckpointSummaries()
        )
        return output.automationOutput.automationResult.action to result
    }
}
