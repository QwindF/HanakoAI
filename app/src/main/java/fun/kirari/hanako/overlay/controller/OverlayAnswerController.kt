package `fun`.kirari.hanako.overlay.controller

import `fun`.kirari.hanako.overlay.workflow.ProcessingPipeline

import `fun`.kirari.hanako.overlay.state.AutoRunState
import `fun`.kirari.hanako.overlay.state.OverlayLaunchMode
import `fun`.kirari.hanako.overlay.state.OverlaySheetMode
import `fun`.kirari.hanako.overlay.state.OverlayUiState

import android.graphics.Bitmap
import `fun`.kirari.hanako.automation.BubbleState
import `fun`.kirari.hanako.automation.BubbleStateMachine
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.loadHistoryBitmaps
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.runtime.WorkflowTaskManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class OverlayAnswerController(
    private val uiState: MutableStateFlow<OverlayUiState>,
    private val pipeline: ProcessingPipeline,
    private val workflowTaskManager: WorkflowTaskManager,
    private val bubbleStateMachine: BubbleStateMachine
) {
    private val tag = "HanakoOverlayAnswer"

    fun process(bitmap: Bitmap) {
        process(listOf(bitmap))
    }

    fun process(bitmaps: List<Bitmap>) {
        val state = uiState.value
        val firstBitmap = bitmaps.firstOrNull() ?: return
        AppDebugLogStore.i(tag, "process start route=${state.settings.processingRoute} bitmapCount=${bitmaps.size}")

        val models = runCatching { pipeline.resolveModels(state) }.getOrElse { error ->
            uiState.update { it.copy(error = error.message) }
            return
        }

        uiState.update {
            it.copy(
                selectedBitmap = firstBitmap,
                liveOcrText = "",
                liveAnswerText = "",
                result = null,
                error = null,
                working = true,
                sheetVisible = true,
                sheetMode = OverlaySheetMode.RESULT
            )
        }
        workflowTaskManager.startAnswerTask(
            models = models,
            bitmaps = bitmaps,
            onStateChanged = { result ->
                uiState.update { current ->
                    current.copy(
                        result = result,
                        liveOcrText = result.extractedText,
                        liveAnswerText = result.answer
                    )
                }
            },
            onFinished = { outcome ->
                outcome.onSuccess { result ->
                    AppDebugLogStore.i(tag, "process success resultId=${result.id} answerLength=${result.answer.length}")
                    uiState.update {
                        it.copy(
                            working = false,
                            result = result,
                            liveOcrText = result.extractedText,
                            liveAnswerText = result.answer,
                            autoRunState = AutoRunState.IDLE,
                            autoCopiedLabel = null,
                            pendingVibrationLetters = null
                        )
                    }
                    bubbleStateMachine.forceState(BubbleState.Idle)
                }.onFailure { error ->
                    AppDebugLogStore.e(tag, "process failed", error)
                    uiState.update {
                        it.copy(
                            working = false,
                            autoRunState = AutoRunState.IDLE,
                            pendingVibrationLetters = null,
                            error = error.message ?: "处理失败"
                        )
                    }
                }
            }
        )
    }

    fun regenerateCurrentResult() {
        val existingResult = uiState.value.result ?: return
        if (existingResult.automationAction != null || uiState.value.working) return
        val bitmaps = existingResult.loadHistoryBitmaps()
        if (bitmaps.isEmpty()) {
            uiState.update { it.copy(error = "找不到原始截图，无法重新生成") }
            return
        }
        regenerateExistingResult(existingResult, bitmaps)
    }

    private fun regenerateExistingResult(existingResult: ProcessingResult, bitmaps: List<Bitmap>) {
        val state = uiState.value
        val firstBitmap = bitmaps.firstOrNull() ?: return
        val models = runCatching { pipeline.resolveModels(state) }.getOrElse { error ->
            uiState.update { it.copy(error = error.message) }
            return
        }

        uiState.update {
            it.copy(
                selectedBitmap = firstBitmap,
                liveOcrText = "",
                liveAnswerText = "",
                error = null,
                working = true,
                sheetVisible = true,
                sheetMode = OverlaySheetMode.RESULT,
                result = existingResult.copy(detail = "正在重新生成")
            )
        }
        workflowTaskManager.startRegenerateAnswerTask(
            existingResult = existingResult,
            models = models,
            bitmaps = bitmaps,
            onStateChanged = { result ->
                uiState.update { current ->
                    current.copy(
                        result = result,
                        liveOcrText = result.extractedText,
                        liveAnswerText = result.answer
                    )
                }
            },
            onFinished = { outcome ->
                outcome.onSuccess { regenerated ->
                    AppDebugLogStore.i(tag, "regenerate success resultId=${regenerated.id} answerLength=${regenerated.answer.length}")
                    uiState.update {
                        it.copy(
                            working = false,
                            result = regenerated,
                            liveOcrText = regenerated.extractedText,
                            liveAnswerText = regenerated.answer,
                            autoRunState = AutoRunState.IDLE,
                            autoCopiedLabel = null,
                            pendingVibrationLetters = null
                        )
                    }
                    bubbleStateMachine.forceState(BubbleState.Idle)
                }.onFailure { error ->
                    AppDebugLogStore.e(tag, "regenerate failed", error)
                    uiState.update {
                        it.copy(
                            working = false,
                            autoRunState = AutoRunState.IDLE,
                            pendingVibrationLetters = null,
                            error = error.message ?: "处理失败"
                        )
                    }
                }
            }
        )
    }
}
