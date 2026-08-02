package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.PlaceholderFormat
import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.data.model.RedactionMode
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyModeManager
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyModeStatus
import com.danielealbano.androidremotecontrolmcp.privacy.model.DownloadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Backs the Privacy Mode settings screen and the Server-screen callout card. A DEDICATED ViewModel
 * (not [MainViewModel]) so the settings/config wiring stays isolated and the constructor stays small.
 */
@HiltViewModel
class PrivacyViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val privacyModeManager: PrivacyModeManager,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        val privacyConfig: StateFlow<PrivacyModeConfig> =
            settingsRepository.serverConfig
                .map { it.privacyModeConfig }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), PrivacyModeConfig())

        val privacyStatus: StateFlow<PrivacyModeStatus> = privacyModeManager.status

        val privacyDownloadState: StateFlow<DownloadState> = privacyModeManager.downloadState

        val privacyBenchmarkEstimate: StateFlow<Double?> =
            settingsRepository.privacyBenchmarkEstimateSeconds
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), null)

        private val mutableConsentRequired = MutableStateFlow(false)

        /** True when the master toggle was turned on but the model must be downloaded first (consent). */
        val consentRequired: StateFlow<Boolean> = mutableConsentRequired.asStateFlow()

        /**
         * Master-toggle ON entry point. Checks model readiness OFF the main thread; if ready, enables
         * immediately, otherwise raises [consentRequired] so the screen can show the download dialog.
         */
        fun requestEnablePrivacyMode() {
            viewModelScope.launch {
                val modelReady = withContext(ioDispatcher) { privacyModeManager.isModelReady() }
                if (modelReady) {
                    privacyModeManager.enableWithDownload()
                } else {
                    mutableConsentRequired.value = true
                }
            }
        }

        /** Consent dialog confirmed: clear the prompt and enable (downloading the model as part of it). */
        fun confirmDownloadAndEnable() {
            mutableConsentRequired.value = false
            viewModelScope.launch { privacyModeManager.enableWithDownload() }
        }

        /** Consent dialog dismissed: leave Privacy Mode off. */
        fun cancelConsent() {
            mutableConsentRequired.value = false
        }

        fun disablePrivacyMode() {
            viewModelScope.launch {
                settingsRepository.updatePrivacyModeEnabled(false)
                privacyModeManager.selfCheck()
            }
        }

        fun updatePrivacyCategoryEnabled(
            category: PiiCategory,
            enabled: Boolean,
        ) {
            viewModelScope.launch { settingsRepository.updatePrivacyCategoryEnabled(category, enabled) }
        }

        fun updatePrivacyRedactionMode(mode: RedactionMode) {
            viewModelScope.launch { settingsRepository.updatePrivacyRedactionMode(mode) }
        }

        fun updatePrivacyPlaceholderFormat(format: PlaceholderFormat) {
            viewModelScope.launch { settingsRepository.updatePrivacyPlaceholderFormat(format) }
        }

        private companion object {
            private const val FLOW_TIMEOUT_MS = 5_000L
        }
    }
