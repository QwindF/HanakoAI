package `fun`.kirari.hanako.network

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

internal fun openAiMessage(role: String, text: String): JsonObject = buildJsonObject {
    put("role", role)
    put("content", text)
}

internal fun extractOpenAiChatDelta(root: JsonObject): String {
    val choices = root["choices"]?.jsonArray ?: return ""
    val choice = choices.firstOrNull()?.jsonObject ?: return ""
    val delta = choice["delta"]?.jsonObject ?: return ""
    return extractOpenAiContent(delta["content"])
}

internal fun extractOpenAiContent(content: JsonElement?): String {
    return when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.joinToString(separator = "") { item ->
            item.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }

        is JsonObject -> content["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        else -> ""
    }
}

internal fun Bitmap.toBase64Jpeg(quality: Int = 92): String {
    val output = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, output)
    return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}
