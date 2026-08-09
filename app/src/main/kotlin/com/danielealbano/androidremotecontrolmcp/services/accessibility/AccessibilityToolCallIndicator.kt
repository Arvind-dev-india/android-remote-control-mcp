package com.danielealbano.androidremotecontrolmcp.services.accessibility

import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolCallIndicator

/** Displays a touch-through accessibility overlay while an MCP tool is executing. */
class AccessibilityToolCallIndicator : ToolCallIndicator {
    override fun onToolCallStarted(toolName: String) {
        McpAccessibilityService.showToolCallIndicator(toolName)
    }

    override fun onToolCallFinished(toolName: String) {
        McpAccessibilityService.hideToolCallIndicator()
    }
}
