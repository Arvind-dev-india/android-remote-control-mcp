package com.danielealbano.androidremotecontrolmcp.mcp

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS

/** Request header carrying the negotiated MCP protocol version. */
const val MCP_PROTOCOL_VERSION_HEADER = "mcp-protocol-version"

/** Request/response header carrying the Streamable HTTP session id. */
const val MCP_SESSION_ID_HEADER = "mcp-session-id"

/**
 * Installs permissive CORS so browser-based MCP clients (e.g. the MCP Inspector) can complete the
 * OAuth discovery/DCR/authorize flow AND the `/mcp` protocol exchange. Without it the browser's
 * preflight `OPTIONS` and cross-origin requests are blocked before reaching any handler.
 *
 * MUST be installed BEFORE [com.danielealbano.androidremotecontrolmcp.mcp.auth.McpAuthPlugin]: that
 * plugin intercepts [io.ktor.server.application.ApplicationCallPipeline.Plugins] and fails closed on
 * any token-less request to `/mcp` — including a preflight `OPTIONS`, which carries no `Authorization`
 * header. Installing CORS first lets it answer and finish the preflight before auth runs.
 *
 * Any origin is allowed ([anyHost]) — this does NOT weaken auth: `/mcp` still requires the bearer or
 * OAuth token (which a cross-origin page cannot obtain) and the OAuth flow is still gated by the
 * on-device number-match approval. Credentials mode is deliberately NOT enabled (auth travels in the
 * `Authorization` header, not cookies), which is what permits the wildcard origin — the two are
 * mutually exclusive per the CORS spec.
 *
 * Response headers are exposed so browser clients can read them via `fetch()` (non-safelisted response
 * headers are hidden from JS otherwise):
 * - [MCP_SESSION_ID_HEADER] — the session id the Streamable HTTP transport returns, replayed on
 *   follow-up requests.
 * - `WWW-Authenticate` — carries the RFC 9728 `resource_metadata` pointer on a 401, which is how a
 *   browser MCP client discovers the OAuth authorization server. Not exposing it would defeat the
 *   header the auth plugin sends specifically for this discovery.
 * - [MCP_PROTOCOL_VERSION_HEADER] — the negotiated protocol version the transport may echo back.
 */
fun Application.configureCors() {
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(MCP_PROTOCOL_VERSION_HEADER)
        allowHeader(MCP_SESSION_ID_HEADER)
        exposeHeader(MCP_SESSION_ID_HEADER)
        exposeHeader(HttpHeaders.WWWAuthenticate)
        exposeHeader(MCP_PROTOCOL_VERSION_HEADER)
        // MCP POSTs use `Content-Type: application/json`, a non-simple content type whose preflight
        // Ktor rejects unless explicitly allowed.
        allowNonSimpleContentTypes = true
    }
}
