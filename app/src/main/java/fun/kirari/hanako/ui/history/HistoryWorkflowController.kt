package `fun`.kirari.hanako.ui.history

import `fun`.kirari.hanako.data.AppSettings
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.SettingsRepository
import `fun`.kirari.hanako.data.loadHistoryBitmaps
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.overlay.OverlayUiState
import `fun`.kirari.hanako.overlay.ProcessingPipeline
import `fun`.kirari.hanako.runtime.WorkflowTaskManager
import `fun`.kirari.hanako.runtime.WorkflowTaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RunningHistoryTaskUiState(
    val historyId: String,
    val answerVersionIndex: Int? = null
)

internal class HistoryWorkflowController(
    private val scope: CoroutineScope,
    private val repository: SettingsRepository,
    private val settings: StateFlow<AppSettings>,
    private val processingPipeline: ProcessingPipeline,
    private val workflowTaskManager: WorkflowTaskManager
) {
    private val tag = "HanakoHistoryWorkflow"

    val runningHistoryTasks: StateFlow<Map<String, RunningHistoryTaskUiState>> = workflowTaskManager.tasks
        .map { tasks ->
            tasks.values
                .filter { it.status == WorkflowTaskStatus.RUNNING }
                .associate { task ->
                    task.historyId to RunningHistoryTaskUiState(
                        historyId = task.historyId,
                        answerVersionIndex = task.answerVersionIndex
                    )
                }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap()
        )

    val liveWorkflowResults: StateFlow<Map<String, ProcessingResult>> = workflowTaskManager.liveResults

    val mergedHistory: StateFlow<List<ProcessingResult>> = combine(
        settings,
        liveWorkflowResults
    ) { settings, _ ->
        workflowTaskManager.mergedHistory(settings.history)
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    fun clearHistory() {
        scope.launch {
            workflowTaskManager.clearHistory()
        }
    }

    fun deleteHistoryItem(resultId: String) {
        scope.launch {
            workflowTaskManager.removeHistoryResult(resultId)
        }
    }

    fun saveResult(result: ProcessingResult) {
        scope.launch {
            repository.update { current ->
                current.copy(
                    lastResult = result,
                    history = listOf(result) + current.history
                )
            }
        }
    }

    fun regenerateHistoryResult(resultId: String) {
        val currentSettings = settings.value
        val existing = currentSettings.history.firstOrNull { it.id == resultId } ?: return
        if (existing.automationAction != null) return
        if (workflowTaskManager.isRunning(resultId)) return
        val bitmaps = existing.loadHistoryBitmaps()
        if (bitmaps.isEmpty()) return
        val models = runCatching {
            processingPipeline.resolveModels(OverlayUiState(settings = currentSettings))
        }.getOrElse { error ->
            AppDebugLogStore.e(tag, "regenerateHistoryResult resolve models failed id=$resultId", error)
            return
        }
        workflowTaskManager.startRegenerateAnswerTask(
            existingResult = existing,
            models = models,
            bitmaps = bitmaps
        )
    }
}
