package `fun`.kirari.hanako.runtime

import android.graphics.Bitmap
import `fun`.kirari.hanako.data.AutomationActionRecord
import `fun`.kirari.hanako.data.AnswerVersion
import `fun`.kirari.hanako.data.ProcessingEvent
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingStatus
import `fun`.kirari.hanako.data.displayedAnswerVersions
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.overlay.OverlayUiState
import `fun`.kirari.hanako.overlay.ProcessingPipeline
import `fun`.kirari.hanako.overlay.workflow.HanakoWorkflowFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal enum class WorkflowTaskKind {
    ANSWER,
    REGENERATE_ANSWER,
    AUTOMATION
}

internal enum class WorkflowTaskStatus {
    RUNNING,
    SUCCESS,
    ERROR,
    CANCELLED
}

internal data class WorkflowTaskState(
    val taskId: String,
    val historyId: String,
    val kind: WorkflowTaskKind,
    val status: WorkflowTaskStatus = WorkflowTaskStatus.RUNNING,
    val answerVersionIndex: Int? = null,
    val errorMessage: String? = null
)

internal data class WorkflowAutomationResult(
    val action: AutomationActionRecord,
    val result: ProcessingResult
)

internal class WorkflowTaskManager(
    private val repository: WorkflowHistoryRepository,
    private val resultStore: WorkflowResultStore,
    private val workflowFactory: HanakoWorkflowFactory,
    private val scope: CoroutineScope,
    private val processingTimeoutMillis: Long = 90_000L
) {
    private val tag = "HanakoWorkflowTasks"
    private val jobs = mutableMapOf<String, Job>()
    private val _tasks = MutableStateFlow<Map<String, WorkflowTaskState>>(emptyMap())
    val tasks: StateFlow<Map<String, WorkflowTaskState>> = _tasks.asStateFlow()
    val liveResults: StateFlow<Map<String, ProcessingResult>> = resultStore.liveResults

    fun isRunning(historyId: String): Boolean {
        return _tasks.value.values.any {
            it.historyId == historyId && it.status == WorkflowTaskStatus.RUNNING
        }
    }

    fun startAnswerTask(
        models: ProcessingPipeline.ResolvedModels,
        bitmaps: List<Bitmap>,
        onStateChanged: (ProcessingResult) -> Unit = {},
        onFinished: (Result<ProcessingResult>) -> Unit = {}
    ): String {
        val taskId = java.util.UUID.randomUUID().toString()
        val job = scope.launch {
            var baseResult: ProcessingResult? = null
            val progressEvents = mutableListOf<ProcessingEvent>()
            val answerText = StringBuilder()
            runCatching {
                withTimeout(processingTimeoutMillis) {
                    val (preparedBaseResult, capturedImages) = workflowFactory.prepareBaseResult(models, bitmaps)
                    baseResult = preparedBaseResult
                    registerTask(taskId, preparedBaseResult.id, WorkflowTaskKind.ANSWER)
                    resultStore.upsert(preparedBaseResult)
                    onStateChanged(preparedBaseResult)

                    val workflowOutput = workflowFactory.runAnswerWorkflow(
                        models = models,
                        capturedImages = capturedImages,
                        onOcrDelta = { text ->
                            resultStore.update(preparedBaseResult.id) { current ->
                                current.copy(extractedText = text)
                            }?.let(onStateChanged)
                        },
                        onAnswerDelta = { delta ->
                            answerText.append(delta)
                            resultStore.update(preparedBaseResult.id) { current ->
                                current.copy(answer = answerText.toString())
                            }?.let(onStateChanged)
                        },
                        onProgressEvent = { event ->
                            progressEvents.add(event)
                            resultStore.update(preparedBaseResult.id) { current ->
                                current.copy(events = preparedBaseResult.events + progressEvents)
                            }?.let(onStateChanged)
                        }
                    )
                    val finished = workflowFactory.buildAnswerResult(
                        base = preparedBaseResult,
                        models = models,
                        output = workflowOutput,
                        progressEvents = progressEvents
                    )
                    val finalAnswer = answerText.toString().ifBlank { finished.answer }
                    finished.copy(
                        answer = finalAnswer,
                        answerVersions = listOf(AnswerVersion(finalAnswer))
                    )
                }
            }.onSuccess { result ->
                AppDebugLogStore.i(tag, "answer task success taskId=$taskId historyId=${result.id}")
                resultStore.upsert(result)
                markTask(taskId, WorkflowTaskStatus.SUCCESS)
                onStateChanged(result)
                onFinished(Result.success(result))
            }.onFailure { error ->
                if (error is CancellationException) {
                    markTask(taskId, WorkflowTaskStatus.CANCELLED)
                    return@onFailure
                }
                AppDebugLogStore.e(tag, "answer task failed taskId=$taskId", error)
                baseResult?.let { base ->
                    val failed = failureResult(base, error)
                    resultStore.upsert(failed)
                    onStateChanged(failed)
                }
                markTask(taskId, WorkflowTaskStatus.ERROR, error.message)
                onFinished(Result.failure(error))
            }
        }
        trackJob(taskId, job)
        return taskId
    }

    fun startRegenerateAnswerTask(
        existingResult: ProcessingResult,
        models: ProcessingPipeline.ResolvedModels,
        bitmaps: List<Bitmap>,
        onStateChanged: (ProcessingResult) -> Unit = {},
        onFinished: (Result<ProcessingResult>) -> Unit = {}
    ): String {
        val historyId = existingResult.id
        val taskId = "regenerate:$historyId:${System.currentTimeMillis()}"
        if (isRunning(historyId)) return taskId

        val job = scope.launch {
            val progressEvents = mutableListOf<ProcessingEvent>()
            val answerText = StringBuilder()
            val (baseResult, capturedImages) = workflowFactory.prepareRegenerationBaseResult(
                existingResult = existingResult,
                models = models,
                bitmaps = bitmaps
            )
            val startedResult = baseResult.withStartedAnswerVersionFrom(existingResult)
            val versionIndex = startedResult.answerVersions.lastIndex
            registerTask(taskId, historyId, WorkflowTaskKind.REGENERATE_ANSWER, versionIndex)
            resultStore.upsert(startedResult)
            onStateChanged(startedResult)

            runCatching {
                withTimeout(processingTimeoutMillis) {
                    val workflowOutput = workflowFactory.runAnswerWorkflow(
                        models = models,
                        capturedImages = capturedImages,
                        onOcrDelta = { text ->
                            resultStore.update(historyId) { current ->
                                current.copy(extractedText = text)
                            }?.let(onStateChanged)
                        },
                        onAnswerDelta = { delta ->
                            answerText.append(delta)
                            resultStore.update(historyId) { current ->
                                current.withUpdatedAnswerVersion(versionIndex, answerText.toString())
                            }?.let(onStateChanged)
                        },
                        onProgressEvent = { event ->
                            progressEvents.add(event)
                            resultStore.update(historyId) { current ->
                                current.copy(events = startedResult.events + progressEvents)
                            }?.let(onStateChanged)
                        }
                    )
                    val finished = workflowFactory.buildAnswerResult(
                        base = baseResult,
                        models = models,
                        output = workflowOutput,
                        progressEvents = progressEvents
                    )
                    val finalAnswer = answerText.toString().ifBlank { finished.answer }
                    val latestResult = resultStore.latest(historyId) ?: startedResult
                    latestResult.copy(
                        status = ProcessingStatus.SUCCESS,
                        detail = finished.detail,
                        extractedText = finished.extractedText,
                        answer = finalAnswer,
                        answerVersions = latestResult.answerVersions.replaceAt(versionIndex, AnswerVersion(finalAnswer)),
                        events = finished.events,
                        checkpoints = finished.checkpoints,
                        modelSummary = finished.modelSummary,
                        route = finished.route,
                        assistantName = finished.assistantName
                    )
                }
            }.onSuccess { regenerated ->
                AppDebugLogStore.i(tag, "regenerate task success taskId=$taskId historyId=$historyId")
                resultStore.upsert(regenerated)
                markTask(taskId, WorkflowTaskStatus.SUCCESS)
                onStateChanged(regenerated)
                onFinished(Result.success(regenerated))
            }.onFailure { error ->
                if (error is CancellationException) {
                    markTask(taskId, WorkflowTaskStatus.CANCELLED)
                    return@onFailure
                }
                AppDebugLogStore.e(tag, "regenerate task failed taskId=$taskId historyId=$historyId", error)
                val failed = failureResult(startedResult, error)
                resultStore.upsert(failed)
                markTask(taskId, WorkflowTaskStatus.ERROR, error.message)
                onStateChanged(failed)
                onFinished(Result.failure(error))
            }
        }
        trackJob(taskId, job)
        return taskId
    }

    fun startAutomationTask(
        models: ProcessingPipeline.ResolvedModels,
        bitmaps: List<Bitmap>,
        onStateChanged: (ProcessingResult) -> Unit = {},
        onFinished: (Result<WorkflowAutomationResult>) -> Unit = {}
    ): String {
        val taskId = java.util.UUID.randomUUID().toString()
        val job = scope.launch {
            var baseResult: ProcessingResult? = null
            val progressEvents = mutableListOf<ProcessingEvent>()
            val thoughtText = StringBuilder()
            runCatching {
                withTimeout(processingTimeoutMillis) {
                    val (preparedBaseResult, capturedImages) = workflowFactory.prepareBaseResult(
                        models = models,
                        bitmaps = bitmaps,
                        detail = "自动流程已开始"
                    )
                    baseResult = preparedBaseResult
                    registerTask(taskId, preparedBaseResult.id, WorkflowTaskKind.AUTOMATION)
                    resultStore.upsert(preparedBaseResult)
                    onStateChanged(preparedBaseResult)

                    val workflowOutput = workflowFactory.runAutomationWorkflow(
                        models = models,
                        capturedImages = capturedImages,
                        onOcrDelta = { text ->
                            resultStore.update(preparedBaseResult.id) { current ->
                                current.copy(extractedText = text)
                            }?.let(onStateChanged)
                        },
                        onThoughtDelta = { delta ->
                            thoughtText.append(delta)
                            resultStore.update(preparedBaseResult.id) { current ->
                                current.copy(automationThought = thoughtText.toString())
                            }?.let(onStateChanged)
                        },
                        onProgressEvent = { event ->
                            progressEvents.add(event)
                            resultStore.update(preparedBaseResult.id) { current ->
                                current.copy(events = preparedBaseResult.events + progressEvents)
                            }?.let(onStateChanged)
                        }
                    )
                    val (action, result) = workflowFactory.buildAutomationResult(
                        base = preparedBaseResult,
                        models = models,
                        output = workflowOutput,
                        progressEvents = progressEvents
                    )
                    val finalThought = thoughtText.toString().ifBlank { result.automationThought }
                    WorkflowAutomationResult(action, result.copy(automationThought = finalThought))
                }
            }.onSuccess { automationResult ->
                AppDebugLogStore.i(tag, "automation task success taskId=$taskId historyId=${automationResult.result.id}")
                resultStore.upsert(automationResult.result)
                markTask(taskId, WorkflowTaskStatus.SUCCESS)
                onStateChanged(automationResult.result)
                onFinished(Result.success(automationResult))
            }.onFailure { error ->
                if (error is CancellationException) {
                    markTask(taskId, WorkflowTaskStatus.CANCELLED)
                    return@onFailure
                }
                AppDebugLogStore.e(tag, "automation task failed taskId=$taskId", error)
                baseResult?.let { base ->
                    val failed = failureResult(base, error)
                    resultStore.upsert(failed)
                    onStateChanged(failed)
                }
                markTask(taskId, WorkflowTaskStatus.ERROR, error.message)
                onFinished(Result.failure(error))
            }
        }
        trackJob(taskId, job)
        return taskId
    }

    fun cancelTask(taskId: String) {
        jobs.remove(taskId)?.cancel()
        markTask(taskId, WorkflowTaskStatus.CANCELLED)
    }

    suspend fun cancelHistoryTask(historyId: String) {
        _tasks.value.values
            .filter { it.historyId == historyId && it.status == WorkflowTaskStatus.RUNNING }
            .forEach { cancelTask(it.taskId) }
    }

    suspend fun removeHistoryResult(historyId: String) {
        cancelHistoryTask(historyId)
        resultStore.remove(historyId)
        repository.update { current ->
            current.copy(
                history = current.history.filterNot { it.id == historyId },
                lastResult = current.lastResult?.takeUnless { it.id == historyId }
            )
        }
    }

    suspend fun clearHistory() {
        jobs.keys.toList().forEach(::cancelTask)
        _tasks.value.values
            .filter { it.status == WorkflowTaskStatus.RUNNING }
            .forEach { markTask(it.taskId, WorkflowTaskStatus.CANCELLED) }
        resultStore.clear()
        repository.update { it.copy(history = emptyList(), lastResult = null) }
    }

    fun mergedHistory(persisted: List<ProcessingResult>): List<ProcessingResult> {
        return resultStore.mergedWith(persisted)
    }

    private fun registerTask(
        taskId: String,
        historyId: String,
        kind: WorkflowTaskKind,
        answerVersionIndex: Int? = null
    ) {
        _tasks.update {
            it + (taskId to WorkflowTaskState(
                taskId = taskId,
                historyId = historyId,
                kind = kind,
                answerVersionIndex = answerVersionIndex
            ))
        }
    }

    private fun markTask(taskId: String, status: WorkflowTaskStatus, errorMessage: String? = null) {
        _tasks.update { current ->
            current[taskId]?.let { task ->
                current + (taskId to task.copy(status = status, errorMessage = errorMessage))
            } ?: current
        }
    }

    private fun trackJob(taskId: String, job: Job) {
        jobs[taskId] = job
        job.invokeOnCompletion {
            if (jobs[taskId] === job) jobs.remove(taskId)
        }
    }

    private fun failureResult(base: ProcessingResult, error: Throwable): ProcessingResult {
        val isTimeout = error is TimeoutCancellationException
        val message = error.message?.ifBlank { null } ?: if (isTimeout) "请求超时（90 秒）" else "处理失败"
        return base.copy(
            status = if (isTimeout) ProcessingStatus.TIMEOUT else ProcessingStatus.ERROR,
            detail = message,
            events = base.events + ProcessingEvent(
                title = if (isTimeout) "请求超时" else "请求失败",
                detail = message
            )
        )
    }
}

private fun ProcessingResult.withStartedAnswerVersionFrom(previous: ProcessingResult): ProcessingResult {
    val versions = previous.displayedAnswerVersions() + AnswerVersion("")
    return copy(answer = "", answerVersions = versions)
}

private fun ProcessingResult.withUpdatedAnswerVersion(
    index: Int,
    text: String
): ProcessingResult {
    val versions = answerVersions.ifEmpty { displayedAnswerVersions() }
    return copy(
        answer = text,
        answerVersions = versions.replaceAt(index, AnswerVersion(text))
    )
}

private fun List<AnswerVersion>.replaceAt(index: Int, value: AnswerVersion): List<AnswerVersion> {
    return mapIndexed { currentIndex, currentValue ->
        if (currentIndex == index) value else currentValue
    }
}
