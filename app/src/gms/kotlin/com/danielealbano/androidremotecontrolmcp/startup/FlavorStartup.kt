package com.danielealbano.androidremotecontrolmcp.startup

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.repository.GeofenceConfigRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface GeofenceMigrationEntryPoint {
    fun geofenceConfigRepository(): GeofenceConfigRepository
}

/**
 * gms flavor: runs the one-time geofence config migration eagerly at app startup, BEFORE any
 * Activity/Service/Receiver (hence any event-channel write) can run. Intentionally blocking: the
 * migration is bounded and guarded by a done-flag, so it is a fast no-op on subsequent launches.
 */
fun runFlavorStartupMigrations(context: Context) {
    val repository =
        EntryPointAccessors
            .fromApplication(context, GeofenceMigrationEntryPoint::class.java)
            .geofenceConfigRepository()
    runBlocking(Dispatchers.IO) { repository.migrateIfNeeded() }
}
