package com.maksimowiczm.foodyou.sync.infrastructure.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Update
import com.maksimowiczm.foodyou.food.infrastructure.room.ProductEntity

/**
 * Write access to the Product table ("My Food") for the foods sync. Kept in the sync package (like
 * [SyncReadDao]) so the feature adds no methods to upstream's ProductDao and stays rebasable. Only
 * insert + update: v1 foods sync is add/update-only, with no deletes. The Product FTS index is kept
 * current by upstream's SQL triggers, so raw writes here stay searchable.
 */
@Dao
interface SyncProductDao {

    @Insert suspend fun insert(product: ProductEntity): Long

    @Update suspend fun update(product: ProductEntity)
}
