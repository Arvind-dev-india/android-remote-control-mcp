package com.danielealbano.androidremotecontrolmcp.mcp

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.mcp.auth.McpAuthPlugin
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthAccessValidator
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthRouteDeps
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthServerDeps
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.installOAuthRoutes
import com.danielealbano.androidremotecontrolmcp.services.sharing.EphemeralFileLinkService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ktor-based MCP server (HTTP by default, optional HTTPS).
 *
 * Configures and runs an embedded Netty server with:
 * - HTTP by default, optional HTTPS when enabled in settings
 * - JSON content negotiation (required by SDK's StreamableHttpServerTransport)
 * - Global bearer token authentication
 * - MCP Streamable HTTP transport at `/mcp` (JSON-only mode, no SSE)
 *
 * @param config The server configuration (port, binding address, bearer token).
 * @param keyStore The SSL KeyStore for HTTPS (null when HTTPS is disabled).
 * @param keyStorePassword The KeyStore password (null when HTTPS is disabled).
 * @param mcpSdkServer The MCP SDK Server instance with registered tools.
 * @param ephemeralFileLinkService Backs the unauthenticated `/s/{token}` capability-link route.
 * @param oauth The OAuth authorization-server collaborators (used only when `config.oauthEnabled`).
 */
class McpServer(
    private val config: ServerConfig,
    private val keyStore: KeyStore?,
    private val keyStorePassword: CharArray?,
    private val mcpSdkServer: io.modelcontextprotocol.kotlin.sdk.server.Server,
    private val ephemeralFileLinkService: EphemeralFileLinkService,
    private val oauth: OAuthServerDeps,
) {
    @Volatile
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val running = AtomicBoolean(false)

    private val accessValidator = OAuthAccessValidator(oauth.jwtTokenService, oauth.oauthClientRepository)

    /**
     * Starts the server. Non-blocking — the server runs on its own threads.
     */
    @Suppress("TooGenericExceptionCaught")
    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "Server is already running, ignoring start request")
            return
        }

        Log.i(TAG, "Starting MCP server on ${config.bindingAddress.address}:${config.port}")

        try {
            server =
                if (config.httpsEnabled && keyStore != null && keyStorePassword != null) {
                    createHttpsServer()
                } else {
                    createHttpServer()
                }

            server?.start(wait = false)
            Log.i(TAG, "MCP server started successfully")
        } catch (e: Exception) {
            server = null
            running.set(false)
            Log.e(TAG, "Failed to start MCP server", e)
            throw e
        }
    }

    /**
     * Stops the server gracefully, waiting for in-flight requests.
     *
     * @param gracePeriodMillis Grace period before force-stopping connections.
     * @param timeoutMillis Maximum time to wait for shutdown.
     */
    fun stop(
        gracePeriodMillis: Long = DEFAULT_GRACE_PERIOD_MS,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ) {
        if (!running.compareAndSet(true, false)) {
            Log.w(TAG, "Server is not running, ignoring stop request")
            return
        }

        Log.i(TAG, "Stopping MCP server (grace=${gracePeriodMillis}ms, timeout=${timeoutMillis}ms)")
        try {
            runBlocking { withTimeout(timeoutMillis) { mcpSdkServer.close() } }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "MCP SDK server close did not complete within ${timeoutMillis}ms", e)
        }
        server?.stop(gracePeriodMillis, timeoutMillis)
        server = null
        Log.i(TAG, "MCP server stopped")
    }

    fun isRunning(): Boolean = running.get()

    private fun createHttpServer(): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(
            factory = Netty,
            port = config.port,
            host = config.bindingAddress.address,
            module = { configureApplication() },
        )

    private fun createHttpsServer(): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        val ks = requireNotNull(keyStore) { "KeyStore must not be null when HTTPS is enabled" }
        val ksPassword = requireNotNull(keyStorePassword) { "KeyStore password must not be null when HTTPS is enabled" }
        return embeddedServer(
            factory = Netty,
            configure = {
                sslConnector(
                    keyStore = ks,
                    keyAlias = CertificateManager.KEY_ALIAS,
                    keyStorePassword = { ksPassword },
                    privateKeyPassword = { ksPassword },
                ) {
                    host = config.bindingAddress.address
                    port = config.port
                }
            },
            module = { configureApplication() },
        )
    }

    private fun io.ktor.server.application.Application.configureApplication() {
        // JSON serialization — required by StreamableHttpServerTransport
        // which uses call.respond(JSONRPCResponse/Error) internally
        install(ContentNegotiation) {
            json(McpJson)
        }

        // CORS — MUST be installed before McpAuthPlugin so preflight OPTIONS (which carry no
        // Authorization header) are answered by CORS instead of being failed closed by auth.
        // Enables browser MCP clients (e.g. MCP Inspector) to reach the OAuth and /mcp endpoints.
        configureCors()

        // Combined MCP authentication: static bearer OR issued OAuth access token (dual-accept).
        // Excludes /health, the unauthenticated OAuth endpoints, the /.well-known/ namespace, and the
        // /s/ capability route. Exact paths are used for the OAuth endpoints (not prefixes) so sibling
        // routes are not silently exempted.
        install(McpAuthPlugin) {
            bearerTokenEnabled = config.bearerTokenEnabled
            expectedToken = config.bearerToken
            oauthEnabled = config.oauthEnabled
            baseUrlOf = { effectiveBaseUrl(it, config.publicUrlOverride) }
            validateOAuthToken = { token, resource -> accessValidator.validate(token, resource) }
            excludedPaths = setOf("/health", "/register", "/token", "/authorize", "/authorize/status")
            excludedPathPrefixes = setOf(EphemeralFileLinkService.PATH_PREFIX, "/.well-known/")
        }

        // Health check endpoint — unauthenticated, installed before MCP routes.
        // BearerTokenAuthPlugin skips paths matching "/health" (no auth required).
        routing {
            get("/health") {
                call.respondText(
                    """{"status":"healthy","version":"${BuildConfig.VERSION_NAME}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
            }

            // Capability-link download route — unauthenticated; the token is the credential.
            // Serves the registered blob bytes with its content-type; 404 for unknown/expired tokens.
            // Never log the token or URL.
            get("${EphemeralFileLinkService.PATH_PREFIX}{token}") {
                val entry = ephemeralFileLinkService.resolve(call.parameters["token"].orEmpty())
                if (entry == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    // A sharing app may supply a malformed MIME; fall back to octet-stream rather than 500.
                    call.respondBytes(entry.bytes, contentTypeOrOctetStream(entry.mimeType), HttpStatusCode.OK)
                }
            }

            // OAuth authorization-server endpoints — mounted only when OAuth is enabled (so the
            // metadata/endpoints are absent otherwise). All are unauthenticated (excluded above).
            if (config.oauthEnabled) {
                installOAuthRoutes(
                    OAuthRouteDeps(
                        clientRepository = oauth.oauthClientRepository,
                        tokenService = oauth.jwtTokenService,
                        authorizationCodeStore = oauth.authorizationCodeStore,
                        approvalCoordinator = oauth.approvalCoordinator,
                        publicUrlOverride = config.publicUrlOverride,
                        geoIpResolver = oauth.geoIpResolver,
                    ),
                )
            }
        }

        // MCP Streamable HTTP transport at /mcp (JSON-only mode, no SSE)
        mcpStreamableHttp(publicUrlOverride = config.publicUrlOverride) {
            mcpSdkServer
        }
    }

    companion object {
        private const val TAG = "MCP:McpServer"
        private const val DEFAULT_GRACE_PERIOD_MS = 1000L
        private const val DEFAULT_TIMEOUT_MS = 5000L
    }
}
