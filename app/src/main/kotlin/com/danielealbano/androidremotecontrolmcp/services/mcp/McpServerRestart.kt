package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first

private const val TAG = "MCP:ServerRestart"

/** Re-issue an ACTION_START to the MCP foreground service. */
internal fun restartMcpServer(context: Context) {
    context.startForegroundService(
        Intent(context, McpServerService::class.java).apply { action = McpServerService.ACTION_START },
    )
}

/** Task-removal restart: attempt only when the server is running; swallow the FGS-not-allowed case. */
internal fun restartMcpServerIfForeground(
    context: Context,
    isServerRunning: Boolean,
) {
    if (!isServerRunning) return
    try {
        restartMcpServer(context)
    } catch (e: ForegroundServiceStartNotAllowedException) {
        Log.w(TAG, "Cannot restart on task removal (app not battery-exempt)", e)
    }
}

/** Package-replaced restart: attempt only when the persisted intent flag is true. */
internal suspend fun restartMcpServerIfRunning(
    context: Context,
    settingsRepository: SettingsRepository,
) {
    if (settingsRepository.serverRunning.first()) {
        restartMcpServer(context)
    }
}
