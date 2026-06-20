package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProviderKind
import `fun`.kirari.hanako.data.SettingsStore
import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

internal class UnifiedLLMClient(
    private val clientProvider: NetworkClientProvider = NetworkClientProvider(),
    private val kirariAuthManager: KirariAuthManager? = null,
    private val settingsStore: SettingsStore? = null,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val tag = "HanakoUnifiedLLM"

    suspend fun stream(
        provider: ModelProviderConfig,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imagesBase64: List<String> = emptyList(),
        tools: List<ToolDef>? = null,
        firstDeltaTimeoutMillis: Long,
        trustAllHttpsCertificates: Boolean = false
    ): Flow<LlmEvent> {
        AppDebugLogStore.i(tag, "stream provider=${provider.kind} model=$model imageCount=${imagesBase64.size} hasTools=${tools != null} trustAllHttps=$trustAllHttpsCertificates")
        val sseClient = SseStreamClient(clientProvider.client(trustAllHttpsCertificates))
        val adapter = when (provider.kind) {
            ProviderKind.OPENAI_COMPATIBLE -> OpenAiChatAdapter(sseClient, json)
            ProviderKind.OPENAI_RESPONSES -> OpenAiResponsesAdapter(sseClient, json)
            ProviderKind.ANTHROPIC -> AnthropicAdapter(sseClient, json)
            ProviderKind.GOOGLE -> GoogleAdapter(sseClient, json)
            ProviderKind.KIRARI_NETWORK -> KirariChatAdapter(
                clientProvider = clientProvider,
                kirariAuthManager = requireNotNull(kirariAuthManager) { "KirariAuthManager is required for Kirari provider" },
                settingsStore = requireNotNull(settingsStore) { "SettingsStore is required for Kirari provider" },
                json = json
            )
        }
        return adapter.stream(
            StreamRequest(
                provider = provider,
                model = model,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                imagesBase64 = imagesBase64,
                tools = tools,
                firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
                trustAllHttpsCertificates = trustAllHttpsCertificates
            )
        )
    }
}
