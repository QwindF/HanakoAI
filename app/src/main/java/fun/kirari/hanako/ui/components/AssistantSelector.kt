package `fun`.kirari.hanako.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.data.AssistantPreset

@Composable
fun AssistantSelector(
    assistant: AssistantPreset,
    onChange: (AssistantPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DraftOutlinedTextField(
                fieldKey = "${assistant.id}:name",
                value = assistant.name,
                onCommit = { onChange(assistant.copy(name = it)) },
                label = "助手名称"
            )
            DraftOutlinedTextField(
                fieldKey = "${assistant.id}:ocrPrompt",
                value = assistant.ocrPrompt,
                onCommit = { onChange(assistant.copy(ocrPrompt = it)) },
                label = "OCR 模型提示词",
                minLines = 4
            )
            DraftOutlinedTextField(
                fieldKey = "${assistant.id}:textPrompt",
                value = assistant.textPrompt,
                onCommit = { onChange(assistant.copy(textPrompt = it)) },
                label = "LLM 提示词",
                minLines = 5
            )
            DraftOutlinedTextField(
                fieldKey = "${assistant.id}:visionPrompt",
                value = assistant.visionPrompt,
                onCommit = { onChange(assistant.copy(visionPrompt = it)) },
                label = "多模态模型提示词",
                minLines = 5
            )
        }
    }
}
