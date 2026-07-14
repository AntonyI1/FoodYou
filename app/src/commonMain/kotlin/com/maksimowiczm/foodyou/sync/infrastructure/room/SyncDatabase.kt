package com.maksimowiczm.foodyou.sync.infrastructure.room

interface SyncDatabase {
    val syncEntryMappingDao: SyncEntryMappingDao
    val syncReadDao: SyncReadDao
}
