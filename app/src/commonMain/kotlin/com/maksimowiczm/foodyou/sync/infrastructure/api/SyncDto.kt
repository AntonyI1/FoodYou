package com.maksimowiczm.foodyou.sync.infrastructure.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire model for the self-hosted server's `FoodEntry` (locked contract v1, DESIGN §3). Nutrients are
 * per-entry TOTALS, not per-100g. snake_case wire names via [SerialName].
 */
@Serializable
data class FoodEntryDto(
    val id: String? = null,
    val date: String,
    val meal: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val quantity: QuantityDto,
    val nutrients: NutrientsDto,
    val source: String,
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
)

@Serializable data class QuantityDto(val amount: Double, val unit: String)

@Serializable
data class NutrientsDto(
    val kcal: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("fiber_g") val fiberG: Double? = null,
    @SerialName("sugar_g") val sugarG: Double? = null,
    @SerialName("saturated_fat_g") val saturatedFatG: Double? = null,
    @SerialName("salt_g") val saltG: Double? = null,
)

/** Response of the sync pull: `GET /api/v1/entries?updated_since&include_deleted=true`. */
@Serializable
data class EntriesResponseDto(
    val entries: List<FoodEntryDto>,
    @SerialName("synced_at") val syncedAt: String,
)

/** Bulk push body: `POST /api/v1/entries`. */
@Serializable data class BulkEntriesDto(val entries: List<FoodEntryDto>)

/**
 * Wire model for a server catalog food ("My Food"), `GET /api/v1/foods`. Nutrients are PER 100 g/ml
 * (not totals). Foods sync is add/update-only — the server sends no tombstones, so there is no
 * `deleted` field.
 */
@Serializable
data class FoodDto(
    val id: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    @SerialName("per_100g") val per100g: NutrientsDto,
    @SerialName("serving_weight_g") val servingWeightG: Double? = null,
    @SerialName("package_weight_g") val packageWeightG: Double? = null,
    @SerialName("is_liquid") val isLiquid: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** Response of the foods sync pull: `GET /api/v1/foods?updated_since`. */
@Serializable
data class FoodsResponseDto(
    val foods: List<FoodDto>,
    @SerialName("synced_at") val syncedAt: String,
)

/**
 * Daily goals (single row), `GET/PUT /api/v1/goals`. All fields are nullable: the server returns
 * null for unset targets and set_goals is merge-semantics server-side, so partial rows are normal.
 */
@Serializable
data class GoalsDto(
    val kcal: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
) {
    /** True when every macro target is present — required to materialize a local DailyGoal. */
    val isComplete: Boolean
        get() = kcal != null && proteinG != null && carbsG != null && fatG != null

    /** True when no target is set — the server has never received goals. */
    val isEmpty: Boolean
        get() = kcal == null && proteinG == null && carbsG == null && fatG == null
}
