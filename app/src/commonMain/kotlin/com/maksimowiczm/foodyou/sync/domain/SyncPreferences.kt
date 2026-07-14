package com.maksimowiczm.foodyou.sync.domain

import com.maksimowiczm.foodyou.common.domain.userpreferences.UserPreferences

/**
 * Persisted sync configuration and protocol state.
 *
 * @property serverUrl Base URL of the self-hosted server (empty when unconfigured).
 * @property enabled Whether periodic/foreground sync is active.
 * @property cursor Last server `synced_at` returned by a pull; the next pull's `updated_since`.
 * @property lastSyncEpochSeconds When the last sync finished (for UI status).
 * @property lastError Message of the last failed sync, or null on success.
 * @property goalKcal/goalProteinG/goalCarbsG/goalFatG Last-synced goals snapshot for goal LWW.
 */
data class SyncPreferences(
    val serverUrl: String = "",
    val enabled: Boolean = false,
    val cursor: String? = null,
    val lastSyncEpochSeconds: Long? = null,
    val lastError: String? = null,
    val goalKcal: Double? = null,
    val goalProteinG: Double? = null,
    val goalCarbsG: Double? = null,
    val goalFatG: Double? = null,
) : UserPreferences
