package `fun`.kirari.hanako.runtime

import android.content.Context
import `fun`.kirari.hanako.data.SettingsRepository
import `fun`.kirari.hanako.localocr.LocalOcrManager
import `fun`.kirari.hanako.network.UnifiedLLMClient
import `fun`.kirari.hanako.network.search.SearchOrchestrator
import `fun`.kirari.hanako.overlay.ProcessingPipeline
import `fun`.kirari.hanako.overlay.workflow.HanakoWorkflowFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal class WorkflowContainer(
    appContext: Context,
    settingsRepository: SettingsRepository,
    unifiedLLMClient: UnifiedLLMClient,
    localOcrManager: LocalOcrManager,
    searchOrchestrator: SearchOrchestrator?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val historyRepository = SettingsWorkflowHistoryRepository(settingsRepository)
    val pipeline = ProcessingPipeline()
    val workflowFactory = HanakoWorkflowFactory(
        appContext = appContext,
        unifiedClient = unifiedLLMClient,
        localOcrManager = localOcrManager,
        searchOrchestrator = searchOrchestrator,
        pipeline = pipeline
    )
    val resultStore = WorkflowResultStore(
        repository = historyRepository,
        scope = scope
    )
    val taskRegistry = WorkflowTaskRegistry()
    val taskManager = WorkflowTaskManager(
        repository = historyRepository,
        resultStore = resultStore,
        taskRegistry = taskRegistry,
        workflowFactory = workflowFactory,
        scope = scope
    )
}
