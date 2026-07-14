package com.maksimowiczm.foodyou.sync.infrastructure.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SyncEntryMappingDao {

    @Query("SELECT * FROM SyncEntryMapping WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): SyncEntryMappingEntity?

    @Query("SELECT * FROM SyncEntryMapping WHERE localTable = :localTable AND localId = :localId")
    suspend fun getByLocal(localTable: String, localId: Long): SyncEntryMappingEntity?

    @Query("SELECT * FROM SyncEntryMapping")
    suspend fun getAll(): List<SyncEntryMappingEntity>

    // Conflict resolution is by the uuid primary key only. To replace the mapping for a local row,
    // resolve its uuid via getByLocal(localTable, localId) first — upserting a fresh uuid for an
    // already-mapped row would leave a duplicate (the unique index on (localTable, localId) guards
    // against that at write time).
    @Upsert suspend fun upsert(mapping: SyncEntryMappingEntity)

    @Query("DELETE FROM SyncEntryMapping WHERE uuid = :uuid")
    suspend fun deleteByUuid(uuid: String)
}
