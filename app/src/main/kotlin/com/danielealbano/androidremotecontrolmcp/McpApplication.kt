package com.danielealbano.androidremotecontrolmcp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.apps.AppIconCache
import com.danielealbano.androidremotecontrolmcp.startup.runFlavorStartupMigrations
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import javax.inject.Inject

@HiltAndroidApp
class McpApplication : Application() {
    @Inject
    lateinit var appIconCache: AppIconCache

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        // Flavor-specific one-time migrations (gms: geofence config → dedicated key), run eagerly
        // and synchronously BEFORE any Activity/Service can issue a settings write. No-op in foss.
        runFlavorStartupMigrations(this)
        createNotificationChannels()
        configureOsmdroid()
        appIconCache.preload()
        // Apply the one-time auth-model migration eagerly so the UI Flow reflects the migrated model
        // promptly at startup (idempotent; the server start path also guarantees it via getServerConfig()).
        CoroutineScope(Dispatchers.IO).launch { settingsRepository.ensureAuthModelMigrated() }
        Log.i(TAG, "Application initialized, notification channels created")
    }

    private fun configureOsmdroid() {
        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = packageName
        osmConfig.osmdroidBasePath = filesDir
        osmConfig.osmdroidTileCache = cacheDir.resolve("osmdroid")
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val mcpServerChannel =
            NotificationChannel(
                MCP_SERVER_CHANNEL_ID,
                getString(R.string.notification_channel_mcp_server_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Notification for the running MCP server"
            }

        val oauthApprovalChannel =
            NotificationChannel(
                OAUTH_APPROVAL_CHANNEL_ID,
                getString(R.string.notification_channel_oauth_approval_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Heads-up notification for pending OAuth connection approvals"
            }

        notificationManager.createNotificationChannel(mcpServerChannel)
        notificationManager.createNotificationChannel(oauthApprovalChannel)
    }

    companion object {
        private const val TAG = "MCP:Application"
        const val MCP_SERVER_CHANNEL_ID = "mcp_server_channel"
        const val OAUTH_APPROVAL_CHANNEL_ID = "oauth_approval_channel"
    }
}
