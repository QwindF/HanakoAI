package `fun`.kirari.hanako.network

import android.graphics.Bitmap
import `fun`.kirari.hanako.data.AssistantPreset
import `fun`.kirari.hanako.data.ModelProviderConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient

class AiGateway(
    private val client: OkHttpClient = OkHttpClient(),
    internal val json: Json = Json { ignoreUnknownKeys = true }
) {
    internal val sseClient = SseStreamClient(client)
    internal val JSON = "application/json; charset=utf-8".toMediaType()

    private fun assistantPromptWithCopyMarker(systemPrompt: String): String {
        val trimmed = systemPrompt.trim()
        if (trimmed.isBlank()) return trimmed
        return """
            你可以在回答中插入如下格式的可复制文本块：
            [copy:内容]
            其中“内容”会显示为一个小标签，点击复制图标后会写入同样的文本到剪贴板。
            对于问题的答案或用户需要填写到某个表单中的内容，你必须给出一键复制的标签。

            $trimmed
        """.trimIndent()
    }

    private fun assistantPromptForAutoCopy(systemPrompt: String): String {
        val trimmed = systemPrompt.trim()
        return """
            你将处理整张屏幕截图。
            先输出简短的文本分析过程，帮助用户理解你的判断；不要省略这部分。
            分析过程只能使用普通文本，不能出现 [copy:...] 标签示例或变体。
            最后一行必须输出且只能输出一个格式严格为 [copy:标签内容] 的标签。
            标签内容应直接可供用户复制使用，例如填入对应的表单或作业答案框。
            除最后一行外，前文不要再出现任何方括号复制标签。

            ${trimmed.ifBlank { "请根据截图内容生成最合适的一段可复制文本。" }}
        """.trimIndent()
    }

    suspend fun streamOcrThenChat(
        ocrProvider: ModelProviderConfig,
        ocrModel: String,
        textProvider: ModelProviderConfig,
        textModel: String,
        assistant: AssistantPreset,
        bitmap: Bitmap,
        onOcrDelta: (String) -> Unit,
        onAnswerDelta: (String) -> Unit
    ): Pair<String, String> {
        val ocrText = streamVision(
            provider = ocrProvider,
            model = ocrModel,
            systemPrompt = assistant.ocrPrompt,
            userPrompt = "请执行 OCR。",
            imageBase64 = bitmap.toBase64Jpeg(),
            onDelta = onOcrDelta
        )
        val answer = streamText(
            provider = textProvider,
            model = textModel,
            systemPrompt = assistantPromptWithCopyMarker(assistant.textPrompt),
            userPrompt = "以下是 OCR 结果，请完成任务：\n$ocrText",
            onDelta = onAnswerDelta
        )
        return ocrText to answer
    }

    suspend fun streamOcrThenAutoCopy(
        ocrProvider: ModelProviderConfig,
        ocrModel: String,
        textProvider: ModelProviderConfig,
        textModel: String,
        assistant: AssistantPreset,
        bitmap: Bitmap,
        onOcrDelta: (String) -> Unit,
        onAnswerDelta: (String) -> Unit
    ): Pair<String, String> {
        val ocrText = streamVision(
            provider = ocrProvider,
            model = ocrModel,
            systemPrompt = assistant.ocrPrompt,
            userPrompt = "请执行 OCR。",
            imageBase64 = bitmap.toBase64Jpeg(),
            onDelta = onOcrDelta
        )
        val answer = streamText(
            provider = textProvider,
            model = textModel,
            systemPrompt = assistantPromptForAutoCopy(assistant.textPrompt),
            userPrompt = "以下是 OCR 结果。请先输出简短分析，最后一行再生成唯一的 [copy:标签内容]：\n$ocrText",
            onDelta = onAnswerDelta
        )
        return ocrText to answer
    }

    suspend fun streamVisionDirect(
        provider: ModelProviderConfig,
        model: String,
        assistant: AssistantPreset,
        bitmap: Bitmap,
        onAnswerDelta: (String) -> Unit
    ): String {
        return streamVision(
            provider = provider,
            model = model,
            systemPrompt = assistantPromptWithCopyMarker(assistant.visionPrompt),
            userPrompt = "请直接基于图片内容完成任务。",
            imageBase64 = bitmap.toBase64Jpeg(),
            onDelta = onAnswerDelta
        )
    }

    suspend fun streamAutoCopy(
        provider: ModelProviderConfig,
        model: String,
        assistant: AssistantPreset,
        bitmap: Bitmap,
        onAnswerDelta: (String) -> Unit
    ): String {
        return streamVision(
            provider = provider,
            model = model,
            systemPrompt = assistantPromptForAutoCopy(assistant.visionPrompt),
            userPrompt = "请先根据整张屏幕截图输出简短分析，最后一行再生成唯一的 [copy:标签内容]。",
            imageBase64 = bitmap.toBase64Jpeg(),
            onDelta = onAnswerDelta
        )
    }
}
