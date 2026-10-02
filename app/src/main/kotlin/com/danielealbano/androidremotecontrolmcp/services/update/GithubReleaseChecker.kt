package com.danielealbano.androidremotecontrolmcp.services.update

import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** The tag and page URL of the latest published GitHub release. */
data class LatestRelease(
    val tagName: String,
    val htmlUrl: String,
)

object GithubReleaseSource {
    const val API_URL = "https://api.github.com/repos/Arvind-dev-india/android-remote-control-mcp/releases/latest"
    const val RELEASE_URL_PREFIX = "https://github.com/Arvind-dev-india/android-remote-control-mcp/releases/tag/"

    fun isForkReleaseUrl(url: String): Boolean = url.startsWith(RELEASE_URL_PREFIX)
}

/** Fetches the latest published release of the project from the GitHub REST API. */
interface GithubReleaseChecker {
    /**
     * Returns the latest published, non-pre-release release, or null on ANY failure (offline,
     * rate-limited, non-2xx, malformed body). This call NEVER throws — a failed check must never
     * surface to the user.
     */
    suspend fun fetchLatestRelease(): LatestRelease?
}

@Singleton
class GithubReleaseCheckerImpl
    @Inject
    constructor(
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : GithubReleaseChecker {
        // One OkHttp-backed client reused across checks (this is a @Singleton). Lazy so unit tests, which
        // override clientProvider before any call, never build a real engine. Overridable for tests.
        // Intentionally not closed: it lives for the process lifetime, like the singleton that owns it.
        private val sharedClient: HttpClient by lazy { buildClient() }
        internal var clientProvider: () -> HttpClient = { sharedClient }

        override suspend fun fetchLatestRelease(): LatestRelease? =
            withContext(ioDispatcher) {
                try {
                    val response =
                        clientProvider().get(GithubReleaseSource.API_URL) {
                            header(HttpHeaders.Accept, GITHUB_ACCEPT)
                            header(HttpHeaders.UserAgent, USER_AGENT)
                        }
                    if (!response.status.isSuccess()) {
                        Logger.w(TAG, "Update check returned HTTP ${response.status.value}")
                        return@withContext null
                    }
                    val dto = json.decodeFromString(GithubReleaseDto.serializer(), response.bodyAsText())
                    val tag = dto.tagName?.takeIf { it.isNotBlank() } ?: return@withContext null
                    val url =
                        dto.htmlUrl?.takeIf { GithubReleaseSource.isForkReleaseUrl(it) }
                            ?: return@withContext null
                    LatestRelease(tag, url)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // Message only (no response body) — the release payload is not sensitive, but keeping
                    // logs terse avoids noise; the check simply fails closed.
                    Logger.w(TAG, "Update check failed: ${e.message}")
                    null
                }
            }

        private fun buildClient(): HttpClient =
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                }
            }

        @Serializable
        private data class GithubReleaseDto(
            @SerialName("tag_name") val tagName: String? = null,
            @SerialName("html_url") val htmlUrl: String? = null,
        )

        companion object {
            private const val TAG = "MCP:UpdateChecker"
            private const val GITHUB_ACCEPT = "application/vnd.github+json"
            private const val USER_AGENT = "android-remote-control-mcp"
            private const val REQUEST_TIMEOUT_MS = 15_000L
            private const val CONNECT_TIMEOUT_MS = 10_000L
            private val json = Json { ignoreUnknownKeys = true }
        }
    }
