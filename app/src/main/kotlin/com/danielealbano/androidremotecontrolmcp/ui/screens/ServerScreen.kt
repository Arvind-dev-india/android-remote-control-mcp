@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.ApprovalActivity
import com.danielealbano.androidremotecontrolmcp.ui.components.BatteryOptimizationCard
import com.danielealbano.androidremotecontrolmcp.ui.components.ConnectionInfoCard
import com.danielealbano.androidremotecontrolmcp.ui.components.ServerLogsSection
import com.danielealbano.androidremotecontrolmcp.ui.components.ServerStatusCard
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ChannelViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.LogsViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import com.danielealbano.androidremotecontrolmcp.utils.NetworkUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    onNavigateToPermissions: () -> Unit,
    onShowAllLogs: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
    channelViewModel: ChannelViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val logsViewModel: LogsViewModel = hiltViewModel()

    val serverConfig by viewModel.serverConfig.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val recentServerLogs by logsViewModel.recentServerLogs.collectAsStateWithLifecycle()
    val tunnelStatus by viewModel.tunnelStatus.collectAsStateWithLifecycle()

    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsStateWithLifecycle()
    val isBatteryOptimizationIgnored by viewModel.isBatteryOptimizationIgnored.collectAsStateWithLifecycle()
    val pendingApprovalCount by viewModel.pendingApprovalCount.collectAsStateWithLifecycle()

    val channelConfig by channelViewModel.eventChannelConfig.collectAsStateWithLifecycle()
    val channelStatus by channelViewModel.channelConnectionStatus.collectAsStateWithLifecycle()

    val deviceIp = remember(context) { NetworkUtils.getDeviceIpAddress(context) ?: "N/A" }
    val copiedToClipboardMessage = stringResource(R.string.copied_to_clipboard)
    var showChannelNotConfiguredDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_server)) },
            windowInsets = WindowInsets(0),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            if (!isAccessibilityEnabled) {
                PermissionWarningCard(onClick = onNavigateToPermissions)
                Spacer(Modifier.height(16.dp))
            }

            if (!serverConfig.oauthEnabled && !serverConfig.bearerTokenEnabled) {
                NoAuthWarningCard()
                Spacer(Modifier.height(16.dp))
            }

            if (!isBatteryOptimizationIgnored) {
                BatteryOptimizationCard(
                    onRequestExemption = { viewModel.requestBatteryOptimizationExemption() },
                )
                Spacer(Modifier.height(16.dp))
            }

            if (pendingApprovalCount > 0) {
                PendingApprovalsCard(
                    count = pendingApprovalCount,
                    onClick = { context.startActivity(Intent(context, ApprovalActivity::class.java)) },
                )
                Spacer(Modifier.height(16.dp))
            }

            ServerStatusCard(
                serverStatus = serverStatus,
                channelStatus = channelStatus,
                channelEnabled = channelConfig.enabled,
                onMcpStartClick = { viewModel.startServer(context) },
                onMcpStopClick = { viewModel.stopServer(context) },
                onChannelStartClick = {
                    if (channelConfig.endpointUrl.isBlank()) {
                        showChannelNotConfiguredDialog = true
                    } else {
                        channelViewModel.startChannel()
                    }
                },
                onChannelStopClick = { channelViewModel.stopChannel() },
                startEnabled = isAccessibilityEnabled,
            )

            Spacer(Modifier.height(16.dp))

            ConnectionInfoCard(
                bindingAddress = serverConfig.bindingAddress,
                ipAddress = deviceIp,
                port = serverConfig.port,
                httpsEnabled = serverConfig.httpsEnabled,
                bearerToken = serverConfig.bearerToken,
                tunnelEnabled = serverConfig.tunnelEnabled,
                serverStatus = serverStatus,
                tunnelStatus = tunnelStatus,
                onCopyAll = { text ->
                    clipboardManager.setText(AnnotatedString(text))
                    Toast.makeText(context, copiedToClipboardMessage, Toast.LENGTH_SHORT).show()
                },
                onShare = { text ->
                    val intent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                    context.startActivity(Intent.createChooser(intent, null))
                },
            )

            Spacer(Modifier.height(16.dp))

            ServerLogsSection(
                logs = recentServerLogs,
                onShowMore = onShowAllLogs,
            )
        }
    }

    if (showChannelNotConfiguredDialog) {
        AlertDialog(
            onDismissRequest = { showChannelNotConfiguredDialog = false },
            title = { Text(stringResource(R.string.channel_not_configured_dialog_title)) },
            text = { Text(stringResource(R.string.channel_not_configured_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showChannelNotConfiguredDialog = false }) {
                    Text(stringResource(R.string.channel_not_configured_dialog_ok))
                }
            },
        )
    }
}

@Composable
private fun NoAuthWarningCard() {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.access_no_auth_warning_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun PendingApprovalsCard(
    count: Int,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.server_pending_approvals_message, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun PermissionWarningCard(onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.permission_warning_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
