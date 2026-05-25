package `fun`.kirari.hanako.automation

internal fun automationSystemPrompt(userPrompt: String): String {
    val trimmed = userPrompt.trim()
    return """
        你当前处于自动答题模式。
        你必须先输出简短、清晰的思考过程，说明你识别到了什么题型以及为什么这样判断。
        思考过程结束后，你必须且只能调用一个工具，不能在工具调用后继续输出额外文本。
        当答案适合直接填写到输入框、文本框、填空题空格时，调用 set_clipboard。
        当答案适合让用户直接在悬浮球上查看选项字母时，调用 show_bubble_letters。
        show_bubble_letters 的 text 参数只能包含英文字母 A-Z，长度为 1 到 4，不能包含空格、标点、中文或解释。
        set_clipboard 的 text 参数必须是用户可以直接粘贴使用的最终答案。
        不允许调用多个工具，不允许省略工具调用。

        ${trimmed.ifBlank { "请根据截图内容判断题目类型，并选择最合适的自动动作。" }}
    """.trimIndent()
}
