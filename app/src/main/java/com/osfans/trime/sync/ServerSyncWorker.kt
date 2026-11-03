/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.sync

import android.content.Context
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.prefs.AppPrefs
import timber.log.Timber
import java.util.concurrent.TimeUnit

/** Periodic sync with the self-hosted rime_server (manifest diff + user db exchange). */
class ServerSyncWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        try {
            Timber.i("Starting server sync ...")
            return doServerSync()
        } catch (e: Exception) {
            Timber.e(e, "Server sync job failed.")
            return Result.retry()
        }
    }

    private suspend fun doServerSync(): Result {
        if (!enabled) return Result.failure()
        if (!UserDataManager.isStorageAvailable()) {
            Timber.w("Server sync skipped: storage not available")
            return Result.retry()
        }
        val rime = RimeDaemon.createSession(javaClass.name)
        try {
            val result = ServerSyncRepository.sync(rime)
            lastStatus = 1
            lastDetail = result.detail()
            lastTime = System.currentTimeMillis()
            return Result.success()
        } catch (e: Exception) {
            lastStatus = 2
            lastDetail = e.message ?: e.javaClass.simpleName
            lastTime = System.currentTimeMillis()
            return Result.retry()
        } finally {
            RimeDaemon.destroySession(javaClass.name)
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "periodic_server_sync"

        private val prefs = AppPrefs.defaultInstance().network
        private val enabled by prefs.autoSync
        private val interval by prefs.autoSyncInterval
        private var lastStatus by prefs.lastSyncStatus
        private var lastDetail by prefs.lastSyncDetail
        private var lastTime by prefs.lastSyncTime

        fun start(context: Context) {
            internalStart(context, ExistingPeriodicWorkPolicy.UPDATE)
        }

        fun forceStart(context: Context) {
            internalStart(context, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
        }

        private fun internalStart(
            context: Context,
            policy: ExistingPeriodicWorkPolicy,
        ) {
            // The default WorkManager initializer is removed from the manifest; both this
            // and BackgroundSyncWork may run initialize first, so tolerate re-init.
            runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
            val instance = WorkManager.getInstance(context)
            if (!enabled) {
                instance.cancelUniqueWork(UNIQUE_WORK_NAME)
                Timber.i("ServerSyncWorker canceled!")
                return
            }
            val constraints =
                Constraints
                    .Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            val workRequest =
                PeriodicWorkRequestBuilder<ServerSyncWorker>(
                    interval.toLong(),
                    TimeUnit.MINUTES,
                    5,
                    TimeUnit.MINUTES,
                ).setConstraints(constraints)
                    .build()
            instance.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, policy, workRequest)
        }
    }
}
