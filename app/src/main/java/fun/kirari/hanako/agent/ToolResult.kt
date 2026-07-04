package `fun`.kirari.hanako.agent

import `fun`.kirari.hanako.data.ProcessingEvent
import kotlinx.serialization.json.JsonObject

internal data class ToolResult(
    val text: String,
    val events: List<ProcessingEvent> = emptyList(),
    val payload: JsonObject? = null,
    val hasSideEffect: Boolean = false,
    val summary: String? = null
)
