package `fun`.kirari.hanako.runtime

import android.graphics.Bitmap
import `fun`.kirari.hanako.automation.AutomationResult
import `fun`.kirari.hanako.data.AnswerVersion
import `fun`.kirari.hanako.data.AutomationActionRecord
import `fun`.kirari.hanako.data.AutomationActionType
import `fun`.kirari.hanako.data.ProcessingEvent
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.ProcessingStatus
import `fun`.kirari.hanako.data.WebSearchSettings
import `fun`.kirari.hanako.data.defaultAssistant
import `fun`.kirari.hanako.data.defaultProvider
import `fun`.kirari.hanako.overlay.ProcessingPipeline
import `fun`.kirari.hanako.overlay.workflow.AnswerNodeOutput
import `fun`.kirari.hanako.overlay.workflow.AnswerWorkflowOutput
import `fun`.kirari.hanako.overlay.workflow.AutomationNodeOutput
import `fun`.kirari.hanako.overlay.workflow.AutomationWorkflowOutput
import `fun`.kirari.hanako.overlay.workflow.CapturedImages
import `fun`.kirari.hanako.overlay.workflow.HanakoWorkflowEngine
import `fun`.kirari.hanako.overlay.workflow.OcrNodeOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowTaskManagerTest {

    @Test
    fun regenerate_createsNewAnswerVersionBeforeWorkflowCompletes() = runTest {
        val harness = ManagerHarness(testScope = TestScope(testScheduler))
        val existing = testProcessingResult(
            id = "history-1",
            answer = "old answer",
            screenshotPaths = listOf("history-1.png")
        )
        harness.engine.answerGate = CompletableDeferred()
        harness.engine.answerDeltas["history-1"] = emptyList()
        harness.engine.finalAnswers["history-1"] = "new answer"

        harness.manager.startRegenerateAnswerTask(
            existingResult = existing,
            models = testModels(),
            bitmaps = emptyList()
        )
        runCurrent()

        val live = harness.resultStore.liveResults.value.getValue("history-1")
        assertEquals(listOf("old answer", ""), live.answerVersions.map { it.text })
        assertEquals(1, harness.manager.tasks.value.values.single().answerVersionIndex)
        assertTrue(harness.manager.isRunning("history-1"))

        harness.engine.answerGate?.complete(Unit)
        advanceUntilIdle()

        val completed = harness.resultStore.liveResults.value.getValue("history-1")
        assertEquals(ProcessingStatus.SUCCESS, completed.status)
        assertEquals("new answer", completed.answerVersions.last().text)
    }

    @Test
    fun answerTasks_keepConcurrentProgressIsolatedByHistoryId() = runTest {
        val harness = ManagerHarness(testScope = TestScope(testScheduler))
        harness.engine.generatedHistoryIds += listOf("history-1", "history-2")
        harness.engine.answerDeltas["history-1"] = listOf("A")
        harness.engine.answerDeltas["history-2"] = listOf("B")

        harness.manager.startAnswerTask(testModels(), emptyList())
        harness.manager.startAnswerTask(testModels(), emptyList())
        advanceUntilIdle()

        assertEquals("A", harness.resultStore.liveResults.value.getValue("history-1").answer)
        assertEquals("B", harness.resultStore.liveResults.value.getValue("history-2").answer)
        assertEquals(
            setOf(WorkflowTaskStatus.SUCCESS),
            harness.manager.tasks.value.values.map { it.status }.toSet()
        )
    }

    @Test
    fun answerCompletion_keepsAccumulatedDeltasWhenFinalOutputIsBlank() = runTest {
        val harness = ManagerHarness(testScope = TestScope(testScheduler))
        harness.engine.generatedHistoryIds += "history-1"
        harness.engine.answerDeltas["history-1"] = listOf("A", "B")
        harness.engine.finalAnswers["history-1"] = ""

        harness.manager.startAnswerTask(testModels(), emptyList())
        advanceUntilIdle()

        val completed = harness.resultStore.liveResults.value.getValue("history-1")
        assertEquals("AB", completed.answer)
        assertEquals(listOf("AB"), completed.answerVersions.map { it.text })
        assertEquals("AB", harness.repository.settings.history.single().answer)
    }

    @Test
    fun removeHistoryResult_cancelsRunningTaskAndPreventsLateResultResurrection() = runTest {
        val harness = ManagerHarness(testScope = TestScope(testScheduler))
        harness.engine.generatedHistoryIds += "history-1"
        harness.engine.answerDeltas["history-1"] = listOf("partial")
        harness.engine.answerGate = CompletableDeferred()

        harness.manager.startAnswerTask(testModels(), emptyList())
        runCurrent()

        assertEquals("partial", harness.resultStore.liveResults.value.getValue("history-1").answer)

        harness.manager.removeHistoryResult("history-1")
        runCurrent()
        harness.engine.answerGate?.complete(Unit)
        advanceUntilIdle()

        assertNull(harness.resultStore.liveResults.value["history-1"])
        assertFalse(harness.repository.settings.history.any { it.id == "history-1" })
        assertEquals(WorkflowTaskStatus.CANCELLED, harness.manager.tasks.value.values.single().status)
    }
}

private class ManagerHarness(
    testScope: TestScope
) {
    val repository = InMemoryWorkflowHistoryRepository()
    val resultStore = WorkflowResultStore(
        repository = repository,
        scope = testScope,
        persistDelayMillis = 250L
    )
    val engine = FakeHanakoWorkflowEngine()
    val taskRegistry = WorkflowTaskRegistry()
    val manager = WorkflowTaskManager(
        repository = repository,
        resultStore = resultStore,
        taskRegistry = taskRegistry,
        workflowFactory = engine,
        scope = testScope,
        processingTimeoutMillis = 90_000L
    )
}

