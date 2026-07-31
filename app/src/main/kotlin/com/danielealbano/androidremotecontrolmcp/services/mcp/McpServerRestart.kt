package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException

private const val TAG = "MCP:ServerRestart"
private const val FLAG_WRITE_TIMEOUT_MS = 2_000L

/**
 * Durably persists the user's start/stop intent (`server_running`) on the calling thread before the
 * caller returns, so restart triggers can decide whether to bring the server back up. The bounded
 * main-thread block is acceptable for a single DataStore edit; a slow or failed write logs and
 * proceeds instead of crashing the frequently-invoked callback that issues it.
 */
internal fun persistServerRunning(
    settingsRepository: SettingsRepository,
    running: Boolean,
) {
    try {
        runBlocking { withTimeout(FLAG_WRITE_TIMEOUT_MS) { settingsRepository.updateServerRunning(running) } }
    } catch (e: TimeoutCancellationException) {
        Log.w(TAG, "Timed out persisting server_running=$running", e)
    } catch (e: IOException) {
        Log.e(TAG, "Failed to persist server_running=$running", e)
    }
}

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
