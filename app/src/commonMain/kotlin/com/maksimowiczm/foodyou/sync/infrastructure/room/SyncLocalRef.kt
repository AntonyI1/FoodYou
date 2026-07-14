package com.maksimowiczm.foodyou.sync.infrastructure.room

/**
 * Lightweight projection of a local diary row id, used to enumerate what needs pushing. Change
 * detection is done by content hash, not row timestamps, so only the id is needed here.
 */
data class SyncLocalRef(val id: Long)
