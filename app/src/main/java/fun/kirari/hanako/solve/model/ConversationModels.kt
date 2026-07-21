package `fun`.kirari.hanako.solve.model

internal sealed interface ConversationIntent {
    data class NewTurn(val prompt: String) : ConversationIntent
    data object RegenerateLatest : ConversationIntent
}
