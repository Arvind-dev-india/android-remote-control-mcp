package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("loggedToolHandler")
class LoggedToolHandlerTest {
    private val request = mockk<CallToolRequest>()

    @Test
    fun `success logs empty message with duration`() =
        runTest {
            val recorder = RecordingServerLogRepository()
            val handler =
                loggedToolHandler(recorder, "tap") {
                    CallToolResult(content = listOf(TextContent(text = "ok")))
                }

            handler(request)

            val entry = recorder.ofType(ServerLogEntry.Type.TOOL_CALL).single()
            assertEquals("", entry.message)
            assertEquals("tap", entry.toolName)
            assertTrue((entry.durationMs ?: -1L) >= 0L)
        }

    @Test
    fun `isError result logs the constant failed marker`() =
        runTest {
            val recorder = RecordingServerLogRepository()
            val handler =
                loggedToolHandler(recorder, "tap") {
                    CallToolResult(content = listOf(TextContent(text = "secret 500 detail")), isError = true)
                }

            handler(request)

            val entry = recorder.ofType(ServerLogEntry.Type.TOOL_CALL).single()
            assertEquals("failed", entry.message)
            assertTrue(!entry.message.contains("500"))
        }

    @Test
    fun `thrown McpToolException logs failed marker and propagates`() =
        runTest {
            val recorder = RecordingServerLogRepository()
            val handler =
                loggedToolHandler(recorder, "tap") {
                    throw McpToolException.InvalidParams("boom 500")
                }

            val error = runCatching { handler(request) }.exceptionOrNull()

            assertTrue(error is McpToolException.InvalidParams)
            val entry = recorder.ofType(ServerLogEntry.Type.TOOL_CALL).single()
            assertEquals("failed", entry.message)
            assertTrue(!entry.message.contains("500"))
        }

    @Test
    fun `cancelled invocation records no entry`() =
        runTest {
            val recorder = RecordingServerLogRepository()
            val handler =
                loggedToolHandler(recorder, "tap") {
                    delay(10_000)
                    CallToolResult(content = listOf(TextContent(text = "late")))
                }

            val job = launch { handler(request) }
            advanceTimeBy(100)
            job.cancelAndJoin()

            assertTrue(recorder.ofType(ServerLogEntry.Type.TOOL_CALL).isEmpty())
        }
}
