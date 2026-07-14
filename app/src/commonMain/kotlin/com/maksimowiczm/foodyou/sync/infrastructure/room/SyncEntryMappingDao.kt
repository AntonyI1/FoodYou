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

    @Upsert suspend fun upsert(mapping: SyncEntryMappingEntity)

    @Query("DELETE FROM SyncEntryMapping WHERE uuid = :uuid")
    suspend fun deleteByUuid(uuid: String)
}
