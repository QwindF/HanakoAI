package `fun`.kirari.hanako.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.data.SearchProviderKind
import `fun`.kirari.hanako.data.WebSearchSettings
import `fun`.kirari.hanako.data.defaultBaseUrl
import `fun`.kirari.hanako.ui.components.SectionCard

@Composable
fun WebSearchSettingsScreen(
    webSearchSettings: WebSearchSettings,
    onUpdateWebSearchSettings: ((WebSearchSettings) -> WebSearchSettings) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionCard(title = "联网搜索") {
                WebSearchSettingsCard(
                    webSearchSettings = webSearchSettings,
                    onUpdateWebSearchSettings = onUpdateWebSearchSettings
                )
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun WebSearchSettingsCard(
    webSearchSettings: WebSearchSettings,
    onUpdateWebSearchSettings: ((WebSearchSettings) -> WebSearchSettings) -> Unit
) {
    var showApiKey by remember { mutableStateOf(false) }
    var searchProviderExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SwitchSettingRow(
            title = "启用联网搜索",
            subtitle = "开启后允许当前任务模型在需要时通过 web_search 工具获取最新信息。",
            checked = webSearchSettings.enabled,
            onCheckedChange = { enabled ->
                onUpdateWebSearchSettings { it.copy(enabled = enabled) }
            }
        )
        Box {
            OutlinedButton(
                onClick = { searchProviderExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("搜索引擎：${webSearchSettings.provider.kind.displayName}")
            }
            DropdownMenu(
                expanded = searchProviderExpanded,
                onDismissRequest = { searchProviderExpanded = false }
            ) {
                SearchProviderKind.entries.forEach { kind ->
                    DropdownMenuItem(
                        text = { Text(kind.displayName) },
                        onClick = {
                            onUpdateWebSearchSettings {
                                it.copy(
                                    provider = it.provider.copy(
                                        kind = kind,
                                        baseUrl = kind.defaultBaseUrl
                                    )
                                )
                            }
                            searchProviderExpanded = false
                        }
                    )
                }
            }
        }
        OutlinedTextField(
            value = webSearchSettings.provider.baseUrl,
            onValueChange = { url ->
                onUpdateWebSearchSettings {
                    it.copy(provider = it.provider.copy(baseUrl = url))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("API URL") }
        )
        OutlinedTextField(
            value = webSearchSettings.provider.apiKey,
            onValueChange = { key ->
                onUpdateWebSearchSettings {
                    it.copy(provider = it.provider.copy(apiKey = key))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("API Key") },
            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showApiKey = !showApiKey }) {
                    Text(if (showApiKey) "隐藏" else "显示")
                }
            }
        )
        SwitchSettingRow(
            title = "自动模式也使用联网搜索",
            subtitle = "自动答题模式下也允许调用搜索工具。",
            checked = webSearchSettings.automationAlsoSearch,
            onCheckedChange = { enabled ->
                onUpdateWebSearchSettings { it.copy(automationAlsoSearch = enabled) }
            }
        )
    }
}
