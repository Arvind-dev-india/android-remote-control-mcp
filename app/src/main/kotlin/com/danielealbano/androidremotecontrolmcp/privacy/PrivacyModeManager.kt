package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.privacy.model.DownloadState
import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelDownloader
import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelStore
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerSegment
import com.danielealbano.androidremotecontrolmcp.privacy.ner.OrtPiiModelRunner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates Privacy Mode readiness: computes the [PrivacyModeStatus], drives the consent-gated model
 * download + warm-up, runs the first-time benchmark estimate, and owns model shutdown. The readiness
 * methods touch the filesystem ([PrivacyModelStore.isReady]) and are dispatched to [ioDispatcher] so
 * they never run disk I/O on a UI-thread caller.
 */
@Singleton
class PrivacyModeManager
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val store: PrivacyModelStore,
        private val downloader: PrivacyModelDownloader,
        private val runner: OrtPiiModelRunner,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val mutableStatus = MutableStateFlow<PrivacyModeStatus>(PrivacyModeStatus.Disabled)
        val status: StateFlow<PrivacyModeStatus> = mutableStatus.asStateFlow()

        val downloadState: StateFlow<DownloadState> get() = downloader.state

        suspend fun currentConfig(): PrivacyModeConfig = settingsRepository.getServerConfig().privacyModeConfig

        suspend fun isModelReady(): Boolean = withContext(ioDispatcher) { store.isReady() }

        suspend fun selfCheck(): PrivacyModeStatus =
            withContext(ioDispatcher) {
                val config = currentConfig()
                val status =
                    when {
                        !config.enabled -> PrivacyModeStatus.Disabled
                        !config.modelRequired() -> PrivacyModeStatus.ReadyDeterministicOnly
                        !store.isReady() -> PrivacyModeStatus.Unavailable("detection model not downloaded")
                        runner.warmUp().isSuccess -> PrivacyModeStatus.Ready
                        else -> PrivacyModeStatus.Unavailable("detection model failed to load")
                    }
                mutableStatus.value = status
                status
            }

        suspend fun enableWithDownload(): Result<PrivacyModeStatus> =
            withContext(ioDispatcher) {
                settingsRepository.updatePrivacyModeEnabled(true)
                val config = currentConfig()
                if (config.modelRequired() && !store.isReady()) {
                    val download = downloader.download()
                    if (download.isFailure) {
                        val status =
                            PrivacyModeStatus.Unavailable(
                                download.exceptionOrNull()?.message ?: "model download failed",
                            )
                        mutableStatus.value = status
                        return@withContext Result.success(status)
                    }
                }
                val status = selfCheck()
                if (status is PrivacyModeStatus.Ready) {
                    val neverBenchmarked = settingsRepository.privacyBenchmarkEstimateSeconds.first() == null
                    if (neverBenchmarked) benchmark()
                }
                Result.success(status)
            }

        suspend fun benchmark(): Double {
            val segments =
                (0 until BENCHMARK_NODES).map { index ->
                    val (context, value) = BENCHMARK_CORPUS[index % BENCHMARK_CORPUS.size]
                    NerSegment("bench-$index", context, value)
                }
            val timingsMs =
                (1..BENCHMARK_RUNS).map {
                    val start = System.nanoTime()
                    runner.infer(segments)
                    (System.nanoTime() - start) / NANOS_PER_MILLI
                }
            val medianSeconds = timingsMs.sorted()[timingsMs.size / 2] / MILLIS_PER_SECOND
            settingsRepository.updatePrivacyBenchmarkEstimateSeconds(medianSeconds)
            return medianSeconds
        }

        fun shutdown() {
            runner.close()
        }

        companion object {
            private const val BENCHMARK_NODES = 100
            private const val BENCHMARK_RUNS = 3
            private const val NANOS_PER_MILLI = 1_000_000.0
            private const val MILLIS_PER_SECOND = 1_000.0

            private val BENCHMARK_CORPUS =
                listOf(
                    "Full name" to "Jonathan Michael Anderson",
                    "Email" to "jonathan.anderson@example.com",
                    "Phone" to "+1 (415) 555-0182",
                    "Home address" to "742 Evergreen Terrace, Springfield",
                    "Card number" to "4111 1111 1111 1111",
                    "SSN" to "078-05-1120",
                    "City" to "San Francisco",
                    "Contact" to "Maria Garcia Lopez",
                    "Message" to "Please call me back tomorrow afternoon",
                    "Note" to "Meeting rescheduled to next week Tuesday",
                )
        }
    }
