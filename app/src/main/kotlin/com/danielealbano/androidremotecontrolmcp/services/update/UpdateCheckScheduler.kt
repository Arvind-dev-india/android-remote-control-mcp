package com.danielealbano.androidremotecontrolmcp.services.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Enqueues the periodic and on-open [UpdateCheckWorker] runs via WorkManager. */
object UpdateCheckScheduler {
    private const val PERIODIC_WORK_NAME = "update_check_periodic"
    private const val ON_OPEN_WORK_NAME = "update_check_on_open"
    private const val INTERVAL_HOURS = 2L

    /**
     * Schedules the recurring (~2h) background check. Uses [ExistingPeriodicWorkPolicy.KEEP] so an
     * already-scheduled chain survives app restarts and reboots without being reset.
     */
    fun schedulePeriodic(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(networkConstraints())
                .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Runs a one-off check when the app is opened. [ExistingWorkPolicy.KEEP] collapses rapid
     * foreground transitions into a single in-flight check.
     */
    fun checkOnOpen(context: Context) {
        val request =
            OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .setConstraints(networkConstraints())
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ON_OPEN_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun networkConstraints(): Constraints =
        Constraints
            .Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
}
