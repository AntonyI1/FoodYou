package com.maksimowiczm.foodyou.sync.domain

/** Outcome of a single sync run. */
sealed interface SyncResult {
    /**
     * Sync completed; counts reflect entries pushed/pulled/deleted this run, plus catalog foods
     * pulled into "My Food" ([productsPulled]).
     */
    data class Success(
        val pushed: Int,
        val pulled: Int,
        val deleted: Int,
        val productsPulled: Int = 0,
    ) : SyncResult

    /**
     * Sync failed. [retryable] is true for transient causes (network, timeout, 5xx) so the worker
     * retries, and false for terminal ones (bad token, 4xx, malformed config) so it surfaces the
     * error instead of hammering the server.
     */
    data class Failure(val message: String, val retryable: Boolean) : SyncResult

    /** Sync is disabled or the server is unconfigured; nothing was attempted. */
    data object Disabled : SyncResult

    /** Another sync was already running; this trigger was skipped (no queueing). */
    data object Skipped : SyncResult
}
