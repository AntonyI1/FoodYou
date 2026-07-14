package com.maksimowiczm.foodyou.sync.infrastructure.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** WorkManager scheduling for sync. 15-minute periodic floor + a coalesced "sync now" job. */
internal object SyncScheduling {
    private const val PERIODIC_WORK = "foodyou_sync_periodic"
    private const val ONE_TIME_WORK = "foodyou_sync_now"
    private const val SYNC_INTERVAL_MINUTES = 15L // WorkManager's periodic floor

    private val networkConstraint =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<SyncWorker>(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }

    fun syncNow(context: Context) {
        val request =
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(networkConstraint).build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.KEEP, request)
    }
}
