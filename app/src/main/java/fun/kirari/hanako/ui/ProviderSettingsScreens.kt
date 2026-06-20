package `fun`.kirari.hanako.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.data.AppSettings
import `fun`.kirari.hanako.data.KIRARI_PROVIDER_ID
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.availableProviders
import `fun`.kirari.hanako.data.displayName
import `fun`.kirari.hanako.ui.components.ProviderEditor
import `fun`.kirari.hanako.ui.components.SectionCard

@Composable
fun ProviderSettingsScreen(
    settings: AppSettings,
    onAddProvider: () -> Unit,
    onDeleteProvider: (String) -> Unit,
    onOpenProvider: (String) -> Unit
) {
    var deleteTargetId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "模型提供方",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = onAddProvider) {
                    Text("新增")
                }
            }
        }
        items(settings.availableProviders(), key = { it.id }) { provider ->
            ProviderListItem(
                provider = provider,
                onOpenProvider = onOpenProvider,
                onRequestDelete = { deleteTargetId = it }
            )
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    val deleteTarget = settings.availableProviders().firstOrNull { it.id == deleteTargetId }
    if (deleteTarget != null) {
        DeleteConfirmDialog(
            title = "删除提供方",
            message = "确认删除 ${deleteTarget.name}？",
            onDismiss = { deleteTargetId = null },
            onConfirm = {
                onDeleteProvider(deleteTarget.id)
                deleteTargetId = null
            }
        )
    }
}

@Composable
fun ProviderDetailScreen(
    provider: ModelProviderConfig,
    connectionTestState: ConnectionTestState,
    onUpdateProvider: (ModelProviderConfig) -> Unit,
    onViewModels: () -> Unit,
    onTestConnection: (ModelProviderConfig) -> Unit,
    onClearConnectionTest: () -> Unit
) {
    val context = LocalContext.current
    DisposableEffect(provider.id) {
        onDispose { onClearConnectionTest() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionCard(title = "") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProviderEditor(
                        provider = provider,
                        readOnly = provider.id == KIRARI_PROVIDER_ID,
                        onChange = onUpdateProvider,
                        onImportResult = { message ->
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    )
                    if (provider.id == KIRARI_PROVIDER_ID) {
                        Text(
                            text = "The Kirari Network 为固定提供方。服务器地址和登录状态请在“更多”中配置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = onViewModels,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("查看可用模型")
                    }
                    ConnectionTestRow(
                        provider = provider,
                        connectionTestState = connectionTestState,
                        onTestConnection = onTestConnection
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun ProviderListItem(
    provider: ModelProviderConfig,
    onOpenProvider: (String) -> Unit,
    onRequestDelete: (String) -> Unit
) {
    Surface(
        modifier = Modifier.combinedClickable(
            onClick = { onOpenProvider(provider.id) },
            onLongClick = {
                if (provider.id != KIRARI_PROVIDER_ID) {
                    onRequestDelete(provider.id)
                }
            }
        ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    provider.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    provider.kind.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun ConnectionTestRow(
    provider: ModelProviderConfig,
    connectionTestState: ConnectionTestState,
    onTestConnection: (ModelProviderConfig) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { onTestConnection(provider) },
            enabled = connectionTestState.status != ConnectionTestStatus.TESTING
        ) {
            if (connectionTestState.status == ConnectionTestStatus.TESTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("测试中...")
            } else {
                Text("测试连接")
            }
        }
        ConnectionTestInlineResult(
            state = connectionTestState,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

@Composable
private fun ConnectionTestInlineResult(
    state: ConnectionTestState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state.status == ConnectionTestStatus.SUCCESS || state.status == ConnectionTestStatus.FAILED,
        modifier = modifier,
        enter = expandHorizontally(expandFrom = Alignment.Start) +
            slideInHorizontally { -it / 3 } +
            fadeIn(),
        exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut()
    ) {
        val success = state.status == ConnectionTestStatus.SUCCESS
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (success) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    if (success) "连接成功 ${state.latencyMs}ms" else "连接失败 ${state.errorMessage}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (success) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                )
            }
        }
    }
}
