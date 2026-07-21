package `fun`.kirari.hanako.feature.history.presentation

import `fun`.kirari.hanako.core.data.AppSettings
import `fun`.kirari.hanako.core.data.ModelSelection
import `fun`.kirari.hanako.core.model.ProcessingResult
import `fun`.kirari.hanako.solve.application.SolveOperations
import `fun`.kirari.hanako.solve.model.WorkflowTaskKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RunningHistoryTaskUiState(
    val historyId: String,
    val answerVersionIndex: Int? = null
)

data class HistoryChatRequestState(
    val sending: Boolean = false,
    val activeTurnId: String? = null
)

internal class HistoryWorkflowController(
    private val scope: CoroutineScope,
    private val settings: StateFlow<AppSettings>,
    private val solveOperations: SolveOperations
) {
    private val _conversationModelSelections = MutableStateFlow<Map<String, ModelSelection>>(emptyMap())
    val conversationModelSelections: StateFlow<Map<String, ModelSelection>> =
        _conversationModelSelections.asStateFlow()
    private val historyState = solveOperations.observeHistory(settings.map { it.history })
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = `fun`.kirari.hanako.solve.application.SolveHistoryState(
                results = emptyList(),
                activeTasks = emptyMap()
            )
        )

    val chatRequestStates: StateFlow<Map<String, HistoryChatRequestState>> = historyState
        .map { state ->
            state.activeTasks.values
                .filter { it.kind == WorkflowTaskKind.CONVERSATION }
                .associate { task ->
                    task.historyId to HistoryChatRequestState(
                        sending = true,
                        activeTurnId = task.conversationTurnId
                    )
                }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap()
        )

    val runningHistoryTasks: StateFlow<Map<String, RunningHistoryTaskUiState>> = historyState
        .map { state ->
            state.activeTasks.values
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

    val liveWorkflowResults: StateFlow<Map<String, ProcessingResult>> = historyState
        .map { state -> state.results.associateBy(ProcessingResult::id) }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap()
        )

    val mergedHistory: StateFlow<List<ProcessingResult>> = historyState
        .map { it.results }
        .stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    fun clearHistory() {
        _conversationModelSelections.value = emptyMap()
        scope.launch {
            solveOperations.clearHistory()
        }
    }

    fun deleteHistoryItem(resultId: String) {
        _conversationModelSelections.value = _conversationModelSelections.value - resultId
        scope.launch {
            solveOperations.removeHistoryResult(resultId)
        }
    }

    fun regenerateHistoryResult(resultId: String) {
        scope.launch {
            solveOperations.regenerate(settings.value, resultId)
        }
    }

    fun sendHistoryFollowUp(resultId: String, prompt: String) {
        scope.launch {
            solveOperations.continueConversation(
                settings = settings.value,
                historyId = resultId,
                prompt = prompt,
                modelSelection = _conversationModelSelections.value[resultId]
            )
        }
    }

    fun retryHistoryFollowUp(resultId: String, turnIndex: Int) {
        scope.launch {
            solveOperations.continueConversation(
                settings = settings.value,
                historyId = resultId,
                prompt = "",
                retryIndex = turnIndex,
                modelSelection = _conversationModelSelections.value[resultId]
            )
        }
    }

    fun selectConversationModel(resultId: String, selection: ModelSelection) {
        _conversationModelSelections.value = _conversationModelSelections.value + (resultId to selection)
    }
}
