package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.PlaceholderFormat
import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.data.model.RedactionMode
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyModeManager
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyModeStatus
import com.danielealbano.androidremotecontrolmcp.privacy.model.DownloadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

        val privacyModelReady: Boolean
            get() = privacyModeManager.isModelReady()

        fun enablePrivacyMode() {
            viewModelScope.launch { privacyModeManager.enableWithDownload() }
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
