package com.maksimowiczm.foodyou.sync.domain

import com.maksimowiczm.foodyou.common.domain.date.DateProvider
import com.maksimowiczm.foodyou.common.domain.userpreferences.UserPreferencesRepository
import kotlinx.coroutines.sync.Mutex

/**
 * Runs a sync and records its outcome (last-sync time / error) for the settings UI. Both the manual
 * "sync now" action and the background worker go through here, so this is also the single point that
 * serializes overlapping triggers. Must be a singleton for the mutex to be shared.
 */
class SyncRunner(
    private val engine: SyncEngine,
    private val preferencesRepository: UserPreferencesRepository<SyncPreferences>,
    private val dateProvider: DateProvider,
) {
    private val mutex = Mutex()

    suspend fun runSync(): SyncResult {
        // A trigger arriving while a sync is in flight is skipped, not queued — avoids duplicate
        // uuids and cursor races between manual / periodic / on-foreground triggers.
        if (!mutex.tryLock()) return SyncResult.Skipped
        try {
            val result = engine.sync()
            preferencesRepository.update {
                when (result) {
                    is SyncResult.Success ->
                        copy(
                            lastSyncEpochSeconds = dateProvider.nowInstant().epochSeconds,
                            lastError = null,
                        )
                    is SyncResult.Failure -> copy(lastError = result.message)
                    SyncResult.Disabled,
                    SyncResult.Skipped -> this
                }
            }
            return result
        } finally {
            mutex.unlock()
        }
    }
}
