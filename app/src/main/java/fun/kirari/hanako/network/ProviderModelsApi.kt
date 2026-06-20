package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProviderKind
import `fun`.kirari.hanako.data.SettingsStore
import `fun`.kirari.hanako.data.modelsRequestUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

data class RemoteModelOption(
    val id: String,
    val displayName: String = id
)

data class ConnectionTestResult(
    val success: Boolean,
    val latencyMs: Long = 0,
    val errorMessage: String = ""
)

internal class ProviderModelsApi(
    private val clientProvider: NetworkClientProvider = NetworkClientProvider(),
    private val kirariAuthManager: KirariAuthManager? = null,
    private val settingsStore: SettingsStore? = null,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun listModels(
        provider: ModelProviderConfig,
        trustAllHttpsCertificates: Boolean = false
    ): List<RemoteModelOption> = withContext(Dispatchers.IO) {
        val authHeader = authorizationHeader(provider, trustAllHttpsCertificates)
        val request = Request.Builder()
            .url(provider.modelsRequestUrl())
            .apply {
                authHeader?.let { addHeader("Authorization", it) }
            }
            .get()
            .build()

        clientProvider.client(trustAllHttpsCertificates).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body?.string()}")
            }

            val body = response.body?.string().orEmpty()
            return@withContext when (provider.kind) {
                ProviderKind.GOOGLE -> parseGoogleModels(body)
                ProviderKind.KIRARI_NETWORK -> parseKirariModels(body)
                ProviderKind.OPENAI_COMPATIBLE,
                ProviderKind.OPENAI_RESPONSES,
                ProviderKind.ANTHROPIC -> parseOpenAiLikeModels(body)
            }
        }
    }

    private fun parseOpenAiLikeModels(body: String): List<RemoteModelOption> {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { modelJson ->
            val modelObject = modelJson.jsonObject
            val id = modelObject["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val displayName = modelObject["display_name"]?.jsonPrimitive?.contentOrNull
                ?: modelObject["name"]?.jsonPrimitive?.contentOrNull
                ?: id
            RemoteModelOption(id = id, displayName = displayName)
        }
    }

    suspend fun testConnection(
        provider: ModelProviderConfig,
        trustAllHttpsCertificates: Boolean = false
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val authHeader = authorizationHeader(provider, trustAllHttpsCertificates)
            val request = Request.Builder()
                .url(provider.modelsRequestUrl())
                .apply {
                    authHeader?.let { addHeader("Authorization", it) }
                }
                .get()
                .build()

            clientProvider.client(trustAllHttpsCertificates).newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val message = parseErrorMessage(body) ?: "HTTP ${response.code}"
                    return@withContext ConnectionTestResult(
                        success = false,
                        latencyMs = latency,
                        errorMessage = message
                    )
                }
                return@withContext ConnectionTestResult(success = true, latencyMs = latency)
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            val message = when {
                e is java.net.UnknownHostException -> "无法解析主机名"
                e is java.net.ConnectException -> "连接被拒绝"
                e is java.net.SocketTimeoutException -> "连接超时"
                e is javax.net.ssl.SSLException -> "SSL 证书错误"
                else -> e.message ?: "未知错误"
            }
            return@withContext ConnectionTestResult(
                success = false,
                latencyMs = latency,
                errorMessage = message
            )
        }
    }

    private fun parseErrorMessage(body: String): String? {
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private fun parseGoogleModels(body: String): List<RemoteModelOption> {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["models"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { modelJson ->
            val modelObject = modelJson.jsonObject
            val id = modelObject["name"]?.jsonPrimitive?.contentOrNull?.substringAfterLast('/') ?: return@mapNotNull null
            val displayName = modelObject["displayName"]?.jsonPrimitive?.contentOrNull ?: id
            RemoteModelOption(id = id, displayName = displayName)
        }
    }

    private fun parseKirariModels(body: String): List<RemoteModelOption> {
        val root = json.parseToJsonElement(body).jsonObject
        val models = root["models"]?.jsonArray ?: return emptyList()
        val defaultModel = root["default_model"]?.jsonPrimitive?.contentOrNull
        return models.mapNotNull { element ->
            val id = element.jsonPrimitive.contentOrNull ?: return@mapNotNull null
            val display = if (id == defaultModel) "$id (default)" else id
            RemoteModelOption(id = id, displayName = display)
        }
    }

    private suspend fun authorizationHeader(
        provider: ModelProviderConfig,
        trustAllHttpsCertificates: Boolean
    ): String? {
        return when (provider.kind) {
            ProviderKind.GOOGLE -> null
            ProviderKind.ANTHROPIC -> null
            ProviderKind.KIRARI_NETWORK -> {
                val manager = requireNotNull(kirariAuthManager) { "KirariAuthManager is required for Kirari provider" }
                val store = requireNotNull(settingsStore) { "SettingsStore is required for Kirari provider" }
                val settings = store.read()
                val token = manager.ensureValidAccessToken(settings, trustAllHttpsCertificates)
                require(token.isNotBlank()) { "请先登录 The Kirari Network" }
                "Bearer $token"
            }
            else -> {
                require(provider.apiKey.isNotBlank()) { "请先填写 API Key" }
                "Bearer ${provider.apiKey}"
            }
        }
    }
}
