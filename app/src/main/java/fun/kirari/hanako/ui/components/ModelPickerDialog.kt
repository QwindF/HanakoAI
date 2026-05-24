package `fun`.kirari.hanako.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.network.ProviderModelsApi
import `fun`.kirari.hanako.network.RemoteModelOption

@Composable
fun ModelPickerDialog(
    provider: ModelProviderConfig,
    title: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onCustomModelRequest: (String) -> Unit,
    api: ProviderModelsApi = ProviderModelsApi()
) {
    val models by produceState(initialValue = emptyList<RemoteModelOption>(), provider.id) {
        value = runCatching { api.listModels(provider) }.getOrElse { emptyList() }
    }
    var query by remember { mutableStateOf("") }
    val filteredModels = remember(models, query) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            models
        } else {
            models.filter { model ->
                model.displayName.contains(normalizedQuery, ignoreCase = true) ||
                    model.id.contains(normalizedQuery, ignoreCase = true)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 560.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = title)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索模型") },
                    placeholder = { Text("按名称或 ID 搜索") },
                    singleLine = true
                )

                if (models.isEmpty()) {
                    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator()
                    }
                } else if (filteredModels.isEmpty()) {
                    Text("没有匹配的模型")
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredModels) { model ->
                            OutlinedButton(
                                onClick = { onPick(model.id) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(model.displayName)
                                    if (model.displayName != model.id) {
                                        Text(model.id)
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { onCustomModelRequest(title) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("使用自定义模型")
                }

                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("关闭")
                }
            }
        }
    }
}
