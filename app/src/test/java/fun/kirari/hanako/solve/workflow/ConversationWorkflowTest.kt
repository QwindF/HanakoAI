package `fun`.kirari.hanako.solve.workflow

import `fun`.kirari.hanako.core.data.WebSearchSettings
import `fun`.kirari.hanako.core.data.defaultAssistant
import `fun`.kirari.hanako.core.data.defaultProvider
import `fun`.kirari.hanako.core.network.UnifiedLLMClient
import `fun`.kirari.hanako.core.model.FollowUpTurn
import `fun`.kirari.hanako.core.model.ProcessingResult
import `fun`.kirari.hanako.core.model.ProcessingRoute
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWorkflowTest {

    private val workflow = ConversationWorkflow(
        unifiedClient = UnifiedLLMClient(),
        pipeline = ProcessingPipeline()
    )

    @Test
    fun prepareTurn_buildsOrderedOcrConversationAndOmitsInvalidAssistantMessages() = runTest {
        val existing = baseResult().copy(
            extractedText = "question text",
            answer = "initial answer",
            followUpTurns = listOf(
                FollowUpTurn(userText = "first", assistantText = "first answer", completed = true),
                FollowUpTurn(userText = "partial", assistantText = "unfinished", completed = false),
                FollowUpTurn(userText = "failed", assistantText = "ignored", completed = true, errorMessage = "error")
            )
        )

        val prepared = workflow.prepareTurn(
            existingResult = existing,
            models = models(),
            prompt = "current",
            retryIndex = null,
            turnId = "turn-current"
        )

        assertEquals(
            listOf("system", "user", "assistant", "user", "assistant", "user", "user", "user"),
            prepared.messages.map { it.role }
        )
        assertTrue(prepared.messages[1].text().contains("question text"))
        assertEquals("initial answer", prepared.messages[2].text())
        assertEquals(listOf("first", "partial", "failed", "current"), prepared.messages.drop(3).filter { it.role == "user" }.map { it.text() })
        assertEquals("turn-current", prepared.startedResult.followUpTurns.last().id)
    }

    @Test
    fun prepareTurn_retryKeepsOnlyTurnsBeforeSelectedIndex() = runTest {
        val existing = baseResult().copy(
            followUpTurns = listOf(
                FollowUpTurn(userText = "keep", assistantText = "kept answer", completed = true),
                FollowUpTurn(userText = "retry", errorMessage = "failed", completed = true),
                FollowUpTurn(userText = "drop", assistantText = "stale", completed = true)
            )
        )

        val prepared = workflow.prepareTurn(
            existingResult = existing,
            models = models(),
            prompt = "retry",
            retryIndex = 1,
            turnId = "replacement"
        )

        assertEquals(listOf("keep", "retry"), prepared.startedResult.followUpTurns.map { it.userText })
        assertEquals(listOf("system", "user", "assistant", "user", "assistant", "user"), prepared.messages.map { it.role })
        assertEquals("replacement", prepared.startedResult.followUpTurns.last().id)
        assertEquals("retry", prepared.messages.last().text())
    }

    private fun baseResult() = ProcessingResult(
        id = "history-1",
        assistantName = "assistant",
        route = ProcessingRoute.OCR_THEN_LLM
    )

    private fun models(): ProcessingPipeline.ResolvedModels {
        val provider = defaultProvider()
        return ProcessingPipeline.ResolvedModels(
            assistant = defaultAssistant(),
            ocrProvider = provider,
            ocrModel = "ocr",
            textProvider = provider,
            textModel = "text",
            visionProvider = provider,
            visionModel = "vision",
            firstDeltaTimeoutMillis = 1_000L,
            route = ProcessingRoute.OCR_THEN_LLM,
            usingLocalOcr = false,
            trustAllHttpsCertificates = false,
            webSearchSettings = WebSearchSettings()
        )
    }
}

private fun `fun`.kirari.llm.core.ChatMessage.text(): String {
    return requireNotNull(content).jsonArray
        .first { it.jsonObject["type"]?.jsonPrimitive?.content == "input_text" }
        .jsonObject.getValue("text").jsonPrimitive.content
}
