package `fun`.kirari.hanako.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.data.AppSettings
import `fun`.kirari.hanako.data.LOCAL_OCR_PROVIDER_ID
import `fun`.kirari.hanako.data.ModelPurpose
import `fun`.kirari.hanako.data.displayName
import `fun`.kirari.hanako.data.resolveModelName
import `fun`.kirari.hanako.data.resolveModelProvider
import `fun`.kirari.hanako.ui.components.ModelButtonField
import `fun`.kirari.hanako.ui.components.SectionCard

@Composable
fun ModelSettingsScreen(
    settings: AppSettings,
    onPickModel: (ModelPurpose) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionCard(title = "模型设置") {
                ModelPurpose.entries.forEach { purpose ->
                    val provider = settings.resolveModelProvider(purpose)
                    val model = settings.resolveModelName(purpose)
                    ModelButtonField(
                        label = "${purpose.displayName} 模型",
                        value = buildString {
                            append(
                                when {
                                    purpose == ModelPurpose.OCR && settings.ocrModelSelection.providerId == LOCAL_OCR_PROVIDER_ID ->
                                        "本地ML Kit"
                                    provider != null -> provider.name
                                    else -> "未选择提供方"
                                }
                            )
                            if (model.isNotBlank()) {
                                append(" / ")
                                append(model)
                            }
                        },
                        onPick = { onPickModel(purpose) }
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}
