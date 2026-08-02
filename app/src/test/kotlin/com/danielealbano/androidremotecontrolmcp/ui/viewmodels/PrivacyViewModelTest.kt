package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.data.model.PlaceholderFormat
import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.data.model.RedactionMode
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyModeManager
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyModeStatus
import com.danielealbano.androidremotecontrolmcp.privacy.model.DownloadState
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("PrivacyViewModel")
class PrivacyViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var privacyModeManager: PrivacyModeManager
    private lateinit var configFlow: MutableStateFlow<ServerConfig>
    private lateinit var statusFlow: MutableStateFlow<PrivacyModeStatus>
    private lateinit var downloadStateFlow: MutableStateFlow<DownloadState>
    private lateinit var benchmarkFlow: MutableStateFlow<Double?>
    private lateinit var viewModel: PrivacyViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        configFlow = MutableStateFlow(ServerConfig())
        statusFlow = MutableStateFlow(PrivacyModeStatus.Disabled)
        downloadStateFlow = MutableStateFlow(DownloadState.Idle)
        benchmarkFlow = MutableStateFlow(null)

        settingsRepository = mockk(relaxed = true)
        every { settingsRepository.serverConfig } returns configFlow
        every { settingsRepository.privacyBenchmarkEstimateSeconds } returns benchmarkFlow

        privacyModeManager = mockk(relaxed = true)
        every { privacyModeManager.status } returns statusFlow
        every { privacyModeManager.downloadState } returns downloadStateFlow

        viewModel = PrivacyViewModel(settingsRepository, privacyModeManager, testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `privacyConfig reflects repository`() =
        runTest {
            viewModel.privacyConfig.test {
                assertEquals(PrivacyModeConfig(), awaitItem())
                configFlow.value = ServerConfig(privacyModeConfig = PrivacyModeConfig(enabled = true))
                assertEquals(PrivacyModeConfig(enabled = true), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `requestEnablePrivacyMode enables directly when model ready`() =
        runTest {
            every { privacyModeManager.isModelReady() } returns true
            viewModel.requestEnablePrivacyMode()
            advanceUntilIdle()
            coVerify(exactly = 1) { privacyModeManager.enableWithDownload() }
            assertEquals(false, viewModel.consentRequired.value)
        }

    @Test
    fun `requestEnablePrivacyMode requires consent when model missing`() =
        runTest {
            every { privacyModeManager.isModelReady() } returns false
            viewModel.requestEnablePrivacyMode()
            advanceUntilIdle()
            assertEquals(true, viewModel.consentRequired.value)
            coVerify(exactly = 0) { privacyModeManager.enableWithDownload() }
        }

    @Test
    fun `confirmDownloadAndEnable enables and clears consent`() =
        runTest {
            every { privacyModeManager.isModelReady() } returns false
            viewModel.requestEnablePrivacyMode()
            advanceUntilIdle()

            viewModel.confirmDownloadAndEnable()
            advanceUntilIdle()

            assertEquals(false, viewModel.consentRequired.value)
            coVerify(exactly = 1) { privacyModeManager.enableWithDownload() }
        }

    @Test
    fun `cancelConsent clears consent without enabling`() =
        runTest {
            every { privacyModeManager.isModelReady() } returns false
            viewModel.requestEnablePrivacyMode()
            advanceUntilIdle()

            viewModel.cancelConsent()

            assertEquals(false, viewModel.consentRequired.value)
            coVerify(exactly = 0) { privacyModeManager.enableWithDownload() }
        }

    @Test
    fun `disablePrivacyMode persists false`() =
        runTest {
            viewModel.disablePrivacyMode()
            advanceUntilIdle()
            coVerify { settingsRepository.updatePrivacyModeEnabled(false) }
            coVerify { privacyModeManager.selfCheck() }
        }

    @Test
    fun `category and mode updates delegate to repository`() =
        runTest {
            viewModel.updatePrivacyCategoryEnabled(PiiCategory.EMAILS, false)
            viewModel.updatePrivacyRedactionMode(RedactionMode.REDACT)
            viewModel.updatePrivacyPlaceholderFormat(PlaceholderFormat.NUMBERED)
            advanceUntilIdle()
            coVerify { settingsRepository.updatePrivacyCategoryEnabled(PiiCategory.EMAILS, false) }
            coVerify { settingsRepository.updatePrivacyRedactionMode(RedactionMode.REDACT) }
            coVerify { settingsRepository.updatePrivacyPlaceholderFormat(PlaceholderFormat.NUMBERED) }
        }

    @Test
    fun `privacyBenchmarkEstimate exposes stored value`() =
        runTest {
            viewModel.privacyBenchmarkEstimate.test {
                assertEquals(null, awaitItem())
                benchmarkFlow.value = 1.5
                assertEquals(1.5, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
}
