package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.data.SettingsStore
import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class KirariChatAdapter(
    private val clientProvider: NetworkClientProvider,
    private val kirariAuthManager: KirariAuthManager,
    private val settingsStore: SettingsStore,
    private val json: Json
) : ProviderAdapter {
    private val tag = "KirariChatAdapter"
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun stream(request: StreamRequest): Flow<LlmEvent> = flow {
        val settings = settingsStore.read()
        val accessToken = kirariAuthManager.ensureValidAccessToken(
            settings = settings,
            trustAllHttpsCertificates = request.trustAllHttpsCertificates
        )
        require(accessToken.isNotBlank()) { "请先登录 The Kirari Network" }

        val payload = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", request.systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    if (request.hasImages) {
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", request.userPrompt)
                            })
                            request.imagesBase64.forEach { imageBase64 ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", "data:image/jpeg;base64,$imageBase64")
                                        put("detail", "high")
                                    })
                                })
                            }
                        })
                    } else {
                        put("content", request.userPrompt)
                    }
                })
            })
            request.tools?.let { put("tools", ToolRegistry.formatForProvider(it, request.provider.kind)) }
        }

        val bodyJson = json.encodeToString(JsonObject.serializer(), payload)
        val endpoint = "${request.provider.baseUrl.trimEnd('/')}/api/llm/chat/completions"
        val httpRequest = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .post(bodyJson.toRequestBody(mediaType))
            .build()

        clientProvider.client(request.trustAllHttpsCertificates).newCall(httpRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Kirari chat failed: HTTP ${response.code} $body")
            }
            val contentType = response.header("Content-Type").orEmpty()
            AppDebugLogStore.i(tag, "chat response code=${response.code} contentType=$contentType url=$endpoint")
            if (contentType.contains("text/event-stream", ignoreCase = true)) {
                emitSseAsEvents(body).collect { emit(it) }
            } else {
                emitJsonAsPseudoStream(body).collect { emit(it) }
            }
        }
    }

    private fun extractAssistantText(body: String): String {
        val root = json.parseToJsonElement(body).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return ""
        val message = choice["message"]?.jsonObject ?: return ""
        return message["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun emitJsonAsPseudoStream(body: String): Flow<LlmEvent> = flow {
        val text = extractAssistantText(body)
        if (text.isBlank()) {
            emit(LlmEvent.Done)
            return@flow
        }
        for (chunk in chunkForDisplay(text)) {
            emit(LlmEvent.TextDelta(chunk))
            delay(12L)
        }
        emit(LlmEvent.Done)
    }

    private fun emitSseAsEvents(body: String): Flow<LlmEvent> = flow {
        val toolCalls = mutableMapOf<Int, PendingToolCall>()
        body.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("data:")) return@forEach
            val data = line.removePrefix("data:").trim()
            if (data.isBlank()) return@forEach
            if (data == "[DONE]") {
                return@forEach
            }
            val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
                ?: return@forEach
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@forEach
            val delta = choice["delta"]?.jsonObject ?: return@forEach
            val textDelta = extractDeltaText(delta)
            if (textDelta.isNotBlank()) {
                emit(LlmEvent.TextDelta(textDelta))
            }
            val toolDelta = delta["tool_calls"]?.jsonArray ?: return@forEach
            toolDelta.forEach { item ->
                val toolCall = item.jsonObject
                val index = toolCall["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                val function = toolCall["function"]?.jsonObject ?: return@forEach
                val pending = toolCalls.getOrPut(index) { PendingToolCall() }
                function["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                    pending.name = it
                }
                pending.arguments.append(function["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        }
        toolCalls.values.forEach { tc ->
            val name = tc.name ?: return@forEach
            val args = runCatching { json.parseToJsonElement(tc.arguments.toString()).jsonObject }.getOrNull()
                ?: return@forEach
            emit(LlmEvent.ToolCall(name, args))
        }
        emit(LlmEvent.Done)
    }

    private fun extractDeltaText(delta: JsonObject): String {
        val content = delta["content"] ?: return ""
        return when {
            content is kotlinx.serialization.json.JsonPrimitive -> content.contentOrNull.orEmpty()
            content is kotlinx.serialization.json.JsonArray -> content.joinToString(separator = "") { item ->
                item.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            else -> ""
        }
    }

    private fun chunkForDisplay(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val chunks = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val step = when {
                text[index].isWhitespace() -> 1
                text[index].code > 127 -> 2
                else -> 6
            }
            val next = (index + step).coerceAtMost(text.length)
            chunks += text.substring(index, next)
            index = next
        }
        return chunks
    }
}
