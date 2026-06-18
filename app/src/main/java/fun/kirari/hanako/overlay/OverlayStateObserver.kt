package `fun`.kirari.hanako.overlay

import android.content.Context
import `fun`.kirari.hanako.copyToClipboard
import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class OverlayStateObserver(
    private val context: Context,
    private val scope: CoroutineScope,
    private val viewModel: OverlayViewModel,
    private val bubbleWindowController: BubbleWindowController,
    private val panelWindowController: PanelWindowController,
    private val onNotifyAutomationCompleted: (String?) -> Unit,
    private val onVibrateLetters: suspend (String, `fun`.kirari.hanako.data.AutomationSettings) -> Unit
) {
    private val logTag = "HanakoOverlayState"
    private var completionResetJob: Job? = null
    private var letterVibrationJob: Job? = null
    private var lastHandledCompletionId: String? = null

    fun start(): Job {
        return scope.launch {
            viewModel.uiState.collect { state ->
                AppDebugLogStore.d(
                    logTag,
                    "uiState launchMode=${state.launchMode} autoRunState=${state.autoRunState} bubble=${state.bubbleState::class.simpleName} sheetVisible=${state.sheetVisible} working=${state.working} resultId=${state.result?.id} error=${state.error}"
                )
                bubbleWindowController.update(state.bubbleState, state.launchMode)
                if (state.sheetVisible) {
                    panelWindowController.showOrUpdate(state.sheetMode)
                } else {
                    panelWindowController.hideWithAnimation()
                }
                handleAutoCompletionState(state)
            }
        }
    }

    fun stop() {
        completionResetJob?.cancel()
        completionResetJob = null
        letterVibrationJob?.cancel()
        letterVibrationJob = null
    }

    private fun handleAutoCompletionState(state: OverlayUiState) {
        if (state.autoRunState != AutoRunState.COMPLETED) {
            if (completionResetJob != null) {
                AppDebugLogStore.d(logTag, "auto completion reset job cancelled because state=${state.autoRunState}")
            }
            completionResetJob?.cancel()
            completionResetJob = null
            letterVibrationJob?.cancel()
            letterVibrationJob = null
            return
        }

        val completionId = state.result?.id ?: state.autoCopiedLabel
        if (completionId != null && completionId != lastHandledCompletionId) {
            AppDebugLogStore.i(
                logTag,
                "auto completion handled completionId=$completionId copiedLabel=${state.autoCopiedLabel} bubble=${state.bubbleState::class.simpleName}"
            )
            lastHandledCompletionId = completionId
            state.autoCopiedLabel?.let { copyToClipboard(context, "Hanako Auto Copy", it) }
            if (state.settings.automation.completionNotificationEnabled) {
                onNotifyAutomationCompleted(state.autoCopiedLabel)
            }
            completionResetJob?.cancel()
            completionResetJob = scope.launch {
                delay(AUTO_COMPLETED_VISIBLE_MS)
                viewModel.consumeAutoCompletedState()
            }
        }

        if (state.settings.automation.staticModeEnabled && !state.pendingVibrationLetters.isNullOrBlank()) {
            val letters = state.pendingVibrationLetters
            letterVibrationJob?.cancel()
            letterVibrationJob = scope.launch {
                AppDebugLogStore.i(logTag, "start pending letter vibration letters=$letters")
                onVibrateLetters(letters, state.settings.automation)
                viewModel.consumePendingVibrationLetters()
            }
        }
    }

    private companion object {
        const val AUTO_COMPLETED_VISIBLE_MS = 2800L
    }
}
