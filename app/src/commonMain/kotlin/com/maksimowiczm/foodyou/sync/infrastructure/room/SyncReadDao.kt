package com.maksimowiczm.foodyou.sync.infrastructure.room

import androidx.room.Dao
import androidx.room.Query

/**
 * Read-only enumeration of the existing diary tables for sync push. Upstream diary DAOs only expose
 * (mealId, date)-scoped queries, so sync needs its own bulk id+timestamp reads. This DAO never
 * writes and adds no columns to the diary tables.
 */
@Dao
interface SyncReadDao {

    @Query("SELECT id FROM ManualDiaryEntry")
    suspend fun manualEntryRefs(): List<SyncLocalRef>

    @Query("SELECT id FROM Measurement")
    suspend fun measurementRefs(): List<SyncLocalRef>
}
