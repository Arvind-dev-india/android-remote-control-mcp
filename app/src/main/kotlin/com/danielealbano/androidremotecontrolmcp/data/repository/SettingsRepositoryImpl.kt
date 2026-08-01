package com.danielealbano.androidremotecontrolmcp.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinPermissions
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.CloudflareTunnelMode
import com.danielealbano.androidremotecontrolmcp.data.model.EventChannelConfig
import com.danielealbano.androidremotecontrolmcp.data.model.NotificationFilterMode
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URL
import java.util.UUID
import javax.inject.Inject

/**
 * [SettingsRepository] implementation backed by Preferences DataStore.
 *
 * This is the single access point for all persisted settings in the
 * application. No other class should access DataStore directly.
 *
 * @property dataStore The Preferences DataStore instance provided by Hilt.
 */
@Suppress("TooManyFunctions")
class SettingsRepositoryImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
    ) : SettingsRepository {
        override val serverConfig: Flow<ServerConfig> =
            dataStore.data.map { prefs ->
                mapPreferencesToServerConfig(prefs)
            }

        override suspend fun getServerConfig(): ServerConfig {
            ensureAuthModelMigrated()
            return mapPreferencesToServerConfig(dataStore.data.first())
        }

        override suspend fun ensureAuthModelMigrated() {
            dataStore.edit { prefs ->
                // One-time bearer-enabled migration (idempotent; guarded).
                if (prefs[BEARER_TOKEN_ENABLED_INITIALIZED_KEY] != true) {
                    val wasInitialized = prefs[BEARER_TOKEN_INITIALIZED_KEY] == true
                    val hadToken = !prefs[BEARER_TOKEN_KEY].isNullOrEmpty()
                    prefs[BEARER_TOKEN_ENABLED_KEY] = if (wasInitialized) hadToken else true
                    prefs[BEARER_TOKEN_ENABLED_INITIALIZED_KEY] = true
                }
                // Bearer-token auto-generation: only when bearer is enabled and empty.
                if (prefs[BEARER_TOKEN_INITIALIZED_KEY] != true) {
                    if (prefs[BEARER_TOKEN_ENABLED_KEY] == true && prefs[BEARER_TOKEN_KEY].isNullOrEmpty()) {
                        prefs[BEARER_TOKEN_KEY] = generateTokenString()
                    }
                    prefs[BEARER_TOKEN_INITIALIZED_KEY] = true
                }
            }
        }

        override suspend fun updateOauthEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[OAUTH_ENABLED_KEY] ?: true
                prefs[OAUTH_ENABLED_KEY] = enabled
                settingsChangeLogger.submit("oauth_enabled", old.toString(), enabled.toString()) { _, n ->
                    "OAuth ${if (n.toBoolean()) "enabled" else "disabled"}"
                }
            }
        }

        override suspend fun updateBearerTokenEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[BEARER_TOKEN_ENABLED_KEY] ?: true
                prefs[BEARER_TOKEN_ENABLED_KEY] = enabled
                settingsChangeLogger.submit("bearer_token_enabled", old.toString(), enabled.toString()) { _, n ->
                    "Bearer token auth ${if (n.toBoolean()) "enabled" else "disabled"}"
                }
                if (enabled && prefs[BEARER_TOKEN_KEY].isNullOrEmpty()) {
                    val generated = generateTokenString()
                    prefs[BEARER_TOKEN_KEY] = generated
                    settingsChangeLogger.submit("bearer_token", "", generated) { _, _ -> "Bearer token changed" }
                }
            }
        }

        override suspend fun updatePublicUrlOverride(url: String) {
            dataStore.edit { prefs ->
                val old = prefs[PUBLIC_URL_OVERRIDE_KEY] ?: ""
                prefs[PUBLIC_URL_OVERRIDE_KEY] = url
                settingsChangeLogger.submit("public_url_override", old, url) { o, n ->
                    "Public URL override changed ${o.ifEmpty { "(none)" }} → ${n.ifEmpty { "(none)" }}"
                }
            }
        }

        override fun validatePublicUrlOverride(url: String): Result<String> {
            if (url.isBlank()) {
                return Result.success("")
            }
            return try {
                val parsed = URL(url)
                if (parsed.protocol != "http" && parsed.protocol != "https") {
                    Result.failure(IllegalArgumentException("URL must use http or https protocol"))
                } else {
                    Result.success(url)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Result.failure(IllegalArgumentException("Invalid URL format: ${e.message}"))
            }
        }

        override suspend fun getOrCreateJwtSigningSecret(): String {
            dataStore.data
                .first()[JWT_SIGNING_SECRET_KEY]
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
            dataStore.edit { prefs ->
                if (prefs[JWT_SIGNING_SECRET_KEY].isNullOrEmpty()) {
                    val raw = ByteArray(JWT_SECRET_BYTES).also { java.security.SecureRandom().nextBytes(it) }
                    prefs[JWT_SIGNING_SECRET_KEY] =
                        java.util.Base64
                            .getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(raw)
                }
            }
            // read-AFTER-edit: key is guaranteed present, so !! is always safe
            return dataStore.data.first()[JWT_SIGNING_SECRET_KEY]!!
        }

        override suspend fun updatePort(port: Int) {
            dataStore.edit { prefs ->
                val old = prefs[PORT_KEY] ?: ServerConfig.DEFAULT_PORT
                prefs[PORT_KEY] = port
                settingsChangeLogger.submit("port", old.toString(), port.toString()) { o, n ->
                    "Port changed $o → $n"
                }
            }
        }

        override suspend fun updateBindingAddress(bindingAddress: BindingAddress) {
            dataStore.edit { prefs ->
                val old = prefs[BINDING_ADDRESS_KEY] ?: BindingAddress.LOCALHOST.name
                prefs[BINDING_ADDRESS_KEY] = bindingAddress.name
                settingsChangeLogger.submit("binding_address", old, bindingAddress.name) { o, n ->
                    "Binding address changed $o → $n"
                }
            }
        }

        override suspend fun updateBearerToken(token: String) {
            dataStore.edit { prefs ->
                val old = prefs[BEARER_TOKEN_KEY] ?: ""
                prefs[BEARER_TOKEN_KEY] = token
                settingsChangeLogger.submit("bearer_token", old, token) { _, _ -> "Bearer token changed" }
            }
        }

        override suspend fun generateNewBearerToken(): String {
            val token = generateTokenString()
            updateBearerToken(token)
            return token
        }

        override suspend fun updateAutoStartOnBoot(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[AUTO_START_KEY] ?: false
                prefs[AUTO_START_KEY] = enabled
                settingsChangeLogger.submit("auto_start", old.toString(), enabled.toString()) { _, n ->
                    "Auto-start on boot ${if (n.toBoolean()) "enabled" else "disabled"}"
                }
            }
        }

        override val serverRunning: Flow<Boolean> =
            dataStore.data.map { prefs -> prefs[SERVER_RUNNING_KEY] ?: false }

        override suspend fun updateServerRunning(running: Boolean) {
            dataStore.edit { prefs -> prefs[SERVER_RUNNING_KEY] = running }
        }

        override suspend fun updateHttpsEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[HTTPS_ENABLED_KEY] ?: false
                prefs[HTTPS_ENABLED_KEY] = enabled
                settingsChangeLogger.submit("https_enabled", old.toString(), enabled.toString()) { _, n ->
                    "HTTPS ${if (n.toBoolean()) "enabled" else "disabled"}"
                }
            }
        }

        override suspend fun updateCertificateSource(source: CertificateSource) {
            dataStore.edit { prefs ->
                val old = prefs[CERTIFICATE_SOURCE_KEY] ?: CertificateSource.AUTO_GENERATED.name
                prefs[CERTIFICATE_SOURCE_KEY] = source.name
                settingsChangeLogger.submit("certificate_source", old, source.name) { o, n ->
                    "Certificate source changed $o → $n"
                }
            }
        }

        override suspend fun updateCertificateHostname(hostname: String) {
            dataStore.edit { prefs ->
                val old = prefs[CERTIFICATE_HOSTNAME_KEY] ?: ServerConfig.DEFAULT_CERTIFICATE_HOSTNAME
                prefs[CERTIFICATE_HOSTNAME_KEY] = hostname
                settingsChangeLogger.submit("certificate_hostname", old, hostname) { o, n ->
                    "Certificate hostname changed $o → $n"
                }
            }
        }

        override suspend fun updateTunnelEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[TUNNEL_ENABLED_KEY] ?: false
                prefs[TUNNEL_ENABLED_KEY] = enabled
                settingsChangeLogger.submit("tunnel_enabled", old.toString(), enabled.toString()) { _, n ->
                    "Remote access tunnel ${if (n.toBoolean()) "enabled" else "disabled"}"
                }
            }
        }

        override suspend fun updateTunnelProvider(provider: TunnelProviderType) {
            dataStore.edit { prefs ->
                val old = prefs[TUNNEL_PROVIDER_KEY] ?: TunnelProviderType.CLOUDFLARE.name
                prefs[TUNNEL_PROVIDER_KEY] = provider.name
                settingsChangeLogger.submit("tunnel_provider", old, provider.name) { o, n ->
                    "Tunnel provider changed $o → $n"
                }
            }
        }

        override suspend fun updateNgrokAuthtoken(authtoken: String) {
            dataStore.edit { prefs ->
                val old = prefs[NGROK_AUTHTOKEN_KEY] ?: ""
                prefs[NGROK_AUTHTOKEN_KEY] = authtoken
                settingsChangeLogger.submit("ngrok_authtoken", old, authtoken) { _, _ -> "ngrok authtoken changed" }
            }
        }

        override suspend fun updateNgrokDomain(domain: String) {
            dataStore.edit { prefs ->
                val old = prefs[NGROK_DOMAIN_KEY] ?: ""
                prefs[NGROK_DOMAIN_KEY] = domain
                settingsChangeLogger.submit("ngrok_domain", old, domain) { o, n ->
                    "ngrok domain changed $o → $n"
                }
            }
        }

        override suspend fun updateCloudflareTunnelMode(mode: CloudflareTunnelMode) {
            dataStore.edit { prefs ->
                val old = prefs[CLOUDFLARE_TUNNEL_MODE_KEY] ?: CloudflareTunnelMode.FREE.name
                prefs[CLOUDFLARE_TUNNEL_MODE_KEY] = mode.name
                settingsChangeLogger.submit("cloudflare_tunnel_mode", old, mode.name) { o, n ->
                    "Cloudflare tunnel mode changed $o → $n"
                }
            }
        }

        override suspend fun updateCloudflareTunnelToken(token: String) {
            dataStore.edit { prefs ->
                val old = prefs[CLOUDFLARE_TUNNEL_TOKEN_KEY] ?: ""
                prefs[CLOUDFLARE_TUNNEL_TOKEN_KEY] = token
                settingsChangeLogger.submit("cloudflare_tunnel_token", old, token) { _, _ ->
                    "Cloudflare tunnel token changed"
                }
            }
        }

        override suspend fun updateFileSizeLimit(limitMb: Int) {
            dataStore.edit { prefs ->
                val old = prefs[FILE_SIZE_LIMIT_KEY] ?: ServerConfig.DEFAULT_FILE_SIZE_LIMIT_MB
                prefs[FILE_SIZE_LIMIT_KEY] = limitMb
                settingsChangeLogger.submit("file_size_limit", old.toString(), limitMb.toString()) { o, n ->
                    "File size limit changed $o → $n MB"
                }
            }
        }

        override fun validateFileSizeLimit(limitMb: Int): Result<Int> =
            if (limitMb in ServerConfig.MIN_FILE_SIZE_LIMIT_MB..ServerConfig.MAX_FILE_SIZE_LIMIT_MB) {
                Result.success(limitMb)
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "File size limit must be between ${ServerConfig.MIN_FILE_SIZE_LIMIT_MB} and " +
                            "${ServerConfig.MAX_FILE_SIZE_LIMIT_MB} MB",
                    ),
                )
            }

        override suspend fun updateAllowHttpDownloads(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[ALLOW_HTTP_DOWNLOADS_KEY] ?: false
                prefs[ALLOW_HTTP_DOWNLOADS_KEY] = enabled
                settingsChangeLogger.submit("allow_http_downloads", old.toString(), enabled.toString()) { _, n ->
                    "HTTP downloads ${if (n.toBoolean()) "allowed" else "disallowed"}"
                }
            }
        }

        override suspend fun updateAllowUnverifiedHttpsCerts(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[ALLOW_UNVERIFIED_HTTPS_KEY] ?: false
                prefs[ALLOW_UNVERIFIED_HTTPS_KEY] = enabled
                settingsChangeLogger.submit(
                    "allow_unverified_https_certs",
                    old.toString(),
                    enabled.toString(),
                ) { _, n -> "Unverified HTTPS certificates ${if (n.toBoolean()) "allowed" else "disallowed"}" }
            }
        }

        override suspend fun updateDownloadTimeout(seconds: Int) {
            dataStore.edit { prefs ->
                val old = prefs[DOWNLOAD_TIMEOUT_KEY] ?: ServerConfig.DEFAULT_DOWNLOAD_TIMEOUT_SECONDS
                prefs[DOWNLOAD_TIMEOUT_KEY] = seconds
                settingsChangeLogger.submit("download_timeout", old.toString(), seconds.toString()) { o, n ->
                    "Download timeout changed $o → $n s"
                }
            }
        }

        override suspend fun updateDeviceSlug(slug: String) {
            dataStore.edit { prefs ->
                val old = prefs[DEVICE_SLUG_KEY] ?: ""
                prefs[DEVICE_SLUG_KEY] = slug
                settingsChangeLogger.submit("device_slug", old, slug) { o, n ->
                    "Device slug changed $o → $n"
                }
            }
        }

        override suspend fun updateToolPermissionsConfig(config: ToolPermissionsConfig) {
            dataStore.edit { prefs ->
                val old = ToolPermissionsConfig.fromJsonOrDefault(prefs[TOOL_PERMISSIONS_KEY])
                prefs[TOOL_PERMISSIONS_KEY] = config.toJson()
                logToolPermissionsDiff(old, config)
            }
        }

        override suspend fun updateToolEnabled(
            toolName: String,
            enabled: Boolean,
        ) {
            dataStore.edit { prefs ->
                val current = ToolPermissionsConfig.fromJsonOrDefault(prefs[TOOL_PERMISSIONS_KEY])
                val updated =
                    if (enabled) {
                        current.copy(disabledTools = current.disabledTools - toolName)
                    } else {
                        current.copy(disabledTools = current.disabledTools + toolName)
                    }
                prefs[TOOL_PERMISSIONS_KEY] = updated.toJson()
                logToolPermissionsDiff(current, updated)
            }
        }

        override suspend fun updateParamEnabled(
            toolName: String,
            paramName: String,
            enabled: Boolean,
        ) {
            dataStore.edit { prefs ->
                val current = ToolPermissionsConfig.fromJsonOrDefault(prefs[TOOL_PERMISSIONS_KEY])
                val currentParams = current.disabledParams[toolName] ?: emptySet()
                val newParams = if (enabled) currentParams - paramName else currentParams + paramName
                val newDisabledParams =
                    if (newParams.isEmpty()) {
                        current.disabledParams - toolName
                    } else {
                        current.disabledParams + (toolName to newParams)
                    }
                val updated = current.copy(disabledParams = newDisabledParams)
                prefs[TOOL_PERMISSIONS_KEY] = updated.toJson()
                logToolPermissionsDiff(current, updated)
            }
        }

        private fun logToolPermissionsDiff(
            old: ToolPermissionsConfig,
            new: ToolPermissionsConfig,
        ) {
            (new.disabledTools - old.disabledTools).forEach { tool ->
                settingsChangeLogger.submit("tool:$tool", "enabled", "disabled") { _, _ -> "Tool '$tool' disabled" }
            }
            (old.disabledTools - new.disabledTools).forEach { tool ->
                settingsChangeLogger.submit("tool:$tool", "disabled", "enabled") { _, _ -> "Tool '$tool' enabled" }
            }
            (new.disabledParams.keys + old.disabledParams.keys).forEach { tool ->
                val oldParams = old.disabledParams[tool].orEmpty()
                val newParams = new.disabledParams[tool].orEmpty()
                (newParams - oldParams).forEach { param ->
                    settingsChangeLogger.submit("param:$tool:$param", "enabled", "disabled") { _, _ ->
                        "Parameter '$param' of tool '$tool' disabled"
                    }
                }
                (oldParams - newParams).forEach { param ->
                    settingsChangeLogger.submit("param:$tool:$param", "disabled", "enabled") { _, _ ->
                        "Parameter '$param' of tool '$tool' enabled"
                    }
                }
            }
        }

        override fun validateDownloadTimeout(seconds: Int): Result<Int> =
            if (seconds in ServerConfig.MIN_DOWNLOAD_TIMEOUT_SECONDS..ServerConfig.MAX_DOWNLOAD_TIMEOUT_SECONDS) {
                Result.success(seconds)
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "Download timeout must be between ${ServerConfig.MIN_DOWNLOAD_TIMEOUT_SECONDS} and " +
                            "${ServerConfig.MAX_DOWNLOAD_TIMEOUT_SECONDS} seconds",
                    ),
                )
            }

        @Suppress("ReturnCount")
        override fun validateDeviceSlug(slug: String): Result<String> {
            if (slug.length > ServerConfig.MAX_DEVICE_SLUG_LENGTH) {
                return Result.failure(
                    IllegalArgumentException(
                        "Device slug must be at most ${ServerConfig.MAX_DEVICE_SLUG_LENGTH} characters",
                    ),
                )
            }
            if (!ServerConfig.DEVICE_SLUG_PATTERN.matches(slug)) {
                return Result.failure(
                    IllegalArgumentException(
                        "Device slug can only contain letters, digits, and underscores",
                    ),
                )
            }
            return Result.success(slug)
        }

        override suspend fun getStoredLocations(): List<SettingsRepository.StoredLocation> {
            val prefs = dataStore.data.first()
            val jsonString = prefs[AUTHORIZED_LOCATIONS_KEY] ?: return emptyList()
            return parseStoredLocationsJson(jsonString)
        }

        override suspend fun addStoredLocation(location: SettingsRepository.StoredLocation) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                existing.add(location)
                prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
                settingsChangeLogger.submit("storage_add:${location.id}", "absent", "present") { _, _ ->
                    "Storage location added: ${location.description}"
                }
            }
        }

        override suspend fun removeStoredLocation(locationId: String) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                val removed = existing.firstOrNull { it.id == locationId }
                existing.removeAll { it.id == locationId }
                prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
                if (removed != null) {
                    settingsChangeLogger.submit("storage_remove:$locationId", "present", "absent") { _, _ ->
                        "Storage location removed: ${removed.description}"
                    }
                }
            }
        }

        override suspend fun updateLocationDescription(
            locationId: String,
            description: String,
        ) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                val index = existing.indexOfFirst { it.id == locationId }
                if (index >= 0) {
                    val oldDescription = existing[index].description
                    existing[index] = existing[index].copy(description = description)
                    prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
                    settingsChangeLogger.submit("storage_desc:$locationId", oldDescription, description) { o, n ->
                        "Storage location renamed $o → $n"
                    }
                } else {
                    Log.w(TAG, "updateLocationDescription: location ${sanitizeLocationId(locationId)} not found, no-op")
                }
            }
        }

        override suspend fun updateLocationAllowWrite(
            locationId: String,
            allowWrite: Boolean,
        ) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                val index = existing.indexOfFirst { it.id == locationId }
                if (index >= 0) {
                    val description = existing[index].description
                    val old = existing[index].allowWrite
                    existing[index] = existing[index].copy(allowWrite = allowWrite)
                    prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
                    settingsChangeLogger.submit(
                        "storage_write:$locationId",
                        old.toString(),
                        allowWrite.toString(),
                    ) { _, n ->
                        "Storage location '$description' write access ${if (n.toBoolean()) "allowed" else "revoked"}"
                    }
                } else {
                    Log.w(TAG, "updateLocationAllowWrite: location ${sanitizeLocationId(locationId)} not found, no-op")
                }
            }
        }

        override suspend fun updateLocationAllowDelete(
            locationId: String,
            allowDelete: Boolean,
        ) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                val index = existing.indexOfFirst { it.id == locationId }
                if (index >= 0) {
                    val description = existing[index].description
                    val old = existing[index].allowDelete
                    existing[index] = existing[index].copy(allowDelete = allowDelete)
                    prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
                    settingsChangeLogger.submit(
                        "storage_delete:$locationId",
                        old.toString(),
                        allowDelete.toString(),
                    ) { _, n ->
                        "Storage location '$description' delete access ${if (n.toBoolean()) "allowed" else "revoked"}"
                    }
                } else {
                    Log.w(TAG, "updateLocationAllowDelete: location ${sanitizeLocationId(locationId)} not found, no-op")
                }
            }
        }

        override suspend fun getBuiltinLocationPermissions(): Map<String, BuiltinPermissions> {
            val prefs = dataStore.data.first()
            val json = prefs[BUILTIN_LOCATION_PERMISSIONS_KEY] ?: return emptyMap()
            return parseBuiltinPermissionsJson(json)
        }

        override suspend fun updateBuiltinLocationAllowWrite(
            locationId: String,
            allowWrite: Boolean,
        ) {
            dataStore.edit { prefs ->
                val current = getBuiltinLocationPermissionsInternal(prefs)
                val existing = current[locationId] ?: BuiltinPermissions()
                val updated = current + (locationId to existing.copy(allowWrite = allowWrite))
                prefs[BUILTIN_LOCATION_PERMISSIONS_KEY] = serializeBuiltinPermissions(updated)
                settingsChangeLogger.submit(
                    "storage_write:$locationId",
                    existing.allowWrite.toString(),
                    allowWrite.toString(),
                ) { _, n ->
                    "Storage location '$locationId' write access ${if (n.toBoolean()) "allowed" else "revoked"}"
                }
            }
        }

        override suspend fun updateBuiltinLocationAllowDelete(
            locationId: String,
            allowDelete: Boolean,
        ) {
            dataStore.edit { prefs ->
                val current = getBuiltinLocationPermissionsInternal(prefs)
                val existing = current[locationId] ?: BuiltinPermissions()
                val updated = current + (locationId to existing.copy(allowDelete = allowDelete))
                prefs[BUILTIN_LOCATION_PERMISSIONS_KEY] = serializeBuiltinPermissions(updated)
                settingsChangeLogger.submit(
                    "storage_delete:$locationId",
                    existing.allowDelete.toString(),
                    allowDelete.toString(),
                ) { _, n ->
                    "Storage location '$locationId' delete access ${if (n.toBoolean()) "allowed" else "revoked"}"
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun parseBuiltinPermissionsJson(json: String): Map<String, BuiltinPermissions> =
            try {
                val root = Json.parseToJsonElement(json).jsonObject
                root.entries.associate { (key, value) ->
                    val obj = value.jsonObject
                    key to
                        BuiltinPermissions(
                            allowWrite = obj["allowWrite"]?.jsonPrimitive?.booleanOrNull ?: false,
                            allowDelete = obj["allowDelete"]?.jsonPrimitive?.booleanOrNull ?: false,
                        )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse builtin location permissions JSON", e)
                emptyMap()
            }

        private fun getBuiltinLocationPermissionsInternal(prefs: Preferences): Map<String, BuiltinPermissions> {
            val json = prefs[BUILTIN_LOCATION_PERMISSIONS_KEY] ?: return emptyMap()
            return parseBuiltinPermissionsJson(json)
        }

        private fun serializeBuiltinPermissions(perms: Map<String, BuiltinPermissions>): String =
            Json.encodeToString(
                buildJsonObject {
                    for ((key, value) in perms) {
                        put(
                            key,
                            buildJsonObject {
                                put("allowWrite", value.allowWrite)
                                put("allowDelete", value.allowDelete)
                            },
                        )
                    }
                },
            )

        override fun validatePort(port: Int): Result<Int> =
            if (port in ServerConfig.MIN_PORT..ServerConfig.MAX_PORT) {
                Result.success(port)
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "Port must be between ${ServerConfig.MIN_PORT} and ${ServerConfig.MAX_PORT}",
                    ),
                )
            }

        @Suppress("ReturnCount")
        override fun validateCertificateHostname(hostname: String): Result<String> {
            if (hostname.isBlank()) {
                return Result.failure(
                    IllegalArgumentException("Certificate hostname must not be empty"),
                )
            }

            if (!HOSTNAME_PATTERN.matches(hostname)) {
                return Result.failure(
                    IllegalArgumentException(
                        "Certificate hostname contains invalid characters. " +
                            "Use only letters, digits, hyphens, and dots.",
                    ),
                )
            }

            return Result.success(hostname)
        }

        /**
         * Maps raw [Preferences] to a [ServerConfig] instance, applying defaults
         * for any missing keys.
         */
        @Suppress("CyclomaticComplexMethod")
        private fun mapPreferencesToServerConfig(prefs: Preferences): ServerConfig {
            val bindingAddressName = prefs[BINDING_ADDRESS_KEY] ?: BindingAddress.LOCALHOST.name
            val certificateSourceName = prefs[CERTIFICATE_SOURCE_KEY] ?: CertificateSource.AUTO_GENERATED.name

            val tunnelProviderName = prefs[TUNNEL_PROVIDER_KEY] ?: TunnelProviderType.CLOUDFLARE.name
            val cloudflareTunnelModeName =
                prefs[CLOUDFLARE_TUNNEL_MODE_KEY] ?: CloudflareTunnelMode.FREE.name

            return ServerConfig(
                port = prefs[PORT_KEY] ?: ServerConfig.DEFAULT_PORT,
                bindingAddress =
                    BindingAddress.entries.firstOrNull { it.name == bindingAddressName }
                        ?: BindingAddress.LOCALHOST,
                bearerToken = prefs[BEARER_TOKEN_KEY] ?: "",
                autoStartOnBoot = prefs[AUTO_START_KEY] ?: false,
                httpsEnabled = prefs[HTTPS_ENABLED_KEY] ?: false,
                certificateSource =
                    CertificateSource.entries.firstOrNull { it.name == certificateSourceName }
                        ?: CertificateSource.AUTO_GENERATED,
                certificateHostname =
                    prefs[CERTIFICATE_HOSTNAME_KEY]
                        ?: ServerConfig.DEFAULT_CERTIFICATE_HOSTNAME,
                tunnelEnabled = prefs[TUNNEL_ENABLED_KEY] ?: false,
                tunnelProvider =
                    TunnelProviderType.entries.firstOrNull { it.name == tunnelProviderName }
                        ?: TunnelProviderType.CLOUDFLARE,
                ngrokAuthtoken = prefs[NGROK_AUTHTOKEN_KEY] ?: "",
                ngrokDomain = prefs[NGROK_DOMAIN_KEY] ?: "",
                cloudflareTunnelMode =
                    CloudflareTunnelMode.entries.firstOrNull { it.name == cloudflareTunnelModeName }
                        ?: CloudflareTunnelMode.FREE,
                cloudflareTunnelToken = prefs[CLOUDFLARE_TUNNEL_TOKEN_KEY] ?: "",
                fileSizeLimitMb = prefs[FILE_SIZE_LIMIT_KEY] ?: ServerConfig.DEFAULT_FILE_SIZE_LIMIT_MB,
                allowHttpDownloads = prefs[ALLOW_HTTP_DOWNLOADS_KEY] ?: false,
                allowUnverifiedHttpsCerts = prefs[ALLOW_UNVERIFIED_HTTPS_KEY] ?: false,
                downloadTimeoutSeconds =
                    prefs[DOWNLOAD_TIMEOUT_KEY]
                        ?: ServerConfig.DEFAULT_DOWNLOAD_TIMEOUT_SECONDS,
                deviceSlug = prefs[DEVICE_SLUG_KEY] ?: "",
                oauthEnabled = prefs[OAUTH_ENABLED_KEY] ?: true,
                bearerTokenEnabled = prefs[BEARER_TOKEN_ENABLED_KEY] ?: true,
                publicUrlOverride = prefs[PUBLIC_URL_OVERRIDE_KEY] ?: "",
                toolPermissionsConfig = ToolPermissionsConfig.fromJsonOrDefault(prefs[TOOL_PERMISSIONS_KEY]),
            )
        }

        /**
         * Generates a random UUID string for use as a bearer token.
         */
        private fun generateTokenString(): String = UUID.randomUUID().toString()

        @Suppress("SwallowedException", "TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")
        private fun parseStoredLocationsJson(json: String?): List<SettingsRepository.StoredLocation> {
            if (json == null) return emptyList()
            return try {
                val jsonArray = Json.parseToJsonElement(json).jsonArray
                jsonArray.mapNotNull { element ->
                    try {
                        val obj = element.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val path = obj["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val treeUri = obj["treeUri"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val description = obj["description"]?.jsonPrimitive?.content ?: ""
                        val allowWriteElement = obj["allowWrite"]
                        // Missing allowWrite in persisted JSON means this is pre-permission-fields
                        // data; default to true for backwards compatibility (old locations were
                        // implicitly full-access).
                        val allowWrite =
                            if (allowWriteElement == null || allowWriteElement is JsonNull) {
                                true
                            } else {
                                allowWriteElement.jsonPrimitive.booleanOrNull ?: false
                            }
                        val allowDeleteElement = obj["allowDelete"]
                        // Missing allowDelete in persisted JSON means this is pre-permission-fields
                        // data; default to true for backwards compatibility (old locations were
                        // implicitly full-access).
                        val allowDelete =
                            if (allowDeleteElement == null || allowDeleteElement is JsonNull) {
                                true
                            } else {
                                allowDeleteElement.jsonPrimitive.booleanOrNull ?: false
                            }
                        SettingsRepository.StoredLocation(
                            id = id,
                            name = name,
                            path = path,
                            description = description,
                            treeUri = treeUri,
                            allowWrite = allowWrite,
                            allowDelete = allowDelete,
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping malformed stored location entry", e)
                        null
                    }
                }
            } catch (_: Exception) {
                // Migration: try parsing old format (JSON object: {"locationId": "treeUri"}).
                // Old-format locations pre-date permission fields and were implicitly
                // full-access, so allowWrite and allowDelete default to true.
                try {
                    val jsonObject = Json.parseToJsonElement(json).jsonObject
                    jsonObject.map { (key, value) ->
                        SettingsRepository.StoredLocation(
                            id = key,
                            name = key.substringAfterLast("/"),
                            path = "/",
                            description = "",
                            treeUri = value.jsonPrimitive.content,
                            allowWrite = true,
                            allowDelete = true,
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse stored locations JSON, returning empty list", e)
                    emptyList()
                }
            }
        }

        private fun serializeStoredLocationsJson(locations: List<SettingsRepository.StoredLocation>): String =
            Json.encodeToString(
                buildJsonArray {
                    for (loc in locations) {
                        add(
                            buildJsonObject {
                                put("id", loc.id)
                                put("name", loc.name)
                                put("path", loc.path)
                                put("description", loc.description)
                                put("treeUri", loc.treeUri)
                                put("allowWrite", loc.allowWrite)
                                put("allowDelete", loc.allowDelete)
                            },
                        )
                    }
                },
            )

        // --- Event Channel ---

        override val eventChannelConfig: Flow<EventChannelConfig> =
            dataStore.data.map { prefs ->
                val json = prefs[EVENT_CHANNEL_CONFIG_KEY] ?: return@map EventChannelConfig()
                EventChannelConfig.fromJsonOrDefault(json)
            }

        override suspend fun getEventChannelConfig(): EventChannelConfig = eventChannelConfig.first()

        private suspend fun updateEventChannelConfig(
            transform: (EventChannelConfig) -> EventChannelConfig,
        ): Pair<EventChannelConfig, EventChannelConfig> {
            val current = getEventChannelConfig()
            val updated = transform(current)
            dataStore.edit { prefs ->
                prefs[EVENT_CHANNEL_CONFIG_KEY] = updated.toJson()
            }
            return current to updated
        }

        override suspend fun updateEventChannelEnabled(enabled: Boolean) {
            val (old, new) = updateEventChannelConfig { it.copy(enabled = enabled) }
            settingsChangeLogger.submit("channel_enabled", old.enabled.toString(), new.enabled.toString()) { _, n ->
                "Event channel ${if (n.toBoolean()) "enabled" else "disabled"}"
            }
        }

        override suspend fun updateEventChannelEndpointUrl(url: String) {
            val (old, new) = updateEventChannelConfig { it.copy(endpointUrl = url) }
            settingsChangeLogger.submit("channel_endpoint", old.endpointUrl, new.endpointUrl) { o, n ->
                "Event channel endpoint changed $o → $n"
            }
        }

        override suspend fun updateEventChannelAuthToken(token: String) {
            val (old, new) = updateEventChannelConfig { it.copy(authToken = token) }
            settingsChangeLogger.submit("channel_auth_token", old.authToken, new.authToken) { _, _ ->
                "Event channel auth token changed"
            }
        }

        override suspend fun generateNewEventChannelAuthToken(): String {
            val token = generateTokenString()
            updateEventChannelAuthToken(token)
            return token
        }

        override fun validateEndpointUrl(url: String): Result<String> {
            if (url.isBlank()) {
                return Result.failure(IllegalArgumentException("Endpoint URL cannot be empty"))
            }
            return try {
                val parsed = URL(url)
                if (parsed.protocol != "http" && parsed.protocol != "https") {
                    Result.failure(IllegalArgumentException("URL must use http or https protocol"))
                } else {
                    Result.success(url)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Result.failure(IllegalArgumentException("Invalid URL format: ${e.message}"))
            }
        }

        override suspend fun updateNotificationChannelEnabled(enabled: Boolean) {
            val (old, new) =
                updateEventChannelConfig { it.copy(notifications = it.notifications.copy(enabled = enabled)) }
            settingsChangeLogger.submit(
                "channel_notifications",
                old.notifications.enabled.toString(),
                new.notifications.enabled.toString(),
            ) { _, n -> "Notification events ${if (n.toBoolean()) "enabled" else "disabled"}" }
        }

        override suspend fun updateNotificationFilterMode(mode: NotificationFilterMode) {
            val (old, new) =
                updateEventChannelConfig { it.copy(notifications = it.notifications.copy(filterMode = mode)) }
            settingsChangeLogger.submit(
                "channel_notif_filter_mode",
                old.notifications.filterMode.name,
                new.notifications.filterMode.name,
            ) { o, n -> "Notification filter mode changed $o → $n" }
        }

        override suspend fun updateNotificationFilterApps(apps: Set<String>) {
            val (old, new) =
                updateEventChannelConfig { it.copy(notifications = it.notifications.copy(filterApps = apps)) }
            settingsChangeLogger.submit(
                "channel_notif_filter_apps",
                serializeSet(old.notifications.filterApps),
                serializeSet(new.notifications.filterApps),
            ) { o, n -> "Notification filter apps changed ${serializedSetCount(o)} → ${serializedSetCount(n)}" }
        }

        override suspend fun updateWifiChannelEnabled(enabled: Boolean) {
            val (old, new) = updateEventChannelConfig { it.copy(wifi = it.wifi.copy(enabled = enabled)) }
            settingsChangeLogger.submit(
                "channel_wifi",
                old.wifi.enabled.toString(),
                new.wifi.enabled.toString(),
            ) { _, n -> "Wi-Fi events ${if (n.toBoolean()) "enabled" else "disabled"}" }
        }

        override suspend fun updateWifiSsids(ssids: Set<String>) {
            val (old, new) = updateEventChannelConfig { it.copy(wifi = it.wifi.copy(ssids = ssids)) }
            settingsChangeLogger.submit(
                "channel_wifi_ssids",
                serializeSet(old.wifi.ssids),
                serializeSet(new.wifi.ssids),
            ) { o, n -> "Wi-Fi monitored SSIDs changed ${serializedSetCount(o)} → ${serializedSetCount(n)}" }
        }

        override suspend fun updateWifiNotifyOnDiscovered(enabled: Boolean) {
            val (old, new) = updateEventChannelConfig { it.copy(wifi = it.wifi.copy(notifyOnDiscovered = enabled)) }
            settingsChangeLogger.submit(
                "channel_wifi_discovered",
                old.wifi.notifyOnDiscovered.toString(),
                new.wifi.notifyOnDiscovered.toString(),
            ) { _, n -> "Wi-Fi notify-on-discovered ${if (n.toBoolean()) "enabled" else "disabled"}" }
        }

        override suspend fun updateWifiNotifyOnLost(enabled: Boolean) {
            val (old, new) = updateEventChannelConfig { it.copy(wifi = it.wifi.copy(notifyOnLost = enabled)) }
            settingsChangeLogger.submit(
                "channel_wifi_lost",
                old.wifi.notifyOnLost.toString(),
                new.wifi.notifyOnLost.toString(),
            ) { _, n -> "Wi-Fi notify-on-lost ${if (n.toBoolean()) "enabled" else "disabled"}" }
        }

        override suspend fun updateWifiNotifyOnConnected(enabled: Boolean) {
            val (old, new) = updateEventChannelConfig { it.copy(wifi = it.wifi.copy(notifyOnConnected = enabled)) }
            settingsChangeLogger.submit(
                "channel_wifi_connected",
                old.wifi.notifyOnConnected.toString(),
                new.wifi.notifyOnConnected.toString(),
            ) { _, n -> "Wi-Fi notify-on-connected ${if (n.toBoolean()) "enabled" else "disabled"}" }
        }

        override suspend fun updateWifiNotifyOnDisconnected(enabled: Boolean) {
            val (old, new) = updateEventChannelConfig { it.copy(wifi = it.wifi.copy(notifyOnDisconnected = enabled)) }
            settingsChangeLogger.submit(
                "channel_wifi_disconnected",
                old.wifi.notifyOnDisconnected.toString(),
                new.wifi.notifyOnDisconnected.toString(),
            ) { _, n -> "Wi-Fi notify-on-disconnected ${if (n.toBoolean()) "enabled" else "disabled"}" }
        }

        private fun serializeSet(values: Set<String>): String = values.sorted().joinToString("\n")

        private fun serializedSetCount(value: String): Int = if (value.isEmpty()) 0 else value.split('\n').size

        companion object {
            private const val TAG = "MCP:SettingsRepo"
            private const val MAX_LOCATION_ID_LOG_LENGTH = 200
            private const val JWT_SECRET_BYTES = 32
            private val CONTROL_CHAR_REGEX = Regex("[\\p{Cntrl}]")

            private fun sanitizeLocationId(locationId: String): String =
                locationId.take(MAX_LOCATION_ID_LOG_LENGTH).replace(CONTROL_CHAR_REGEX, "")

            private val PORT_KEY = intPreferencesKey("port")
            private val BINDING_ADDRESS_KEY = stringPreferencesKey("binding_address")
            private val BEARER_TOKEN_KEY = stringPreferencesKey("bearer_token")
            private val BEARER_TOKEN_INITIALIZED_KEY = booleanPreferencesKey("bearer_token_initialized")
            private val OAUTH_ENABLED_KEY = booleanPreferencesKey("oauth_enabled")
            private val BEARER_TOKEN_ENABLED_KEY = booleanPreferencesKey("bearer_token_enabled")
            private val BEARER_TOKEN_ENABLED_INITIALIZED_KEY =
                booleanPreferencesKey("bearer_token_enabled_initialized")
            private val PUBLIC_URL_OVERRIDE_KEY = stringPreferencesKey("public_url_override")
            private val JWT_SIGNING_SECRET_KEY = stringPreferencesKey("jwt_signing_secret")
            private val AUTO_START_KEY = booleanPreferencesKey("auto_start_on_boot")
            private val SERVER_RUNNING_KEY = booleanPreferencesKey("server_running")
            private val HTTPS_ENABLED_KEY = booleanPreferencesKey("https_enabled")
            private val CERTIFICATE_SOURCE_KEY = stringPreferencesKey("certificate_source")
            private val CERTIFICATE_HOSTNAME_KEY = stringPreferencesKey("certificate_hostname")
            private val TUNNEL_ENABLED_KEY = booleanPreferencesKey("tunnel_enabled")
            private val TUNNEL_PROVIDER_KEY = stringPreferencesKey("tunnel_provider")
            private val NGROK_AUTHTOKEN_KEY = stringPreferencesKey("ngrok_authtoken")
            private val NGROK_DOMAIN_KEY = stringPreferencesKey("ngrok_domain")
            private val CLOUDFLARE_TUNNEL_MODE_KEY = stringPreferencesKey("cloudflare_tunnel_mode")
            private val CLOUDFLARE_TUNNEL_TOKEN_KEY = stringPreferencesKey("cloudflare_tunnel_token")
            private val FILE_SIZE_LIMIT_KEY = intPreferencesKey("file_size_limit_mb")
            private val ALLOW_HTTP_DOWNLOADS_KEY = booleanPreferencesKey("allow_http_downloads")
            private val ALLOW_UNVERIFIED_HTTPS_KEY = booleanPreferencesKey("allow_unverified_https_certs")
            private val DOWNLOAD_TIMEOUT_KEY = intPreferencesKey("download_timeout_seconds")
            private val DEVICE_SLUG_KEY = stringPreferencesKey("device_slug")
            private val TOOL_PERMISSIONS_KEY = stringPreferencesKey("tool_permissions")
            private val AUTHORIZED_LOCATIONS_KEY = stringPreferencesKey("authorized_storage_locations")
            private val BUILTIN_LOCATION_PERMISSIONS_KEY = stringPreferencesKey("builtin_location_permissions")
            private val EVENT_CHANNEL_CONFIG_KEY = stringPreferencesKey("event_channel_config")

            /**
             * Regex pattern for valid hostnames.
             *
             * Allows labels of letters, digits, and hyphens separated by dots.
             * Each label must start and end with an alphanumeric character.
             * Maximum total length is 253 characters per RFC 1035.
             */
            private val HOSTNAME_PATTERN =
                Regex(
                    "^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)*" +
                        "[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$",
                )
        }
    }
