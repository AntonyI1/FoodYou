package com.maksimowiczm.foodyou.sync.infrastructure.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SyncProductMappingDao {

    @Query("SELECT * FROM SyncProductMapping WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): SyncProductMappingEntity?

    // Foods sync is add/update-only, so there is no delete: a mapping is created on first pull and
    // its serverUpdatedAt advances on later updates.
    @Upsert suspend fun upsert(mapping: SyncProductMappingEntity)
}
