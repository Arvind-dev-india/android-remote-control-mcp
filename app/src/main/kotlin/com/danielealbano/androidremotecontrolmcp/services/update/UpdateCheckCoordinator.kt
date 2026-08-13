package com.danielealbano.androidremotecontrolmcp.services.update

import com.danielealbano.androidremotecontrolmcp.data.model.AvailableUpdate
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** What triggered an update check. Manual checks bypass the enabled toggle and never post a
 * notification (the user is already looking at the result in-app). */
enum class UpdateCheckTrigger {
    AUTOMATIC,
    MANUAL,
}

/** The result of an update check, surfaced to the UI for manual checks. */
sealed interface UpdateCheckOutcome {
    /** Automatic checks are disabled by the user and this was not a manual check. */
    data object Disabled : UpdateCheckOutcome

    /** The installed build is a local/dev build (or an unparseable version); checks are skipped. */
    data object DevBuild : UpdateCheckOutcome

    /** The check could not complete (offline, rate-limited, malformed response). */
    data object Failed : UpdateCheckOutcome

    /** The installed build is the latest. */
    data object UpToDate : UpdateCheckOutcome

    /** A newer release is available. */
    data class UpdateAvailable(
        val update: AvailableUpdate,
    ) : UpdateCheckOutcome
}

/**
 * Compares the installed version against the latest GitHub release and, when newer, persists it (so
 * the in-app banner can render) and — for automatic checks only, once per new version — posts a
 * notification. Idempotent and fail-closed: any failure yields [UpdateCheckOutcome.Failed] and
 * leaves persisted state untouched.
 */
@Singleton
class UpdateCheckCoordinator
    @Inject
    constructor(
        private val checker: GithubReleaseChecker,
        private val settingsRepository: SettingsRepository,
        private val notifier: UpdateNotifier,
        private val versionProvider: AppVersionProvider,
    ) {
        suspend fun check(trigger: UpdateCheckTrigger): UpdateCheckOutcome {
            val manual = trigger == UpdateCheckTrigger.MANUAL
            val currentRaw = versionProvider.versionName
            val current = SemanticVersion.parse(currentRaw)
            return when {
                !manual && !settingsRepository.autoUpdateCheckEnabled.first() -> UpdateCheckOutcome.Disabled
                current == null || currentRaw.contains(DEV_MARKER) -> UpdateCheckOutcome.DevBuild
                else -> evaluateAgainstLatest(current, manual)
            }
        }

        private suspend fun evaluateAgainstLatest(
            current: SemanticVersion,
            manual: Boolean,
        ): UpdateCheckOutcome {
            // A missing release OR an unparseable tag both mean "could not determine the latest" → Failed.
            val latest =
                checker.fetchLatestRelease()?.let { release ->
                    SemanticVersion.parse(release.tagName)?.let { version -> release to version }
                } ?: return UpdateCheckOutcome.Failed
            val (release, version) = latest
            return if (version <= current) {
                // Clear any stale persisted update (e.g. the user has since upgraded).
                settingsRepository.setAvailableUpdate(null)
                UpdateCheckOutcome.UpToDate
            } else {
                applyUpdate(AvailableUpdate(version.toCoreString(), release.htmlUrl), manual)
            }
        }

        private suspend fun applyUpdate(
            update: AvailableUpdate,
            manual: Boolean,
        ): UpdateCheckOutcome.UpdateAvailable {
            settingsRepository.setAvailableUpdate(update)
            // De-dup: surface a given version at most once. A notification is posted only for automatic
            // checks; a manual check shows the result in-app, so it records the version as "seen" WITHOUT
            // posting — suppressing a redundant notification for it on the next automatic run.
            if (settingsRepository.getNotifiedUpdateVersion() != update.versionName) {
                if (!manual) {
                    notifier.notifyUpdateAvailable(update.versionName, update.releaseUrl)
                }
                settingsRepository.setNotifiedUpdateVersion(update.versionName)
            }
            return UpdateCheckOutcome.UpdateAvailable(update)
        }

        companion object {
            // git-describe stamps builds after a tag with a `-dev.<n>` pre-release segment; those are
            // ahead of the tagged release and must never be prompted to "update" to that same tag.
            private const val DEV_MARKER = "-dev"
        }
    }
