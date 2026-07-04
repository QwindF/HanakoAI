package `fun`.kirari.hanako.overlay

import `fun`.kirari.hanako.data.AssistantPreset
import `fun`.kirari.hanako.data.LOCAL_OCR_PROVIDER_ID
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ModelPurpose
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.WebSearchSettings
import `fun`.kirari.hanako.data.resolveModelName
import `fun`.kirari.hanako.data.resolveModelProvider

internal class ProcessingPipeline {
    data class ResolvedModels(
        val assistant: AssistantPreset,
        val ocrProvider: ModelProviderConfig?,
        val ocrModel: String,
        val textProvider: ModelProviderConfig?,
        val textModel: String,
        val visionProvider: ModelProviderConfig?,
        val visionModel: String,
        val firstDeltaTimeoutMillis: Long,
        val route: ProcessingRoute,
        val usingLocalOcr: Boolean,
        val trustAllHttpsCertificates: Boolean,
        val webSearchSettings: WebSearchSettings
    )

    fun resolveModels(state: OverlayUiState): ResolvedModels {
        val assistant = state.settings.assistants.firstOrNull { it.id == state.settings.selectedAssistantId }
            ?: error("请先配置助手")
        return ResolvedModels(
            assistant = assistant,
            ocrProvider = state.settings.resolveModelProvider(ModelPurpose.OCR),
            ocrModel = state.settings.resolveModelName(ModelPurpose.OCR),
            textProvider = state.settings.resolveModelProvider(ModelPurpose.TEXT),
            textModel = state.settings.resolveModelName(ModelPurpose.TEXT),
            visionProvider = state.settings.resolveModelProvider(ModelPurpose.VISION),
            visionModel = state.settings.resolveModelName(ModelPurpose.VISION),
            firstDeltaTimeoutMillis = state.settings.automation.autoModeTimeoutSeconds.coerceAtLeast(1) * 1000L,
            route = state.settings.processingRoute,
            usingLocalOcr = state.settings.ocrModelSelection.providerId == LOCAL_OCR_PROVIDER_ID,
            trustAllHttpsCertificates = state.settings.trustAllHttpsCertificates,
            webSearchSettings = state.settings.webSearch
        )
    }

    fun buildModelSummary(model: String, providerName: String?): String {
        val trimmedModel = model.trim()
        val trimmedProvider = providerName?.trim().orEmpty()
        if (trimmedModel.isBlank()) return ""
        return if (trimmedProvider.isBlank()) trimmedModel else "$trimmedModel（$trimmedProvider）"
    }

    fun validateOcrThenLlmModels(models: ResolvedModels) {
        if ((!models.usingLocalOcr && (models.ocrProvider == null || models.ocrModel.isBlank())) ||
            models.textProvider == null || models.textModel.isBlank()
        ) {
            error("请先在模型设置中配置 OCR 和文本模型")
        }
    }

    fun validateVisionModels(models: ResolvedModels) {
        if (models.visionProvider == null || models.visionModel.isBlank()) {
            error("请先在模型设置中配置多模态模型")
        }
    }
}