private class FakeHanakoWorkflowEngine : HanakoWorkflowEngine {
    val generatedHistoryIds = ArrayDeque<String>()
    val answerDeltas = mutableMapOf<String, List<String>>()
    val finalAnswers = mutableMapOf<String, String>()
    var answerGate: CompletableDeferred<Unit>? = null

    override suspend fun prepareBaseResult(
        models: ProcessingPipeline.ResolvedModels,
        bitmaps: List<Bitmap>,
        detail: String
    ): Pair<ProcessingResult, CapturedImages> {
        val historyId = generatedHistoryIds.removeFirstOrNull() ?: "history-${generatedHistoryIds.size + 1}"
        return baseResult(historyId, models, detail) to CapturedImages(
            historyId = historyId,
            bitmaps = bitmaps,
            screenshotPaths = listOf("$historyId.png")
        )
    }

    override fun prepareRegenerationBaseResult(
        existingResult: ProcessingResult,
        models: ProcessingPipeline.ResolvedModels,
        bitmaps: List<Bitmap>,
        detail: String
    ): Pair<ProcessingResult, CapturedImages> {
        return existingResult.copy(
            assistantName = models.assistant.name,
            route = models.route,
            status = ProcessingStatus.RUNNING,
            modelSummary = "test-model",
            detail = detail,
            extractedText = "",
            checkpoints = emptyList()
        ) to CapturedImages(
            historyId = existingResult.id,
            bitmaps = bitmaps,
            screenshotPaths = existingResult.allScreenshotPaths
        )
    }

    override suspend fun runAnswerWorkflow(
        models: ProcessingPipeline.ResolvedModels,
        capturedImages: CapturedImages,
        onOcrDelta: suspend (String) -> Unit,
        onAnswerDelta: suspend (String) -> Unit,
        onProgressEvent: suspend (ProcessingEvent) -> Unit
    ): AnswerWorkflowOutput {
        val historyId = capturedImages.historyId
        onOcrDelta("ocr-$historyId")
        answerDeltas[historyId].orEmpty().forEach { onAnswerDelta(it) }
        answerGate?.await()
        val finalAnswer = finalAnswers[historyId] ?: answerDeltas[historyId].orEmpty().joinToString("")
        return AnswerWorkflowOutput(
            capturedImages = capturedImages,
            ocrOutput = OcrNodeOutput(text = "ocr-$historyId", pageTexts = listOf("ocr-$historyId"), providerInfo = "fake"),
            answerOutput = AnswerNodeOutput(answer = finalAnswer, searchOutcome = null, messageTraceSummary = "fake"),
            checkpoints = emptyList()
        )
    }

    override suspend fun runAutomationWorkflow(
        models: ProcessingPipeline.ResolvedModels,
        capturedImages: CapturedImages,
        onOcrDelta: suspend (String) -> Unit,
        onThoughtDelta: suspend (String) -> Unit,
        onProgressEvent: suspend (ProcessingEvent) -> Unit
    ): AutomationWorkflowOutput {
        return AutomationWorkflowOutput(
            capturedImages = capturedImages,
            ocrOutput = null,
            automationOutput = AutomationNodeOutput(
                automationResult = AutomationResult(
                    thought = "",
                    action = AutomationActionRecord(AutomationActionType.SET_CLIPBOARD, "")
                ),
                searchOutcome = null,
                toolTraceSummary = "fake"
            ),
            checkpoints = emptyList()
        )
    }

    override fun buildAnswerResult(
        base: ProcessingResult,
        models: ProcessingPipeline.ResolvedModels,
        output: AnswerWorkflowOutput,
        progressEvents: List<ProcessingEvent>
    ): ProcessingResult {
        return base.copy(
            status = ProcessingStatus.SUCCESS,
            detail = "done",
            extractedText = output.ocrOutput?.text.orEmpty(),
            answer = output.answerOutput.answer,
            answerVersions = listOf(AnswerVersion(output.answerOutput.answer)),
            events = base.events + progressEvents
        )
    }

    override fun buildAutomationResult(
        base: ProcessingResult,
        models: ProcessingPipeline.ResolvedModels,
        output: AutomationWorkflowOutput,
        progressEvents: List<ProcessingEvent>
    ): Pair<AutomationActionRecord, ProcessingResult> {
        val action = output.automationOutput.automationResult.action
        return action to base.copy(
            status = ProcessingStatus.SUCCESS,
            automationAction = action,
            automationThought = output.automationOutput.automationResult.thought
        )
    }

    private fun baseResult(
        historyId: String,
        models: ProcessingPipeline.ResolvedModels,
        detail: String
    ): ProcessingResult {
        return ProcessingResult(
            id = historyId,
            assistantName = models.assistant.name,
            route = models.route,
            status = ProcessingStatus.RUNNING,
            modelSummary = "test-model",
            detail = detail,
            screenshotPaths = listOf("$historyId.png")
        )
    }
}

private fun testModels(): ProcessingPipeline.ResolvedModels {
    val provider = defaultProvider()
    return ProcessingPipeline.ResolvedModels(
        assistant = defaultAssistant(),
        ocrProvider = provider,
        ocrModel = "ocr",
        textProvider = provider,
        textModel = "text",
        visionProvider = provider,
        visionModel = "vision",
        firstDeltaTimeoutMillis = 1_000L,
        route = ProcessingRoute.OCR_THEN_LLM,
        usingLocalOcr = false,
        trustAllHttpsCertificates = false,
        webSearchSettings = WebSearchSettings()
    )
}
