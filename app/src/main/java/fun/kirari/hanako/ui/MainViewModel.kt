package `fun`.kirari.hanako.ui

import `fun`.kirari.hanako.ui.provider.ConnectionTestManager
import `fun`.kirari.hanako.ui.provider.KirariAccountState
import `fun`.kirari.hanako.ui.provider.KirariAuthController
import `fun`.kirari.hanako.ui.provider.ProviderMetaState
import `fun`.kirari.hanako.ui.provider.ProviderRuntimeController
import `fun`.kirari.hanako.ui.search.WebSearchQuotaController
import `fun`.kirari.hanako.ui.search.WebSearchQuotaState
import `fun`.kirari.hanako.ui.settings.SettingsEditorController
import `fun`.kirari.hanako.ui.update.AppUpdateController
import `fun`.kirari.hanako.ui.update.AppUpdateUiState

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import `fun`.kirari.hanako.HanakoApplication
import `fun`.kirari.hanako.BuildConfig
import `fun`.kirari.hanako.data.AppSettings
import `fun`.kirari.hanako.data.AssistantPreset
import `fun`.kirari.hanako.data.AutomationSettings
import `fun`.kirari.hanako.data.ModelPurpose
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ModelSelection
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.ScreenCaptureMethod
import `fun`.kirari.hanako.data.SettingsRepository
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.data.KirariSettings
import `fun`.kirari.hanako.data.WebSearchSettings
import `fun`.kirari.hanako.localocr.LocalOcrManager
import `fun`.kirari.hanako.ui.history.HistoryWorkflowController
import `fun`.kirari.hanako.ui.history.RunningHistoryTaskUiState
import `fun`.kirari.hanako.ui.history.HistoryChatRequestState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val tag = "HanakoMainViewModel"
    private val container = (application as HanakoApplication).container
    private val repository: SettingsRepository = container.settingsRepository
    private val localOcrManager: LocalOcrManager = container.localOcrManager
    val settings: StateFlow<AppSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings()
    )
    private val historyWorkflowController = HistoryWorkflowController(
        scope = viewModelScope,
        repository = repository,
        settings = settings,
        processingPipeline = container.workflow.pipeline,
        workflowTaskManager = container.workflow.taskManager,
        unifiedLLMClient = container.unifiedLLMClient
    )
    val runningHistoryTasks: StateFlow<Map<String, RunningHistoryTaskUiState>> =
        historyWorkflowController.runningHistoryTasks
    val liveWorkflowResults: StateFlow<Map<String, ProcessingResult>> =
        historyWorkflowController.liveWorkflowResults
    val mergedHistory: StateFlow<List<ProcessingResult>> =
        historyWorkflowController.mergedHistory
    val historyChatRequestStates: StateFlow<Map<String, HistoryChatRequestState>> =
        historyWorkflowController.chatRequestStates
    private val providerRuntimeController = ProviderRuntimeController(
        scope = viewModelScope,
        settings = settings,
        providerModelsApi = container.providerModelsApi,
        refreshKirariSession = { syncKirariSessionStatus(force = true) }
    )
    val connectionTestManager: ConnectionTestManager =
        providerRuntimeController.connectionTestManager
    val providerMetaState: StateFlow<ProviderMetaState> =
        providerRuntimeController.providerMetaState
    private val settingsEditorController = SettingsEditorController(
        scope = viewModelScope,
        repository = repository,
        providerMetaState = providerMetaState
    )
    private val webSearchQuotaController = WebSearchQuotaController(
        scope = viewModelScope,
        settings = settings,
        tavilyUsageApi = container.tavilyUsageApi
    )
    val webSearchQuotaState: StateFlow<WebSearchQuotaState> =
        webSearchQuotaController.state
    private val appUpdateController = AppUpdateController(
        scope = viewModelScope,
        appUpdateApi = container.appUpdateApi
    )
    val appUpdateState: StateFlow<AppUpdateUiState> = appUpdateController.state
    private val kirariAuthController = KirariAuthController(
        scope = viewModelScope,
        settingsStore = container.settingsStore,
        kirariAuthManager = container.kirariAuthManager,
        settingsProvider = { settings.value },
        clearProviderMeta = { providerRuntimeController.clearProviderMeta() }
    )
    val kirariAuthMessage: StateFlow<String?> = kirariAuthController.message
    val kirariAccountState: StateFlow<KirariAccountState> = kirariAuthController.accountState
    val kirariRedirectTarget: StateFlow<String?> = kirariAuthController.redirectTarget

    init {
        syncLocalOcrInstallation()
        syncKirariSessionStatus()
        appUpdateController.checkOnceSilently()
    }

    fun updateProvider(provider: ModelProviderConfig) {
        settingsEditorController.updateProvider(provider)
    }

    fun addProvider() {
        settingsEditorController.addProvider()
    }

    fun selectProvider(providerId: String) {
        settingsEditorController.selectProvider(providerId)
    }

    fun deleteProvider(providerId: String) {
        settingsEditorController.deleteProvider(providerId)
    }

    fun updateAssistant(assistant: AssistantPreset) {
        settingsEditorController.updateAssistant(assistant)
    }

    fun addAssistant() {
        settingsEditorController.addAssistant()
    }

    fun selectAssistant(assistantId: String) = settingsEditorController.selectAssistant(assistantId)

    fun deleteAssistant(assistantId: String) {
        settingsEditorController.deleteAssistant(assistantId)
    }

    fun setRoute(route: ProcessingRoute) {
        settingsEditorController.setRoute(route)
    }

    fun setScreenCaptureMethod(method: ScreenCaptureMethod) {
        settingsEditorController.setScreenCaptureMethod(method)
    }

    fun updateModelSelection(purpose: ModelPurpose, selection: ModelSelection) =
        settingsEditorController.updateModelSelection(purpose, selection)

    fun syncLocalOcrInstallation() {
        viewModelScope.launch {
            AppDebugLogStore.i("LocalOcrUi", "syncLocalOcrInstallation start")
            val status = withContext(Dispatchers.IO) { localOcrManager.installationStatus() }
            AppDebugLogStore.i("LocalOcrUi", "syncLocalOcrInstallation done installed=${status.installed}")
            repository.update { current ->
                current.copy(
                    localOcr = current.localOcr.copy(
                        installed = status.installed,
                        lastMessage = if (status.installed) "本地 ML Kit 已内置，可直接使用" else "本地 ML Kit 当前不可用"
                    )
                )
            }
        }
    }

    fun updateModelSelectionWithFavorite(
        purpose: ModelPurpose,
        selection: ModelSelection,
        favoriteModel: Boolean = false
    ) = settingsEditorController.updateModelSelectionWithFavorite(purpose, selection, favoriteModel)

    fun toggleFavoriteModel(providerId: String, modelId: String) =
        settingsEditorController.toggleFavoriteModel(providerId, modelId)

    fun removeFavoriteModel(providerId: String, modelId: String) =
        settingsEditorController.removeFavoriteModel(providerId, modelId)

    fun updateAutomationSettings(transform: (AutomationSettings) -> AutomationSettings) {
        settingsEditorController.updateAutomationSettings(transform)
    }

    fun setTrustAllHttpsCertificates(enabled: Boolean) {
        settingsEditorController.setTrustAllHttpsCertificates(enabled)
    }

    fun updateKirariSettings(transform: (KirariSettings) -> KirariSettings) {
        settingsEditorController.updateKirariSettings(transform)
    }

    fun updateWebSearchSettings(transform: (WebSearchSettings) -> WebSearchSettings) {
        settingsEditorController.updateWebSearchSettings(transform)
    }

    fun queryWebSearchQuota() {
        webSearchQuotaController.query()
    }

    fun resetWebSearchQuotaState() {
        webSearchQuotaController.reset()
    }

    fun startKirariLogin(onReady: (String) -> Unit) {
        kirariAuthController.startLogin(onReady)
    }

    fun handleKirariRedirect(uri: Uri) {
        kirariAuthController.handleRedirect(uri)
    }

    fun consumeKirariRedirectTarget() {
        kirariAuthController.consumeRedirectTarget()
    }

    fun logoutKirari() {
        kirariAuthController.logout()
    }

    fun consumeKirariAuthMessage() {
        kirariAuthController.consumeMessage()
    }

    fun clearHistory() {
        historyWorkflowController.clearHistory()
    }

    fun deleteHistoryItem(resultId: String) {
        historyWorkflowController.deleteHistoryItem(resultId)
    }

    fun saveResult(result: ProcessingResult) {
        historyWorkflowController.saveResult(result)
    }

    fun regenerateHistoryResult(resultId: String) {
        historyWorkflowController.regenerateHistoryResult(resultId)
    }

    fun sendHistoryFollowUp(resultId: String, prompt: String) {
        historyWorkflowController.sendHistoryFollowUp(resultId, prompt)
    }

    fun retryHistoryFollowUp(resultId: String, turnIndex: Int) {
        historyWorkflowController.retryHistoryFollowUp(resultId, turnIndex)
    }

    fun testProviderConnection(provider: ModelProviderConfig) {
        providerRuntimeController.testProviderConnection(provider)
    }

    fun resetConnectionTest(providerId: String) {
        providerRuntimeController.resetConnectionTest(providerId)
    }

    fun loadProviderMeta(provider: ModelProviderConfig) {
        providerRuntimeController.loadProviderMeta(provider)
    }

    fun resetProviderMeta() {
        providerRuntimeController.resetProviderMeta()
    }

    fun shouldSuggestKirariAutoSetup(settings: AppSettings, providerMetaState: ProviderMetaState): Boolean {
        return settingsEditorController.shouldSuggestKirariAutoSetup(settings, providerMetaState)
    }

    fun applyKirariAutoSetup() {
        settingsEditorController.applyKirariAutoSetup()
    }

    fun clearDebugLogs() {
        AppDebugLogStore.clear()
    }

    fun showUpdateDialog() {
        appUpdateController.showDialog()
    }

    fun dismissUpdateDialog() {
        appUpdateController.dismissDialog()
    }

    fun hasKirariClientId(): Boolean = BuildConfig.KIRARI_OIDC_CLIENT_ID.isNotBlank()

    fun syncKirariSessionStatus(force: Boolean = false) {
        kirariAuthController.syncSessionStatus(force)
    }
}
