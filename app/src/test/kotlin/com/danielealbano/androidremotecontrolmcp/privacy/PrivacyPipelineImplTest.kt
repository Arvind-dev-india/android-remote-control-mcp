package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.data.model.PlaceholderFormat
import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.CardDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.CredentialDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.EmailDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.IbanDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.NationalIdDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.PhoneDetector
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerEngine
import com.danielealbano.androidremotecontrolmcp.privacy.ner.PrivacyModelException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PrivacyPipelineImpl")
class PrivacyPipelineImplTest {
    private val manager = mockk<PrivacyModeManager>()
    private val nerEngine = mockk<NerEngine>()
    private val store = PseudonymStore()
    private val pipeline =
        PrivacyPipelineImpl(
            manager,
            DeterministicEngine(
                CredentialDetector(), CardDetector(), IbanDetector(),
                EmailDetector(), PhoneDetector(), NationalIdDetector(),
            ),
            nerEngine,
            ContextExtractor(),
            Redactor(store),
        )

    private fun configure(
        config: PrivacyModeConfig,
        status: PrivacyModeStatus,
    ) {
        coEvery { manager.currentConfig() } returns config
        every { manager.status } returns MutableStateFlow(status)
    }

    private fun node(
        id: String,
        text: String? = null,
        desc: String? = null,
        children: List<AccessibilityNodeData> = emptyList(),
    ) = AccessibilityNodeData(
        id = id,
        text = text,
        contentDescription = desc,
        bounds = BoundsData(0, 0, 10, 10),
        children = children,
    )

    private fun tree(root: AccessibilityNodeData) =
        MultiWindowResult(listOf(WindowData(windowId = 1, windowType = "APPLICATION", tree = root)))

    @Test
    fun `disabled config is identity`() =
        runTest {
            configure(PrivacyModeConfig(enabled = false), PrivacyModeStatus.Disabled)

            assertEquals("a@b.com", pipeline.processText("a@b.com", DetectionContext.EMPTY))
            coVerify(exactly = 0) { nerEngine.detect(any()) }
        }

    @Test
    fun `deterministic only when model categories off`() =
        runTest {
            val config =
                PrivacyModeConfig(
                    enabled = true,
                    disabledCategories = setOf(PiiCategory.NAMES, PiiCategory.ADDRESSES, PiiCategory.NATIONAL_IDS),
                    placeholderFormat = PlaceholderFormat.NUMBERED,
                )
            configure(config, PrivacyModeStatus.ReadyDeterministicOnly)

            val result = pipeline.processText("mail a@b.com", DetectionContext.forField("email"))

            assertEquals("mail [EMAIL_1]", result)
            coVerify(exactly = 0) { nerEngine.detect(any()) }
        }

    @Test
    fun `model detections merged and disabled category filtered`() =
        runTest {
            val config =
                PrivacyModeConfig(
                    enabled = true,
                    disabledCategories = setOf(PiiCategory.EMAILS),
                    placeholderFormat = PlaceholderFormat.NUMBERED,
                )
            configure(config, PrivacyModeStatus.Ready)
            coEvery { nerEngine.detect(any()) } returns
                mapOf("0" to listOf(PiiDetection(PiiCategory.NAMES, 0, 5, PiiDetection.Source.MODEL)))

            val result = pipeline.processText("Sarah a@b.com", DetectionContext.EMPTY)

            assertTrue(result.startsWith("[NAME_1]"), "name should be redacted: $result")
            assertTrue(result.contains("a@b.com"), "disabled EMAILS should not be redacted: $result")
        }

    @Test
    fun `structural wins overlap`() =
        runTest {
            configure(PrivacyModeConfig(enabled = true, placeholderFormat = PlaceholderFormat.NUMBERED), PrivacyModeStatus.Ready)
            coEvery { nerEngine.detect(any()) } returns
                mapOf("0" to listOf(PiiDetection(PiiCategory.NAMES, 0, 5, PiiDetection.Source.MODEL)))

            val result = pipeline.processText("Sarah", DetectionContext(isPassword = true))

            assertEquals("[CREDENTIAL_1]", result)
            assertFalse(result.contains("NAME"))
        }

    @Test
    fun `fail closed when model required and unavailable`() =
        runTest {
            configure(PrivacyModeConfig(enabled = true), PrivacyModeStatus.Unavailable("no model"))

            var thrown: Throwable? = null
            try {
                pipeline.processText("Sarah", DetectionContext.EMPTY)
            } catch (e: McpToolException.PrivacyModeUnavailable) {
                thrown = e
            }
            assertTrue(thrown is McpToolException.PrivacyModeUnavailable)
        }

    @Test
    fun `fail closed on inference exception`() =
        runTest {
            configure(PrivacyModeConfig(enabled = true), PrivacyModeStatus.Ready)
            coEvery { nerEngine.detect(any()) } throws PrivacyModelException("boom")

            var thrown: Throwable? = null
            try {
                pipeline.processText("Sarah", DetectionContext.EMPTY)
            } catch (e: McpToolException.PrivacyModeUnavailable) {
                thrown = e
            }
            assertTrue(thrown is McpToolException.PrivacyModeUnavailable)
        }

    @Test
    fun `processTree redacts text and desc and returns flagged bounds`() =
        runTest {
            val config = PrivacyModeConfig(enabled = true, disabledCategories = allExcept(PiiCategory.EMAILS))
            configure(config, PrivacyModeStatus.ReadyDeterministicOnly)
            val root =
                node(
                    "root",
                    children =
                        listOf(
                            node("n1", text = "a@b.com", desc = "mail c@d.com"),
                            node("n2", text = "hello world"),
                        ),
                )

            val processed = pipeline.processTree(tree(root))

            assertEquals(1, processed.flaggedBounds.size)
            coVerify(exactly = 0) { nerEngine.detect(any()) }
        }

    @Test
    fun `processTree packs all segments in one detect call`() =
        runTest {
            configure(PrivacyModeConfig(enabled = true), PrivacyModeStatus.Ready)
            coEvery { nerEngine.detect(any()) } returns emptyMap()
            val root =
                node(
                    "root",
                    children = listOf(node("n1", text = "one"), node("n2", text = "two"), node("n3", text = "three")),
                )

            pipeline.processTree(tree(root))

            coVerify(exactly = 1) { nerEngine.detect(any()) }
        }

    private fun allExcept(vararg keep: PiiCategory): Set<PiiCategory> = PiiCategory.entries.toSet() - keep.toSet()
}
