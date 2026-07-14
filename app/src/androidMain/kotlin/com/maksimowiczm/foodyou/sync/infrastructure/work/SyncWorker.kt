package com.maksimowiczm.foodyou.sync.infrastructure.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.maksimowiczm.foodyou.sync.domain.SyncResult
import com.maksimowiczm.foodyou.sync.domain.SyncRunner
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Background sync entry point (periodic + on-foreground). Polling only — no GMS/FCM. */
internal class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), KoinComponent {

    private val syncRunner: SyncRunner by inject()

    override suspend fun doWork(): Result =
        when (val result = syncRunner.runSync()) {
            // Retry transient failures (network/timeout/5xx); surface terminal ones (bad token, 4xx)
            // via lastSyncError instead of hammering the server.
            is SyncResult.Failure -> if (result.retryable) Result.retry() else Result.failure()
            else -> Result.success()
        }
}
