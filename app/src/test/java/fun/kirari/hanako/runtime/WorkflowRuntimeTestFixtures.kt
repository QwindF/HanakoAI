package `fun`.kirari.hanako.runtime

import `fun`.kirari.hanako.data.AppSettings
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute

internal class InMemoryWorkflowHistoryRepository(
    initialSettings: AppSettings = AppSettings()
) : WorkflowHistoryRepository {
    var settings: AppSettings = initialSettings
        private set

    override suspend fun read(): AppSettings = settings

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        settings = transform(settings)
    }
}

internal fun testProcessingResult(
    id: String,
    answer: String = "",
    screenshotPaths: List<String> = emptyList()
): ProcessingResult {
    return ProcessingResult(
        id = id,
        assistantName = "assistant",
        route = ProcessingRoute.OCR_THEN_LLM,
        answer = answer,
        screenshotPaths = screenshotPaths
    )
}
