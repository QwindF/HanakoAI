package `fun`.kirari.hanako.automation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun automationToolsForResponses(): kotlinx.serialization.json.JsonArray = buildJsonArray {
    add(responseToolDefinition("set_clipboard", "将最终答案写入剪贴板", "最终可直接粘贴的答案"))
    add(responseToolDefinition("show_bubble_letters", "在悬浮球上展示选项字母", "1到4个大写英文字母"))
}

internal fun automationToolsForChatCompletions(): kotlinx.serialization.json.JsonArray = buildJsonArray {
    add(chatCompletionsToolDefinition("set_clipboard", "将最终答案写入剪贴板", "最终可直接粘贴的答案"))
    add(chatCompletionsToolDefinition("show_bubble_letters", "在悬浮球上展示选项字母", "1到4个大写英文字母"))
}

internal fun automationToolsForAnthropic(): kotlinx.serialization.json.JsonArray = buildJsonArray {
    add(anthropicTool("set_clipboard", "将最终答案写入剪贴板", "最终可直接粘贴的答案"))
    add(anthropicTool("show_bubble_letters", "在悬浮球上展示选项字母", "1到4个大写英文字母", lettersOnly = true))
}

internal fun automationToolsForGoogle(): JsonObject = buildJsonObject {
    put(
        "functionDeclarations",
        buildJsonArray {
            add(googleTool("set_clipboard", "将最终答案写入剪贴板", "最终可直接粘贴的答案"))
            add(googleTool("show_bubble_letters", "在悬浮球上展示选项字母", "1到4个大写英文字母", lettersOnly = true))
        }
    )
}

private fun responseToolDefinition(name: String, description: String, fieldDescription: String): JsonObject = buildJsonObject {
    put("type", "function")
    put(
        "name",
        name
    )
    put("description", description)
    put(
        "parameters",
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "text",
                        buildJsonObject {
                            put("type", "string")
                            put("description", fieldDescription)
                        }
                    )
                }
            )
            put("required", buildJsonArray { add(JsonPrimitive("text")) })
            put("additionalProperties", false)
        }
    )
}

private fun chatCompletionsToolDefinition(name: String, description: String, fieldDescription: String): JsonObject = buildJsonObject {
    put("type", "function")
    put(
        "function",
        buildJsonObject {
            put("name", name)
            put("description", description)
            put(
                "parameters",
                buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "text",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", fieldDescription)
                                }
                            )
                        }
                    )
                    put("required", buildJsonArray { add(JsonPrimitive("text")) })
                    put("additionalProperties", false)
                }
            )
        }
    )
}

private fun anthropicTool(
    name: String,
    description: String,
    fieldDescription: String,
    lettersOnly: Boolean = false
): JsonObject = buildJsonObject {
    put("name", name)
    put("description", description)
    put(
        "input_schema",
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "text",
                        buildJsonObject {
                            put("type", "string")
                            put("description", fieldDescription)
                            if (lettersOnly) {
                                put("pattern", "^[A-Z]{1,4}$")
                            }
                        }
                    )
                }
            )
            put("required", buildJsonArray { add(JsonPrimitive("text")) })
        }
    )
}

private fun googleTool(
    name: String,
    description: String,
    fieldDescription: String,
    lettersOnly: Boolean = false
): JsonObject = buildJsonObject {
    put("name", name)
    put("description", description)
    put(
        "parameters",
        buildJsonObject {
            put("type", "OBJECT")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "text",
                        buildJsonObject {
                            put("type", "STRING")
                            put("description", fieldDescription)
                            if (lettersOnly) {
                                put("pattern", "^[A-Z]{1,4}$")
                            }
                        }
                    )
                }
            )
            put("required", buildJsonArray { add(JsonPrimitive("text")) })
        }
    )
}
