package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ToolPermissionsConfig(
    val enabledTools: Set<String> = DEFAULT_ENABLED_TOOLS,
    val disabledParams: Map<String, Set<String>> = emptyMap(),
) {
    fun isToolEnabled(toolName: String): Boolean = toolName in enabledTools

    val disabledTools: Set<String>
        get() = ALL_SUPPORTED_TOOLS - enabledTools

    fun isParamEnabled(
        toolName: String,
        paramName: String,
    ): Boolean = paramName !in (disabledParams[toolName] ?: emptySet())

    fun toJson(): String =
        buildJsonObject {
            put("enabledTools", buildJsonArray { enabledTools.forEach { add(it) } })
            put(
                "disabledParams",
                buildJsonObject {
                    disabledParams.forEach { (tool, params) ->
                        put(tool, buildJsonArray { params.forEach { add(it) } })
                    }
                },
            )
        }.toString()

    companion object {
        fun fromJson(json: String): ToolPermissionsConfig? =
            try {
                val obj = Json.parseToJsonElement(json).jsonObject
                val enabledTools =
                    when {
                        obj["enabledTools"] != null -> {
                            obj
                                .getValue("enabledTools")
                                .jsonArray
                                .mapNotNull { it.jsonPrimitive.contentOrNull }
                                .toSet()
                        }

                        obj["disabledTools"] != null -> {
                            ALL_SUPPORTED_TOOLS -
                                obj
                                    .getValue("disabledTools")
                                    .jsonArray
                                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                                    .toSet()
                        }

                        else -> {
                            DEFAULT_ENABLED_TOOLS
                        }
                    }
                val disabledParams =
                    obj["disabledParams"]
                        ?.jsonObject
                        ?.mapValues { (_, v) ->
                            v.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                        }
                        ?: emptyMap()
                ToolPermissionsConfig(enabledTools = enabledTools, disabledParams = disabledParams)
            } catch (_: kotlinx.serialization.SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }

        fun fromJsonOrDefault(json: String?): ToolPermissionsConfig =
            if (json == null) ToolPermissionsConfig() else fromJson(json) ?: ToolPermissionsConfig()

        val FILE_OPERATION_TOOLS: Set<String> =
            setOf(
                "list_storage_locations",
                "list_files",
                "read_file",
                "write_file",
                "append_file",
                "file_replace",
                "download_from_url",
                "delete_file",
            )

        val DEFAULT_ENABLED_TOOLS: Set<String> =
            setOf(
                "get_screen_state",
                "press_back",
                "press_home",
                "open_notifications",
                "dismiss_keyboard",
                "tap",
                "swipe",
                "scroll",
                "find_nodes",
                "click_node",
                "tap_node",
                "scroll_to_node",
                "type_append_text",
                "type_insert_text",
                "type_replace_text",
                "type_clear_text",
                "press_key",
                "get_clipboard",
                "set_clipboard",
                "wait_for_node",
                "wait_for_idle",
                "get_node_details",
                "open_app",
                "list_apps",
                "list_cameras",
                "list_camera_photo_resolutions",
                "take_camera_photo",
                "notification_list",
                "notification_open",
                "notification_dismiss",
                "get_location",
            ) + FILE_OPERATION_TOOLS

        val ALL_SUPPORTED_TOOLS: Set<String> =
            setOf(
                "get_screen_state",
                "press_back",
                "press_home",
                "press_recents",
                "open_notifications",
                "open_quick_settings",
                "dismiss_keyboard",
                "get_device_logs",
                "tap",
                "long_press",
                "double_tap",
                "swipe",
                "scroll",
                "pinch",
                "custom_gesture",
                "find_nodes",
                "click_node",
                "long_click_node",
                "tap_node",
                "scroll_to_node",
                "type_append_text",
                "type_insert_text",
                "type_replace_text",
                "type_clear_text",
                "press_key",
                "get_clipboard",
                "set_clipboard",
                "wait_for_node",
                "wait_for_idle",
                "get_node_details",
                "open_app",
                "list_apps",
                "close_app",
                "list_cameras",
                "list_camera_photo_resolutions",
                "list_camera_video_resolutions",
                "take_camera_photo",
                "save_camera_photo",
                "save_camera_video",
                "send_intent",
                "open_uri",
                "notification_list",
                "notification_open",
                "notification_dismiss",
                "notification_snooze",
                "notification_action",
                "notification_reply",
                "get_location",
                "get_shared_content",
                "share_file_via_web",
            ) + FILE_OPERATION_TOOLS
    }
}
