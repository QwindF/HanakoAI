package `fun`.kirari.hanako.automation

import `fun`.kirari.hanako.data.AutomationActionRecord
import `fun`.kirari.hanako.data.AutomationActionType

data class AutomationResult(
    val thought: String,
    val action: AutomationActionRecord
)

internal enum class BubbleDisplayState {
    IDLE,
    RUNNING,
    COPIED,
    SHOWING_LETTERS,
    SHOWING_LETTERS_PENDING_RESET
}

internal fun bubbleLettersAction(text: String): AutomationActionRecord =
    AutomationActionRecord(
        type = AutomationActionType.SHOW_BUBBLE_LETTERS,
        text = text
    )

internal fun clipboardAction(text: String): AutomationActionRecord =
    AutomationActionRecord(
        type = AutomationActionType.SET_CLIPBOARD,
        text = text
    )
