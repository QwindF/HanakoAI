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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
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
import `fun`.kirari.hanako.BuildConfig
import `fun`.kirari.hanako.data.AutomationSettings
import `fun`.kirari.hanako.data.BubbleAppearanceSettings
import `fun`.kirari.hanako.data.KirariSettings
import `fun`.kirari.hanako.data.ScreenCaptureMethod
import `fun`.kirari.hanako.data.SearchProviderKind
import `fun`.kirari.hanako.data.WebSearchSettings
import `fun`.kirari.hanako.data.defaultBaseUrl

@Composable
fun MoreSettingsScreen(
    automationSettings: AutomationSettings,
    selectedMethod: ScreenCaptureMethod,
    trustAllHttpsCertificates: Boolean,
    kirariSettings: KirariSettings,
    webSearchSettings: WebSearchSettings,
    hasKirariClientId: Boolean,
    onToggleCompletionNotification: (Boolean) -> Unit,
    onToggleStaticMode: (Boolean) -> Unit,
    onNavigateStaticVibrationSettings: () -> Unit,
    onUpdateAutomationSettings: (AutomationSettings) -> Unit,
    onUpdateTimeoutSeconds: (Int) -> Unit,
    onSelectMethod: (ScreenCaptureMethod) -> Unit,
    onToggleTrustAllHttpsCertificates: (Boolean) -> Unit,
    onUpdateKirariServerUrl: (String) -> Unit,
    onLoginKirari: () -> Unit,
    onLogoutKirari: () -> Unit,
    onUpdateWebSearchSettings: ((WebSearchSettings) -> WebSearchSettings) -> Unit
) {
    var timeoutInput by remember(automationSettings.autoModeTimeoutSeconds) {
        mutableStateOf(automationSettings.autoModeTimeoutSeconds.toString())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            MoreSettingCard(
                icon = Icons.Default.Adjust,
                title = "悬浮球外观",
                subtitle = "调整悬浮球的尺寸与透明度。",
                trailing = {
                    BubbleAppearanceResetButton(
                        onReset = {
                            onUpdateAutomationSettings(
                                automationSettings.copy(bubbleAppearance = BubbleAppearanceSettings())
                            )
                        }
                    )
                }
            ) {
                BubbleAppearanceSettingsCard(
                    settings = automationSettings.bubbleAppearance,
                    onChange = { bubbleAppearance ->
                        onUpdateAutomationSettings(automationSettings.copy(bubbleAppearance = bubbleAppearance))
                    }
                )
            }
        }
        item {
            MoreSettingCard(
                icon = Icons.Default.SmartToy,
                title = "自动模式",
                subtitle = "主页长按启动进入自动模式。"
            ) {
                AutoModeSettingsCard(
                    automationSettings = automationSettings,
                    timeoutInput = timeoutInput,
                    onTimeoutInputChange = { timeoutInput = it },
                    onToggleCompletionNotification = onToggleCompletionNotification,
                    onToggleStaticMode = onToggleStaticMode,
                    onNavigateStaticVibrationSettings = onNavigateStaticVibrationSettings,
                    onUpdateTimeoutSeconds = onUpdateTimeoutSeconds
                )
            }
        }
        item {
            MoreSettingCard(
                icon = Icons.Default.Security,
                title = "网络兼容",
                subtitle = "HTTP 与自签 HTTPS 测试接口。"
            ) {
                TrustAllHttpsSwitch(
                    trustAllHttpsCertificates = trustAllHttpsCertificates,
                    onToggleTrustAllHttpsCertificates = onToggleTrustAllHttpsCertificates
                )
            }
        }
        item {
            MoreSettingCard(
                icon = Icons.Default.Search,
                title = "联网搜索",
                subtitle = "OCR 后由模型按工具调用决定是否搜索。"
            ) {
                WebSearchSettingsCard(
                    webSearchSettings = webSearchSettings,
                    onUpdateWebSearchSettings = onUpdateWebSearchSettings
                )
            }
        }
        item {
            MoreSettingCard(
                icon = Icons.Default.PhoneAndroid,
                title = "屏幕录制方式",
                subtitle = "管理当前激活的截图实现。"
            ) {
                ScreenCaptureMethodList(
                    selectedMethod = selectedMethod,
                    onSelectMethod = onSelectMethod
                )
            }
        }
        item {
            if (BuildConfig.SHOW_KIRARI_ENTRY) {
                MoreSettingCard(
                    icon = Icons.Default.Cloud,
                    title = "The Kirari Network",
                    subtitle = "标准 OIDC 登录与 Kirari LLM 网关。"
                ) {
                    KirariSettingsCard(
                        kirariSettings = kirariSettings,
                        hasKirariClientId = hasKirariClientId,
                        onServerUrlCommit = onUpdateKirariServerUrl,
                        onLogin = onLoginKirari,
                        onLogout = onLogoutKirari
                    )
                }
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
            subtitle = "OCR 完成后允许模型通过搜索工具获取最新信息。",
            checked = webSearchSettings.enabled,
            onCheckedChange = { enabled ->
                onUpdateWebSearchSettings { it.copy(enabled = enabled) }
            }
        )
        Text("仅在 OCR -> 文本模型路由下可用。")
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
