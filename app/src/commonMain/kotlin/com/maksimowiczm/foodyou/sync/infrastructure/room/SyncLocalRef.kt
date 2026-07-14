package com.maksimowiczm.foodyou.sync.infrastructure.room

/**
 * Lightweight projection of a local diary row used to enumerate what needs pushing and to detect
 * changes without loading full entities.
 *
 * @property id Local row id.
 * @property updatedAt Epoch seconds the row was last modified locally.
 */
data class SyncLocalRef(val id: Long, val updatedAt: Long)
