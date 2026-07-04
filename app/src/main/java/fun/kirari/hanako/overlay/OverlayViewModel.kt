package `fun`.kirari.hanako.overlay

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `fun`.kirari.hanako.AppContainer
import `fun`.kirari.hanako.automation.BubbleMenuItem
import `fun`.kirari.hanako.automation.BubbleState
import `fun`.kirari.hanako.automation.BubbleStateMachine
import `fun`.kirari.hanako.data.ModelPurpose
import `fun`.kirari.hanako.data.ModelSelection
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.SettingsRepository
import `fun`.kirari.hanako.data.loadHistoryBitmaps
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.network.ProviderModelsApi
import `fun`.kirari.hanako.runtime.WorkflowTaskManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class OverlayViewModel(
    private val appContext: Context,
    private val repository: SettingsRepository,
    private val pipeline: ProcessingPipeline,
    private val workflowTaskManager: WorkflowTaskManager,
    val providerModelsApi: ProviderModelsApi
) : ViewModel() {
    private val tag = "HanakoOverlayVM"
    
    // 新的状态机
    val bubbleStateMachine = BubbleStateMachine()
    
    private val _uiState = MutableStateFlow(OverlayUiState())
    val uiState: StateFlow<OverlayUiState> = _uiState.asStateFlow()
    private val multiPageCaptureController = MultiPageCaptureController(
        appContext = appContext,
        scope = viewModelScope,
        uiState = _uiState,
        bubbleStateMachine = bubbleStateMachine
    )
    private val autoProcessingController = AutoProcessingController(
        appContext = appContext,
        scope = viewModelScope,
        uiState = _uiState,
        pipeline = pipeline,
        workflowTaskManager = workflowTaskManager,
        bubbleStateMachine = bubbleStateMachine
    )
    private val interactionController = OverlayInteractionController(
        uiState = _uiState,
        bubbleStateMachine = bubbleStateMachine,
        openCropSheet = ::openCropSheet,
        capturePage = ::capturePage,
        sendCaptures = ::sendCaptures,
        exitMultiPageCaptureMode = ::exitMultiPageCaptureMode,
        cancelActiveProcessing = autoProcessingController::cancelActiveProcessing,
        enterMultiPageCaptureMode = ::enterMultiPageCaptureMode,
        isBubbleMenuEnabled = { _uiState.value.settings.automation.bubbleMenuEnabled },
        toggleProcessingRoute = ::toggleProcessingRoute,
        toggleWebSearch = ::toggleWebSearch,
        openSettings = { openMainActivity(appContext) }
    )

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
        
        // 监听状态机变化，同步到 UI 状态
        viewModelScope.launch {
            bubbleStateMachine.state.collect { bubbleState ->
                _uiState.update { it.copy(bubbleState = bubbleState) }
            }
        }
    }

    fun setLaunchMode(mode: OverlayLaunchMode) {
        AppDebugLogStore.i(tag, "setLaunchMode mode=$mode")
        _uiState.update { state ->
            state.copy(
                launchMode = mode,
                autoRunState = if (mode == OverlayLaunchMode.NORMAL) AutoRunState.IDLE else state.autoRunState,
                autoCopiedLabel = if (mode == OverlayLaunchMode.NORMAL) null else state.autoCopiedLabel,
                pendingVibrationLetters = if (mode == OverlayLaunchMode.NORMAL) null else state.pendingVibrationLetters,
                error = null
            )
        }
        // 普通模式重置状态机
        if (mode == OverlayLaunchMode.NORMAL) {
            bubbleStateMachine.forceState(BubbleState.Idle)
        }
    }

    fun openCropSheet() {
        AppDebugLogStore.i(tag, "openCropSheet launchMode=${_uiState.value.launchMode}")
        if (_uiState.value.launchMode == OverlayLaunchMode.AUTO) {
            AppDebugLogStore.i(tag, "openCropSheet delegated to processFullScreen for auto mode")
            processFullScreen()
            return
        }
        viewModelScope.launch {
            runCatching {
                `fun`.kirari.hanako.capture.ScreenCaptureManager.captureLatestBitmap(appContext, _uiState.value.settings.screenCaptureMethod)
            }.onSuccess { bitmap ->
                AppDebugLogStore.i(tag, "openCropSheet capture success width=${bitmap.width} height=${bitmap.height}")
                _uiState.update {
                    it.copy(
                        screenshot = bitmap,
                        selectedBitmap = null,
                        liveOcrText = "",
                        liveAnswerText = "",
                        result = null,
                        error = null,
                        working = false,
                        sheetVisible = true,
                        sheetMode = OverlaySheetMode.CROP,
                        autoRunState = AutoRunState.IDLE,
                        autoCopiedLabel = null,
                        pendingVibrationLetters = null
                    )
                }
                bubbleStateMachine.forceState(BubbleState.Idle)
            }.onFailure { error ->
                AppDebugLogStore.e(tag, "openCropSheet failed", error)
                _uiState.update {
                    it.copy(
                        error = error.message ?: "截屏失败",
                        sheetVisible = true,
                        sheetMode = OverlaySheetMode.CROP
                    )
                }
            }
        }
    }

    fun processFullScreen() {
        autoProcessingController.processFullScreen()
    }

    fun process(bitmap: Bitmap) {
        process(listOf(bitmap))
    }

    fun process(bitmaps: List<Bitmap>) {
        val state = _uiState.value
        val firstBitmap = bitmaps.firstOrNull() ?: return
        AppDebugLogStore.i(tag, "process start route=${state.settings.processingRoute} bitmapCount=${bitmaps.size}")

        val models = runCatching { pipeline.resolveModels(state) }.getOrElse { error ->
            _uiState.update { it.copy(error = error.message) }
            return
        }

        _uiState.update {
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
                _uiState.update { current ->
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
                    _uiState.update {
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
                    _uiState.update {
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
        val existingResult = _uiState.value.result ?: return
        if (existingResult.automationAction != null || _uiState.value.working) return
        val bitmaps = existingResult.loadHistoryBitmaps()
        if (bitmaps.isEmpty()) {
            _uiState.update { it.copy(error = "找不到原始截图，无法重新生成") }
            return
        }
        regenerateExistingResult(existingResult, bitmaps)
    }

    private fun regenerateExistingResult(existingResult: ProcessingResult, bitmaps: List<Bitmap>) {
        val state = _uiState.value
        val firstBitmap = bitmaps.firstOrNull() ?: return
        val models = runCatching { pipeline.resolveModels(state) }.getOrElse { error ->
            _uiState.update { it.copy(error = error.message) }
            return
        }

        _uiState.update {
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
                _uiState.update { current ->
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
                    _uiState.update {
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
                    _uiState.update {
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

    fun closeSheet() {
        _uiState.update { it.copy(sheetVisible = false, error = null) }
    }

    fun consumeAutoCompletedState() {
        interactionController.consumeAutoCompletedState()
    }

    fun consumePendingVibrationLetters() {
        interactionController.consumePendingVibrationLetters()
    }

    fun onBubbleTappedAfterLettersShown() {
        interactionController.onBubbleTappedAfterLettersShown()
    }

    // 多页截图相关方法
    
    /**
     * 进入多页截图模式
     */
    fun enterMultiPageCaptureMode() {
        multiPageCaptureController.enter()
    }

    /**
     * 截图一次
     */
    fun capturePage() {
        multiPageCaptureController.capturePage()
    }

    /**
     * 发送截图给 AI
     */
    fun sendCaptures() {
        AppDebugLogStore.i(tag, "sendCaptures called state=${bubbleStateMachine.currentState::class.simpleName}")
        if (!multiPageCaptureController.canSendCaptures()) {
            AppDebugLogStore.i(tag, "sendCaptures called but cannot send")
            return
        }
        
        val bitmaps = multiPageCaptureController.capturedBitmaps()
        AppDebugLogStore.i(tag, "sendCaptures count=${bitmaps.size}")
        
        if (bitmaps.isEmpty()) {
            AppDebugLogStore.i(tag, "sendCaptures no bitmaps available")
            return
        }
        
        multiPageCaptureController.markSendCaptures()
        AppDebugLogStore.i(tag, "sendCaptures dispatched SendCaptures, new state=${bubbleStateMachine.currentState::class.simpleName}")
        autoProcessingController.processBitmaps(bitmaps)
    }

    /**
     * 退出多页截图模式
     */
    fun exitMultiPageCaptureMode() {
        multiPageCaptureController.exit()
    }

    /**
     * 处理单击事件（根据当前状态决定行为）
     */
    fun handleSingleTap() {
        interactionController.handleSingleTap()
    }

    /**
     * 处理长按事件
     * - 多图模式下：发送已截图片
     * - Idle/ShowingLetters/Copied/Error：进入多图截图模式
     * - Processing 等：展开扇形菜单
     */
    fun handleLongPress(anchorX: Int = 0, anchorY: Int = 0) {
        interactionController.handleLongPress(anchorX, anchorY)
    }

    /**
     * 处理双击事件
     * - 多图模式：退出多图
     * - Processing：取消处理
     * - Idle/ShowingLetters/Copied/Error：展开扇形菜单
     */
    fun handleDoubleTap(anchorX: Int = 0, anchorY: Int = 0) {
        interactionController.handleDoubleTap(anchorX, anchorY)
    }

    /**
     * 菜单关闭后的回调
     */
    fun onMenuDismissed() {
        interactionController.onMenuDismissed()
    }

    /**
     * 处理菜单项点击
     */
    fun handleMenuSelect(item: BubbleMenuItem) {
        interactionController.handleMenuSelect(item)
    }

    fun toggleWebSearch() {
        viewModelScope.launch {
            repository.update { current ->
                current.copy(
                    webSearch = current.webSearch.copy(enabled = !current.webSearch.enabled)
                )
            }
        }
    }

    fun selectAssistant(assistantId: String) = repository.selectAssistant(viewModelScope, assistantId)

    fun selectPreviousAssistant() {
        val current = _uiState.value.settings
        val assistants = current.assistants
        if (assistants.isEmpty()) return
        val selectedIndex = assistants.indexOfFirst { it.id == current.selectedAssistantId }.takeIf { it >= 0 } ?: 0
        val previousIndex = if (selectedIndex == 0) assistants.lastIndex else selectedIndex - 1
        selectAssistant(assistants[previousIndex].id)
    }

    fun selectNextAssistant() {
        val current = _uiState.value.settings
        val assistants = current.assistants
        if (assistants.isEmpty()) return
        val selectedIndex = assistants.indexOfFirst { it.id == current.selectedAssistantId }.takeIf { it >= 0 } ?: 0
        val nextIndex = if (selectedIndex == assistants.lastIndex) 0 else selectedIndex + 1
        selectAssistant(assistants[nextIndex].id)
    }

    fun updateModelSelection(purpose: ModelPurpose, selection: ModelSelection) =
        repository.updateModelSelection(viewModelScope, purpose, selection)

    fun updateModelSelectionWithFavorite(purpose: ModelPurpose, selection: ModelSelection, favoriteModel: Boolean = false) =
        repository.updateModelSelectionWithFavorite(viewModelScope, purpose, selection, favoriteModel)

    fun toggleFavoriteModel(providerId: String, modelId: String) =
        repository.toggleFavoriteModel(viewModelScope, providerId, modelId)

    fun toggleProcessingRoute() {
        viewModelScope.launch {
            repository.update { current ->
                current.copy(
                    processingRoute = when (current.processingRoute) {
                        ProcessingRoute.OCR_THEN_LLM -> ProcessingRoute.MULTIMODAL_DIRECT
                        ProcessingRoute.MULTIMODAL_DIRECT -> ProcessingRoute.OCR_THEN_LLM
                    }
                )
            }
        }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory {
            val container = (appContext.applicationContext as `fun`.kirari.hanako.HanakoApplication).container
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return OverlayViewModel(
                        appContext = appContext,
                        repository = container.settingsRepository,
                        pipeline = container.workflow.pipeline,
                        workflowTaskManager = container.workflow.taskManager,
                        providerModelsApi = container.providerModelsApi
                    ) as T
                }
            }
        }
    }
}
