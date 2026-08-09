package com.danielealbano.androidremotecontrolmcp.mcp.tools

import java.util.concurrent.atomic.AtomicInteger

/** Keeps an indicator visible until every overlapping tool invocation has completed. */
class ReferenceCountedToolCallIndicator(
    private val delegate: ToolCallIndicator,
) : ToolCallIndicator {
    private val activeCalls = AtomicInteger(0)

    override fun onToolCallStarted(toolName: String) {
        activeCalls.incrementAndGet()
        delegate.onToolCallStarted(toolName)
    }

    override fun onToolCallFinished(toolName: String) {
        val remaining = activeCalls.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        if (remaining == 0) {
            delegate.onToolCallFinished(toolName)
        }
    }
}
