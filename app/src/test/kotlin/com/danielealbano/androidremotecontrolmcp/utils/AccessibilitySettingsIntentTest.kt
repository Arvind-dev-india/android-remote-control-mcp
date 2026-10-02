package com.danielealbano.androidremotecontrolmcp.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AccessibilitySettingsIntentTest {
    private val context = mockk<Context>(relaxed = true)
    private val packageName = "com.yedhant.androidremotecontrolmcp"

    @BeforeEach
    fun setUp() {
        every { context.packageName } returns packageName
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setAction(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().setData(any()) } answers { self as Intent }
    }

    @AfterEach
    fun tearDown() {
        unmockkConstructor(Intent::class)
        unmockkStatic(Uri::class)
    }

    @Test
    fun `Enable opens accessibility details for the runtime package and original service class`() {
        PermissionUtils.openAccessibilitySettings(context, McpAccessibilityService::class.java)

        verify { anyConstructed<Intent>().setAction("android.settings.ACCESSIBILITY_DETAILS_SETTINGS") }
        verify {
            anyConstructed<Intent>().putExtra(
                "android.intent.extra.COMPONENT_NAME",
                "$packageName/${McpAccessibilityService::class.java.name}",
            )
        }
        verify { anyConstructed<Intent>().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        verify(exactly = 1) { context.startActivity(any()) }
    }

    @Test
    fun `unsupported OEM details action falls back to the accessibility list`() {
        every { context.startActivity(any()) } throws ActivityNotFoundException() andThen Unit

        PermissionUtils.openAccessibilitySettings(context, McpAccessibilityService::class.java)

        verify { anyConstructed<Intent>().setAction(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        verify(exactly = 2) { context.startActivity(any()) }
    }

    @Test
    fun `restricted OEM details action falls back to the accessibility list`() {
        every { context.startActivity(any()) } throws SecurityException() andThen Unit

        PermissionUtils.openAccessibilitySettings(context, McpAccessibilityService::class.java)

        verify { anyConstructed<Intent>().setAction(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        verify(exactly = 2) { context.startActivity(any()) }
    }

    @Test
    fun `restricted settings help opens app info for the runtime package`() {
        mockkStatic(Uri::class)
        val uri = mockk<Uri>()
        every { Uri.parse("package:$packageName") } returns uri
        every { context.startActivity(any()) } just Runs

        PermissionUtils.openAppInfo(context)

        verify { anyConstructed<Intent>().setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }
        verify { anyConstructed<Intent>().setData(uri) }
        verify { anyConstructed<Intent>().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        verify(exactly = 1) { context.startActivity(any()) }
    }
}
