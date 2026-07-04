package `fun`.kirari.hanako.automation

import `fun`.kirari.hanako.data.AutomationActionRecord
import `fun`.kirari.hanako.data.AutomationActionType

data class AutomationResult(
    val thought: String,
    val action: AutomationActionRecord?
)

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
