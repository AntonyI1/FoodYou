package com.maksimowiczm.foodyou.sync.infrastructure.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Maps a server catalog food (its UUID) to the local Product ("My Food") row it materialized as.
 *
 * Foods sync is one-way (server -> app): the app never pushes product changes, so unlike
 * [SyncEntryMappingEntity] there is no content hash — the server is authoritative. [serverUpdatedAt]
 * guards re-applies: a synced product is rewritten only when the server food advances past the last
 * applied timestamp, so a routine sync neither churns the row nor overwrites an in-app edit until the
 * server food genuinely changes.
 *
 * Like the entry mapping it is decoupled from the Product table (no foreign key) so the feature adds
 * no columns to upstream entities and stays rebasable.
 *
 * @property uuid Server food identity (UUID). Primary key.
 * @property localProductId Local Product.id this food materialized as. Unique.
 * @property serverUpdatedAt Epoch seconds of the last server `updated_at` we applied.
 */
@Entity(
    tableName = "SyncProductMapping",
    indices = [Index(value = ["localProductId"], unique = true)],
)
data class SyncProductMappingEntity(
    @PrimaryKey val uuid: String,
    val localProductId: Long,
    val serverUpdatedAt: Long,
)
