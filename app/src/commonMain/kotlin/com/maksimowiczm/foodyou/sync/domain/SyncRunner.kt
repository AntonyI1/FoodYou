package com.maksimowiczm.foodyou.sync.domain

import com.maksimowiczm.foodyou.common.domain.date.DateProvider
import com.maksimowiczm.foodyou.common.domain.userpreferences.UserPreferencesRepository

/**
 * Runs a sync and records its outcome (last-sync time / error) for the settings UI. Both the manual
 * "sync now" action and the background worker go through here so status is updated one way.
 */
class SyncRunner(
    private val engine: SyncEngine,
    private val preferencesRepository: UserPreferencesRepository<SyncPreferences>,
    private val dateProvider: DateProvider,
) {
    suspend fun runSync(): SyncResult {
        val result = engine.sync()
        preferencesRepository.update {
            when (result) {
                is SyncResult.Success ->
                    copy(
                        lastSyncEpochSeconds = dateProvider.nowInstant().epochSeconds,
                        lastError = null,
                    )
                is SyncResult.Failure -> copy(lastError = result.message)
                SyncResult.Disabled -> this
            }
        }
        return result
    }
}
