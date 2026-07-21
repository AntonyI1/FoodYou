package com.maksimowiczm.foodyou.sync.infrastructure

import com.maksimowiczm.foodyou.common.domain.measurement.Measurement
import com.maksimowiczm.foodyou.common.infrastructure.room.FoodSourceType
import com.maksimowiczm.foodyou.common.infrastructure.room.Minerals
import com.maksimowiczm.foodyou.common.infrastructure.room.Nutrients
import com.maksimowiczm.foodyou.common.infrastructure.room.Vitamins
import com.maksimowiczm.foodyou.food.infrastructure.room.ProductEntity
import com.maksimowiczm.foodyou.fooddiary.domain.entity.DiaryEntry
import com.maksimowiczm.foodyou.fooddiary.domain.entity.FoodDiaryEntry
import com.maksimowiczm.foodyou.fooddiary.domain.entity.ManualDiaryEntry
import com.maksimowiczm.foodyou.fooddiary.infrastructure.room.ManualDiaryEntryEntity
import com.maksimowiczm.foodyou.goals.domain.entity.MacronutrientGoal
import com.maksimowiczm.foodyou.sync.infrastructure.api.FoodDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.FoodEntryDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.GoalsDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.NutrientsDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.QuantityDto
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pure, deterministic conversions between the app's diary/goals model and the server's `FoodEntry`
 * totals model. No IO. [timeZone] is injectable so local-datetime <-> instant conversion is
 * deterministic in tests.
 */
class SyncMapper(private val timeZone: TimeZone = TimeZone.currentSystemDefault()) {

    /** Local diary entry (totals already computed by the domain) -> server DTO for push. */
    fun toDto(entry: DiaryEntry, mealName: String, uuid: String?): FoodEntryDto {
        val quantity =
            when (entry) {
                is FoodDiaryEntry -> entry.measurement.toQuantityDto()
                is ManualDiaryEntry -> QuantityDto(1.0, UNIT_SERVING)
            }
        val nf = entry.nutritionFacts
        return FoodEntryDto(
            id = uuid,
            date = entry.date.toString(),
            meal = mealName,
            name = entry.name,
            quantity = quantity,
            nutrients =
                NutrientsDto(
                    kcal = nf.energy.value,
                    proteinG = nf.proteins.value,
                    carbsG = nf.carbohydrates.value,
                    fatG = nf.fats.value,
                    fiberG = nf.dietaryFiber.value,
                    sugarG = nf.sugars.value,
                    saturatedFatG = nf.saturatedFats.value,
                    saltG = nf.salt.value,
                ),
            source = SOURCE_APP,
            createdAt = entry.createdAt.toInstant(timeZone).toString(),
            updatedAt = entry.updatedAt.toInstant(timeZone).toString(),
            deleted = false,
        )
    }

    /** Server DTO -> a manual diary row. Server-originated entries always materialize as manual. */
    fun toManualEntity(dto: FoodEntryDto, mealId: Long, localId: Long = 0): ManualDiaryEntryEntity {
        val n = dto.nutrients
        return ManualDiaryEntryEntity(
            id = localId,
            mealId = mealId,
            dateEpochDay = LocalDate.parse(dto.date).toEpochDays(),
            name = dto.name,
            nutrients =
                Nutrients(
                    energy = n.kcal,
                    proteins = n.proteinG,
                    fats = n.fatG,
                    saturatedFats = n.saturatedFatG,
                    transFats = null,
                    monounsaturatedFats = null,
                    polyunsaturatedFats = null,
                    omega3 = null,
                    omega6 = null,
                    carbohydrates = n.carbsG,
                    sugars = n.sugarG,
                    addedSugars = null,
                    dietaryFiber = n.fiberG,
                    solubleFiber = null,
                    insolubleFiber = null,
                    salt = n.saltG,
                    cholesterolMilli = null,
                    caffeineMilli = null,
                ),
            vitamins = EMPTY_VITAMINS,
            minerals = EMPTY_MINERALS,
            createdEpochSeconds = dto.createdAt?.let { Instant.parse(it).epochSeconds } ?: 0L,
            updatedEpochSeconds = dto.updatedAt?.let { Instant.parse(it).epochSeconds } ?: 0L,
        )
    }

    /**
     * Stable hash over the semantic content (identity + timestamps excluded) so hash equality means
     * the entry is unchanged and does not need re-pushing.
     */
    fun contentHash(dto: FoodEntryDto): String {
        val signature =
            ContentSignature(
                date = dto.date,
                meal = dto.meal,
                name = dto.name,
                brand = dto.brand,
                barcode = dto.barcode,
                amount = dto.quantity.amount,
                unit = dto.quantity.unit,
                nutrients = dto.nutrients,
                notes = dto.notes,
            )
        return fnv1a64Hex(canonicalJson.encodeToString(ContentSignature.serializer(), signature))
    }

