package com.maksimowiczm.foodyou.sync.infrastructure.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Maps a local diary entry to its stable server identity for self-hosted sync.
 *
 * The row is intentionally decoupled from the diary tables (no foreign key): the app hard-deletes
 * diary rows, so a mapping whose [localTable]/[localId] no longer resolves to a row is the signal to
 * push a tombstone to the server. Keeping the mapping separate also means the sync feature adds no
 * columns to upstream entities, so the schema change stays purely additive and upstream-rebasable.
 *
 * @property uuid Server-side identity (UUID v4). Primary key.
 * @property localTable Local table the entry lives in: "ManualDiaryEntry" or "Measurement".
 * @property localId Local auto-increment id within [localTable].
 * @property contentHash Canonical hash of the last synced content, to detect genuine local edits.
 *   NULL means the uuid was reserved before a push whose outcome is unknown — the entry is
 *   re-pushed next cycle under the same uuid (idempotent by the server's upsert-by-id).
 * @property serverUpdatedAt Epoch seconds of the last server `updated_at` we applied or pushed.
 */
@Entity(
    tableName = "SyncEntryMapping",
    indices = [Index(value = ["localTable", "localId"], unique = true)],
)
data class SyncEntryMappingEntity(
    @PrimaryKey val uuid: String,
    val localTable: String,
    val localId: Long,
    val contentHash: String?,
    val serverUpdatedAt: Long,
)
