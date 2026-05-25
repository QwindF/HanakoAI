package `fun`.kirari.hanako.automation

import `fun`.kirari.hanako.data.AutomationActionRecord
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun validateAutomationAction(name: String, text: String): AutomationActionRecord {
    val normalized = text.trim()
    require(normalized.isNotBlank()) { "自动模式工具参数不能为空" }
    return when (name) {
        "set_clipboard" -> clipboardAction(normalized)
        "show_bubble_letters" -> {
            require(Regex("^[A-Z]{1,4}$").matches(normalized)) {
                "悬浮球字母必须是 1 到 4 个大写英文字母"
            }
            bubbleLettersAction(normalized)
        }

        else -> error("未知自动模式工具：$name")
    }
}

internal fun extractJsonTextField(input: JsonObject): String {
    return input["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
}

internal fun extractTextDelta(element: JsonElement?): String {
    return when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonObject -> element["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        else -> ""
    }
}
