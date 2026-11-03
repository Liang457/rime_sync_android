/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.worker

import android.content.Context
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.sync.ServerSyncRepository
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * The only periodic sync job: rime user data sync first, then the server sync
 * rides on the same cycle when a server is configured (M2 plan: one scheduler).
 */
class BackgroundSyncWork(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        try {
            Timber.i("Starting background sync ...")
            return doBackgroundSync()
        } catch (e: Exception) {
            Timber.e(e, "Background sync job failed.")
            return Result.retry()
        }
    }

    private suspend fun doBackgroundSync(): Result {
        if (!enable) {
            return Result.failure()
        }
        if (!UserDataManager.isStorageAvailable()) {
            Timber.w("Background sync skipped: storage not available")
            return Result.retry()
        }
        val rime = RimeDaemon.createSession(javaClass.name)
        try {
            lastSyncStatus = rime.runOnReady { syncUserData() }
            lastSyncTime = System.currentTimeMillis()
            // The server sync needs a successful local sync for its snapshot/merge
            // steps; its outcome is recorded on the server sync page without
            // failing the local sync round.
            if (lastSyncStatus && serverConfigured) {
                runCatching { ServerSyncRepository.sync(rime) }
                    .onSuccess { Timber.i("Server sync ok: %s", it.detail()) }
                    .onFailure { e ->
                        networkPrefs.lastSyncStatus.setValue(2)
                        networkPrefs.lastSyncDetail.setValue(e.message ?: e.javaClass.simpleName)
                        networkPrefs.lastSyncTime.setValue(System.currentTimeMillis())
                        Timber.e(e, "Server sync failed.")
                    }
            }
            return if (lastSyncStatus) Result.success() else Result.retry()
        } finally {
            RimeDaemon.destroySession(javaClass.name)
        }
    }

    companion object {
        private const val PERIODIC_BACKGROUND_SYNC_KEY = "periodic_background_sync"

        /** Former standalone server sync job; cancel leftovers from older builds. */
        private const val SUPERSEDED_SERVER_SYNC_KEY = "periodic_server_sync"

        private val prefs = AppPrefs.defaultInstance().profile
        private val networkPrefs = AppPrefs.defaultInstance().network
        private val enable by prefs.periodicBackgroundSync
        private val interval by prefs.periodicBackgroundSyncInterval
        private val allowMetered by prefs.periodicBackgroundSyncAllowMetered
        private val serverUrl by networkPrefs.serverUrl
        private var lastSyncStatus by prefs.lastBackgroundSyncStatus
        private var lastSyncTime by prefs.lastBackgroundSyncTime

        private val serverConfigured: Boolean
            get() = serverUrl.trim().isNotEmpty()

        fun start(context: Context) {
            Timber.i("BackgroundSyncWork scheduled!")
            internalStart(context, ExistingPeriodicWorkPolicy.UPDATE)
        }

        fun forceStart(context: Context) {
            internalStart(context, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
        }

        private fun internalStart(
            context: Context,
            policy: ExistingPeriodicWorkPolicy,
        ) {
            // The default WorkManager initializer is removed from the manifest; other
            // callers may have initialized it already, so tolerate re-init.
            runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
            val instance = WorkManager.getInstance(context)
            instance.cancelUniqueWork(SUPERSEDED_SERVER_SYNC_KEY)
            if (!enable) {
                instance.cancelUniqueWork(PERIODIC_BACKGROUND_SYNC_KEY)
                Timber.i("BackgroundSyncWork canceled!")
                return
            }
            val constraints =
                Constraints
                    .Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .setRequiredNetworkType(
                        when {
                            // No network use at all without a configured server.
                            !serverConfigured -> NetworkType.NOT_REQUIRED
                            allowMetered -> NetworkType.CONNECTED
                            else -> NetworkType.UNMETERED
                        },
                    ).build()

            val workRequest =
                PeriodicWorkRequestBuilder<BackgroundSyncWork>(
                    interval.toLong(),
                    TimeUnit.MINUTES,
                    5,
                    TimeUnit.MINUTES,
                ).setConstraints(constraints)
                    .build()
            instance.enqueueUniquePeriodicWork(
                PERIODIC_BACKGROUND_SYNC_KEY,
                policy,
                workRequest,
            )
        }
    }
}
