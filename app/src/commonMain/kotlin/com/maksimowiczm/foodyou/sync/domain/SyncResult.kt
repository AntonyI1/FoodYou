package com.maksimowiczm.foodyou.sync.domain

/** Outcome of a single sync run. */
sealed interface SyncResult {
    /** Sync completed; counts reflect entries pushed/pulled/deleted this run. */
    data class Success(val pushed: Int, val pulled: Int, val deleted: Int) : SyncResult

    /** Sync failed; [message] is surfaced in the settings status line. */
    data class Failure(val message: String) : SyncResult

    /** Sync is disabled or the server is unconfigured; nothing was attempted. */
    data object Disabled : SyncResult
}
