package `fun`.kirari.hanako.runtime

import android.content.Context
import `fun`.kirari.hanako.data.SettingsRepository
import `fun`.kirari.hanako.localocr.LocalOcrManager
import `fun`.kirari.hanako.network.UnifiedLLMClient
import `fun`.kirari.hanako.network.search.SearchOrchestrator
import `fun`.kirari.hanako.overlay.ProcessingPipeline
import `fun`.kirari.hanako.overlay.workflow.HanakoWorkflowFactory

internal class WorkflowContainer(
    appContext: Context,
    settingsRepository: SettingsRepository,
    unifiedLLMClient: UnifiedLLMClient,
    localOcrManager: LocalOcrManager,
    searchOrchestrator: SearchOrchestrator?
) {
    val pipeline = ProcessingPipeline()
    val workflowFactory = HanakoWorkflowFactory(
        appContext = appContext,
        unifiedClient = unifiedLLMClient,
        localOcrManager = localOcrManager,
        searchOrchestrator = searchOrchestrator,
        pipeline = pipeline
    )
    val taskManager = WorkflowTaskManager(
        repository = settingsRepository,
        pipeline = pipeline,
        workflowFactory = workflowFactory
    )
}
