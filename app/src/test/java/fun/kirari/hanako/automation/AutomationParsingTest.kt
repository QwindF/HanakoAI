package `fun`.kirari.hanako.automation

import `fun`.kirari.hanako.data.AutomationActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AutomationParsingTest {

    @Test
    fun validateAutomationAction_trimsClipboardText() {
        val action = validateAutomationAction("set_clipboard", "  answer  ")

        assertEquals(AutomationActionType.SET_CLIPBOARD, action.type)
        assertEquals("answer", action.text)
    }

    @Test
    fun validateAutomationAction_acceptsLettersAndChineseSymbols() {
        assertEquals(
            "ABCD",
            validateAutomationAction("show_bubble_letters", "ABCD").text
        )
        assertEquals(
            "对",
            validateAutomationAction("show_bubble_letters", "对").text
        )
        assertEquals(
            "√",
            validateAutomationAction("show_bubble_letters", "√").text
        )
    }

    @Test
    fun validateAutomationAction_rejectsBlankText() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateAutomationAction("set_clipboard", "   ")
        }

        assertEquals("自动模式工具参数不能为空", error.message)
    }

    @Test
    fun validateAutomationAction_rejectsInvalidBubbleLetters() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateAutomationAction("show_bubble_letters", "A-1")
        }

        assertEquals(
            "悬浮球字母必须是 1-8 个英文字母（大小写均可），或'对'、'错'、'√'、'×'",
            error.message
        )
    }

    @Test
    fun validateAutomationAction_rejectsUnknownActionName() {
        val error = assertThrows(IllegalStateException::class.java) {
            validateAutomationAction("unknown", "text")
        }

        assertEquals("未知自动模式工具：unknown", error.message)
    }
}
