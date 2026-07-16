package com.danielealbano.androidremotecontrolmcp.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ToolPermissionsConfig")
class ToolPermissionsConfigTest {
    @Test
    fun `default profile enables core control tools`() {
        val config = ToolPermissionsConfig()
        assertTrue(config.isToolEnabled("tap"))
        assertTrue(config.isToolEnabled("take_camera_photo"))
        assertTrue(config.isToolEnabled("get_location"))
        assertFalse(config.isToolEnabled("read_file"))
    }

    @Test
    fun `isToolEnabled returns false when tool is not allowlisted`() {
        val config = ToolPermissionsConfig(enabledTools = setOf("swipe"))
        assertFalse(config.isToolEnabled("tap"))
    }

    @Test
    fun `isToolEnabled returns false for unknown tool names`() {
        val config = ToolPermissionsConfig()
        assertFalse(config.isToolEnabled("nonexistent_tool"))
    }

    @Test
    fun `isParamEnabled returns true for empty disabledParams`() {
        val config = ToolPermissionsConfig()
        assertTrue(config.isParamEnabled("get_screen_state", "include_screenshot"))
    }

    @Test
    fun `isParamEnabled returns false when param is in disabledParams`() {
        val config =
            ToolPermissionsConfig(
                disabledParams = mapOf("get_screen_state" to setOf("include_screenshot")),
            )
        assertFalse(config.isParamEnabled("get_screen_state", "include_screenshot"))
    }

    @Test
    fun `isParamEnabled returns true when tool has no entry`() {
        val config =
            ToolPermissionsConfig(
                disabledParams = mapOf("get_screen_state" to setOf("include_screenshot")),
            )
        assertTrue(config.isParamEnabled("save_camera_video", "audio"))
    }

    @Test
    fun `toJson with empty allowlist produces expected JSON`() {
        val json = ToolPermissionsConfig(enabledTools = emptySet()).toJson()
        assertEquals("{\"enabledTools\":[],\"disabledParams\":{}}", json)
    }

    @Test
    fun `toJson and fromJson round-trip`() {
        val original =
            ToolPermissionsConfig(
                enabledTools = setOf("tap", "swipe"),
                disabledParams = mapOf("get_screen_state" to setOf("include_screenshot")),
            )
        val json = original.toJson()
        val restored = ToolPermissionsConfig.fromJson(json)
        assertNotNull(restored)
        assertEquals(original, restored)
    }

    @Test
    fun `fromJson with empty JSON object`() {
        val config = ToolPermissionsConfig.fromJson("{}")
        assertNotNull(config)
        assertEquals(ToolPermissionsConfig(), config)
    }

    @Test
    fun `fromJson with valid JSON`() {
        val json =
            """{"enabledTools":["tap","swipe"],"disabledParams":{"get_screen_state":["include_screenshot"]}}"""
        val config = ToolPermissionsConfig.fromJson(json)
        assertNotNull(config)
        assertEquals(setOf("tap", "swipe"), config!!.enabledTools)
        assertEquals(
            mapOf("get_screen_state" to setOf("include_screenshot")),
            config.disabledParams,
        )
    }

    @Test
    fun `fromJson with invalid JSON returns null`() {
        assertNull(ToolPermissionsConfig.fromJson("not json"))
    }

    @Test
    fun `fromJsonOrDefault with null returns default`() {
        val config = ToolPermissionsConfig.fromJsonOrDefault(null)
        assertEquals(ToolPermissionsConfig(), config)
    }

    @Test
    fun `fromJsonOrDefault with invalid JSON returns default`() {
        val config = ToolPermissionsConfig.fromJsonOrDefault("not json")
        assertEquals(ToolPermissionsConfig(), config)
    }

    @Test
    fun `fromJson with unknown extra JSON keys`() {
        val json =
            """{"enabledTools":["tap"],"disabledParams":{},"unknownKey":"value"}"""
        val config = ToolPermissionsConfig.fromJson(json)
        assertNotNull(config)
        assertEquals(setOf("tap"), config!!.enabledTools)
    }

    @Test
    fun `fromJson with partially valid JSON`() {
        val json = """{"enabledTools":["tap"],"disabledParams":"not_an_object"}"""
        val config = ToolPermissionsConfig.fromJson(json)
        assertNull(config)
    }

    @Test
    fun `legacy disabledTools JSON migrates to an allowlist`() {
        val config = ToolPermissionsConfig.fromJson("""{"disabledTools":["tap"],"disabledParams":{}}""")
        assertNotNull(config)
        assertFalse(config!!.isToolEnabled("tap"))
        assertTrue(config.isToolEnabled("swipe"))
        assertFalse(config.isToolEnabled("future_unknown_tool"))
    }
}
