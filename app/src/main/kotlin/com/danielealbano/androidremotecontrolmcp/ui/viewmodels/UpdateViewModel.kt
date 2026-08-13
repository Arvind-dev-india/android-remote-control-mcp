package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.AvailableUpdate
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.update.AppVersionProvider
import com.danielealbano.androidremotecontrolmcp.services.update.UpdateCheckCoordinator
import com.danielealbano.androidremotecontrolmcp.services.update.UpdateCheckOutcome
import com.danielealbano.androidremotecontrolmcp.services.update.UpdateCheckTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Transient result of a manual "Check for updates" action, shown next to the button. */
sealed interface UpdateCheckUiState {
    data object Idle : UpdateCheckUiState

    data object Checking : UpdateCheckUiState

    data object UpToDate : UpdateCheckUiState

    data object Failed : UpdateCheckUiState

    data object DevBuild : UpdateCheckUiState

    data class UpdateFound(
        val update: AvailableUpdate,
    ) : UpdateCheckUiState
}

/**
 * Backs the in-app update banner and the About-screen update controls. Shared across the banner and
 * the settings section because `hiltViewModel()` returns the same activity-scoped instance.
 */
@HiltViewModel
class UpdateViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val coordinator: UpdateCheckCoordinator,
        versionProvider: AppVersionProvider,
    ) : ViewModel() {
        val currentVersion: String = versionProvider.versionName

        val availableUpdate: StateFlow<AvailableUpdate?> =
            settingsRepository.availableUpdate.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                null,
            )

        val autoCheckEnabled: StateFlow<Boolean> =
            settingsRepository.autoUpdateCheckEnabled.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                true,
            )

        private val _checkState = MutableStateFlow<UpdateCheckUiState>(UpdateCheckUiState.Idle)
        val checkState: StateFlow<UpdateCheckUiState> = _checkState.asStateFlow()

        fun setAutoCheckEnabled(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.updateAutoUpdateCheckEnabled(enabled) }
        }

        fun checkNow() {
            if (_checkState.value == UpdateCheckUiState.Checking) return
            viewModelScope.launch {
                _checkState.value = UpdateCheckUiState.Checking
                _checkState.value =
                    when (val outcome = coordinator.check(UpdateCheckTrigger.MANUAL)) {
                        is UpdateCheckOutcome.UpdateAvailable -> UpdateCheckUiState.UpdateFound(outcome.update)

                        UpdateCheckOutcome.UpToDate -> UpdateCheckUiState.UpToDate

                        UpdateCheckOutcome.Failed -> UpdateCheckUiState.Failed

                        UpdateCheckOutcome.DevBuild -> UpdateCheckUiState.DevBuild

                        // A manual check bypasses the enabled toggle, so Disabled is never returned here.
                        UpdateCheckOutcome.Disabled -> UpdateCheckUiState.Idle
                    }
            }
        }

        companion object {
            private const val STOP_TIMEOUT_MS = 5_000L
        }
    }
