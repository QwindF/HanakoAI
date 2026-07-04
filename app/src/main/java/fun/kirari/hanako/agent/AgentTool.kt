package `fun`.kirari.hanako.agent

import `fun`.kirari.hanako.network.ToolDef
import kotlinx.serialization.json.JsonObject

internal interface AgentTool {
    val name: String
    val definition: ToolDef

    suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult
}