    /**
     * Hash of what a manual entry materialized from [dto] would push back. The local row can only
     * hold name + date + the 8 nutrient totals + meal, so brand/barcode/notes drop and quantity
     * collapses to 1 serving; [localMealName] carries the local meal's canonical casing. Storing
     * THIS at apply time (instead of the rich server dto's hash) closes the sync loop by
     * construction — the next push re-derives the same lossy form, matches, and never overwrites the
     * server's richer copy.
     */
    fun localManualContentHash(dto: FoodEntryDto, localMealName: String): String =
        contentHash(
            dto.copy(
                meal = localMealName,
                brand = null,
                barcode = null,
                notes = null,
                quantity = QuantityDto(1.0, UNIT_SERVING),
            )
        )

    /**
     * Server catalog food -> a local Product ("My Food") row. [dto]'s per-100 g/ml nutrients map
     * straight onto the product; vitamins and minerals stay empty (scope: no enrichment). Source is
     * [FoodSourceType.User] — there is no dedicated "server" source and these behave as user foods.
     * [localId] is 0 for an insert, or the existing Product id for an in-place update.
     */
    fun toProductEntity(dto: FoodDto, localId: Long = 0): ProductEntity {
        val n = dto.per100g
        return ProductEntity(
            id = localId,
            name = dto.name,
            brand = dto.brand,
            barcode = dto.barcode,
            nutrients =
                Nutrients(
                    energy = n.kcal,
                    proteins = n.proteinG,
                    fats = n.fatG,
                    saturatedFats = n.saturatedFatG,
                    transFats = null,
                    monounsaturatedFats = null,
                    polyunsaturatedFats = null,
                    omega3 = null,
                    omega6 = null,
                    carbohydrates = n.carbsG,
                    sugars = n.sugarG,
                    addedSugars = null,
                    dietaryFiber = n.fiberG,
                    solubleFiber = null,
                    insolubleFiber = null,
                    salt = n.saltG,
                    cholesterolMilli = null,
                    caffeineMilli = null,
                ),
            vitamins = EMPTY_VITAMINS,
            minerals = EMPTY_MINERALS,
            packageWeight = dto.packageWeightG,
            servingWeight = dto.servingWeightG,
            note = null,
            sourceType = FoodSourceType.User,
            sourceUrl = null,
            isLiquid = dto.isLiquid,
        )
    }

    fun toGoalsDto(goal: MacronutrientGoal): GoalsDto =
        GoalsDto(
            kcal = goal.energyKcal,
            proteinG = goal.proteinsGrams,
            carbsG = goal.carbohydratesGrams,
            fatG = goal.fatsGrams,
        )

    /** Null when the server goal is incomplete (some macro target unset) — can't be materialized. */
    fun toMacronutrientGoal(dto: GoalsDto): MacronutrientGoal? =
        if (dto.isComplete) {
            MacronutrientGoal.Manual(
                energyKcal = dto.kcal!!,
                proteinsGrams = dto.proteinG!!,
                fatsGrams = dto.fatG!!,
                carbohydratesGrams = dto.carbsG!!,
            )
        } else {
            null
        }

    private companion object {
        const val SOURCE_APP = "app"
        const val UNIT_SERVING = "serving"

        val canonicalJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }

        val EMPTY_VITAMINS =
            Vitamins(null, null, null, null, null, null, null, null, null, null, null, null, null)

        val EMPTY_MINERALS =
            Minerals(null, null, null, null, null, null, null, null, null, null, null, null)
    }
}

/**
 * Descriptive quantity for a measurement (nutrient totals remain authoritative). Mass units report
 * grams, volume units report millilitres; serving/package report their count.
 */
internal fun Measurement.toQuantityDto(): QuantityDto =
    when (this) {
        is Measurement.Gram -> QuantityDto(value, "g")
        is Measurement.Ounce -> QuantityDto(metric, "g")
        is Measurement.Milliliter -> QuantityDto(value, "ml")
        is Measurement.FluidOunce -> QuantityDto(metric, "ml")
        is Measurement.Serving -> QuantityDto(quantity, "serving")
        is Measurement.Package -> QuantityDto(quantity, "piece")
    }

@Serializable
private data class ContentSignature(
    val date: String,
    val meal: String,
    val name: String,
    val brand: String?,
    val barcode: String?,
    val amount: Double,
    val unit: String,
    val nutrients: NutrientsDto,
    val notes: String?,
)

/** FNV-1a 64-bit over UTF-8 bytes — deterministic across platforms, no external dependency. */
private fun fnv1a64Hex(value: String): String {
    var hash = 0xcbf29ce484222325UL
    val prime = 0x100000001b3UL
    for (byte in value.encodeToByteArray()) {
        hash = hash xor byte.toUByte().toULong()
        hash *= prime
    }
    return hash.toString(16)
}
