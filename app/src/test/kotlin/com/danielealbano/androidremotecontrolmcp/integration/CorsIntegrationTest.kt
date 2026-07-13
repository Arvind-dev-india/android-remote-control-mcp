package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.mcp.auth.McpAuthPlugin
import com.danielealbano.androidremotecontrolmcp.mcp.configureCors
import com.danielealbano.androidremotecontrolmcp.mcp.effectiveBaseUrl
import com.danielealbano.androidremotecontrolmcp.mcp.mcpStreamableHttp
import com.danielealbano.androidremotecontrolmcp.services.sharing.EphemeralFileLinkService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CORS Integration Tests")
class CorsIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    /**
     * Configures a test application mirroring the production plugin ordering in
     * [com.danielealbano.androidremotecontrolmcp.mcp.McpServer.configureApplication]: ContentNegotiation,
     * then [configureCors] (BEFORE auth so preflight is not failed closed), then [McpAuthPlugin], a
     * `/health` and a stand-in `/register` route (both auth-excluded), and `/mcp`.
     *
     * NOTE: this replicates the production wiring rather than invoking it (McpServer builds Netty). If
     * the production ordering or auth-exclusion set changes, this MUST be kept in sync or these tests
     * stop protecting the real behavior.
     *
     * @param oauthEnabled When true, auth emits the RFC 9728 `WWW-Authenticate` discovery header on 401
     *   (the OAuth token check always denies here — only the 401 header path is under test).
     */
    private suspend fun withCorsApp(
        oauthEnabled: Boolean = false,
        testBlock: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        val deps = McpIntegrationTestHelper.createMockDependencies()
        val sdkServer = McpIntegrationTestHelper.createSdkServer(deps)
        testApplication {
            application {
                install(ContentNegotiation) { json(McpJson) }
                configureCors()
                install(McpAuthPlugin) {
                    bearerTokenEnabled = true
                    expectedToken = McpIntegrationTestHelper.TEST_BEARER_TOKEN
                    this.oauthEnabled = oauthEnabled
                    validateOAuthToken = { _, _ -> false }
                    baseUrlOf = { effectiveBaseUrl(it, "") }
                    excludedPaths = setOf("/health", "/register", "/token", "/authorize", "/authorize/status")
                    excludedPathPrefixes = setOf(EphemeralFileLinkService.PATH_PREFIX, "/.well-known/")
                }
                routing {
                    get("/health") { call.respondText("{}", ContentType.Application.Json) }
                    post("/register") { call.respondText("{}", ContentType.Application.Json) }
                }
                mcpStreamableHttp { sdkServer }
            }
            testBlock()
        }
    }

    private fun HttpResponse.allowOrigin(): String? = headers[HttpHeaders.AccessControlAllowOrigin]

    private fun HttpResponse.headerTokens(name: String): List<String> =
        headers[name]
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    @Test
    fun `preflight OPTIONS on mcp returns wildcard origin and is not failed closed by auth`() =
        runTest {
            withCorsApp {
                val response =
                    client.options("/mcp") {
                        header(HttpHeaders.Origin, "https://inspector.example.com")
                        header(HttpHeaders.AccessControlRequestMethod, "POST")
                        header(
                            HttpHeaders.AccessControlRequestHeaders,
                            "authorization, content-type, mcp-protocol-version",
                        )
                    }

                assertTrue(response.status.value in 200..299, "preflight should succeed, was ${response.status}")
                assertEquals("*", response.allowOrigin())
                val allowedHeaders =
                    response.headerTokens(HttpHeaders.AccessControlAllowHeaders).map { it.lowercase() }
                listOf("authorization", "content-type", "mcp-protocol-version", "mcp-session-id").forEach {
                    assertTrue(it in allowedHeaders, "expected '$it' in Allow-Headers, was $allowedHeaders")
                }
                // POST/GET are CORS-safelisted ("simple") methods that Ktor does not enumerate — they are
                // implicitly allowed. DELETE (MCP session termination) is non-simple and MUST be listed.
                val allowedMethods = response.headerTokens(HttpHeaders.AccessControlAllowMethods)
                assertTrue("DELETE" in allowedMethods, "expected DELETE in Allow-Methods, was $allowedMethods")
            }
        }

    @Test
    fun `preflight OPTIONS on oauth register endpoint returns wildcard origin`() =
        runTest {
            withCorsApp {
                val response =
                    client.options("/register") {
                        header(HttpHeaders.Origin, "https://inspector.example.com")
                        header(HttpHeaders.AccessControlRequestMethod, "POST")
                        header(HttpHeaders.AccessControlRequestHeaders, "content-type")
                    }

                assertTrue(response.status.value in 200..299, "preflight should succeed, was ${response.status}")
                assertEquals("*", response.allowOrigin())
            }
        }

    @Test
    fun `cross-origin GET exposes mcp-session-id response header to the browser`() =
        runTest {
            withCorsApp {
                val response =
                    client.get("/health") {
                        header(HttpHeaders.Origin, "https://inspector.example.com")
                    }

                assertEquals("*", response.allowOrigin())
                val exposed = response.headers[HttpHeaders.AccessControlExposeHeaders].orEmpty()
                assertTrue(
                    exposed.split(",").any { it.trim().equals("mcp-session-id", ignoreCase = true) },
                    "expected mcp-session-id to be exposed, was '$exposed'",
                )
            }
        }

    @Test
    fun `unauthenticated cross-origin POST mcp still carries allow-origin so browser can read the 401`() =
        runTest {
            withCorsApp {
                val response =
                    client.post("/mcp") {
                        header(HttpHeaders.Origin, "https://inspector.example.com")
                        contentType(ContentType.Application.Json)
                        setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
                    }

                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertEquals("*", response.allowOrigin())
            }
        }

    @Test
    fun `authenticated cross-origin POST mcp carries allow-origin`() =
        runTest {
            withCorsApp {
                val response =
                    client.post("/mcp") {
                        header(HttpHeaders.Origin, "https://inspector.example.com")
                        header("Authorization", "Bearer ${McpIntegrationTestHelper.TEST_BEARER_TOKEN}")
                        header(HttpHeaders.Accept, "application/json, text/event-stream")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
                                """"params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
                                """"clientInfo":{"name":"cors-test","version":"1.0"}}}""",
                        )
                    }

                // Auth passed (not 401); regardless of the MCP-level outcome the CORS header must be present.
                assertTrue(response.status != HttpStatusCode.Unauthorized, "auth should pass with valid token")
                assertEquals("*", response.allowOrigin())
            }
        }

    @Test
    fun `WWW-Authenticate is exposed so a browser can read the OAuth discovery pointer on 401`() =
        runTest {
            withCorsApp(oauthEnabled = true) {
                val response =
                    client.post("/mcp") {
                        header(HttpHeaders.Origin, "https://inspector.example.com")
                        contentType(ContentType.Application.Json)
                        setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
                    }

                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertEquals("*", response.allowOrigin())
                // The discovery header is actually sent...
                val wwwAuth = response.headers[HttpHeaders.WWWAuthenticate].orEmpty()
                assertTrue(
                    wwwAuth.contains("resource_metadata"),
                    "expected WWW-Authenticate with resource_metadata, was '$wwwAuth'",
                )
                // ...and exposed to browser JS.
                val exposed = response.headerTokens(HttpHeaders.AccessControlExposeHeaders).map { it.lowercase() }
                assertTrue(
                    HttpHeaders.WWWAuthenticate.lowercase() in exposed,
                    "expected WWW-Authenticate to be exposed, was $exposed",
                )
            }
        }
}
