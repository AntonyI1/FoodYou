package com.maksimowiczm.foodyou.sync.infrastructure

import com.maksimowiczm.foodyou.common.domain.food.NutrientValue
import com.maksimowiczm.foodyou.common.domain.food.NutritionFacts
import com.maksimowiczm.foodyou.fooddiary.domain.entity.ManualDiaryEntry
import com.maksimowiczm.foodyou.fooddiary.domain.entity.ManualDiaryEntryId
import com.maksimowiczm.foodyou.goals.domain.entity.MacronutrientGoal
import com.maksimowiczm.foodyou.sync.infrastructure.api.FoodEntryDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.NutrientsDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.QuantityDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

class SyncMapperTest {

    private val mapper = SyncMapper(TimeZone.UTC)

    private fun manualEntry(
        nutritionFacts: NutritionFacts = yogurtFacts,
        name: String = "Greek yogurt",
        date: LocalDate = LocalDate(2026, 7, 13),
        createdAt: LocalDateTime = LocalDateTime(2026, 7, 13, 14, 1, 0),
        updatedAt: LocalDateTime = LocalDateTime(2026, 7, 13, 14, 1, 0),
    ) =
        ManualDiaryEntry(
            id = ManualDiaryEntryId(1),
            mealId = 5,
            date = date,
            name = name,
            nutritionFacts = nutritionFacts,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    @Test
    fun toDto_mapsManualEntryFieldsNutrientsAndTimestamps() {
        val dto = mapper.toDto(manualEntry(), mealName = "Breakfast", uuid = "abc-uuid")

        assertEquals("abc-uuid", dto.id)
        assertEquals("2026-07-13", dto.date)
        assertEquals("Breakfast", dto.meal)
        assertEquals("Greek yogurt", dto.name)
        assertEquals("app", dto.source)
        assertEquals(false, dto.deleted)
        // Manual entries have no quantity -> default serving.
        assertEquals(1.0, dto.quantity.amount)
        assertEquals("serving", dto.quantity.unit)
        // Nutrient field mapping.
        assertEquals(165.0, dto.nutrients.kcal)
        assertEquals(17.0, dto.nutrients.proteinG)
        assertEquals(6.0, dto.nutrients.carbsG)
        assertEquals(8.0, dto.nutrients.fatG)
        assertEquals(0.0, dto.nutrients.fiberG)
        assertEquals(6.0, dto.nutrients.sugarG)
        assertEquals(5.4, dto.nutrients.saturatedFatG)
        assertEquals(0.1, dto.nutrients.saltG)
        // LocalDateTime -> instant with injected UTC zone.
        assertEquals("2026-07-13T14:01:00Z", dto.createdAt)
        assertEquals("2026-07-13T14:01:00Z", dto.updatedAt)
    }

    @Test
    fun toDto_leavesMissingNutrientsNull() {
        val entry = manualEntry(nutritionFacts = NutritionFacts(energy = NutrientValue.Complete(200.0)))
        val dto = mapper.toDto(entry, mealName = "Snacks", uuid = null)

        assertEquals(200.0, dto.nutrients.kcal)
        assertNull(dto.nutrients.proteinG)
        assertNull(dto.nutrients.saltG)
        assertNull(dto.id)
    }

    @Test
    fun toManualEntity_mapsDtoNutrientsDateAndTimestamps() {
        val entity = mapper.toManualEntity(claudeSandwichDto, mealId = 2, localId = 7)

        assertEquals(7L, entity.id)
        assertEquals(2L, entity.mealId)
        assertEquals(LocalDate.parse("2026-07-13").toEpochDays(), entity.dateEpochDay)
        assertEquals("Chicken sandwich", entity.name)
        assertEquals(550.0, entity.nutrients.energy)
        assertEquals(30.0, entity.nutrients.proteins)
        assertEquals(45.0, entity.nutrients.carbohydrates)
        assertEquals(25.0, entity.nutrients.fats)
        assertEquals(3.0, entity.nutrients.dietaryFiber)
        assertEquals(5.0, entity.nutrients.sugars)
        assertEquals(6.0, entity.nutrients.saturatedFats)
        assertEquals(1.2, entity.nutrients.salt)
        // Unmapped nutrient fields and all vitamins/minerals stay null.
        assertNull(entity.nutrients.transFats)
        assertNull(entity.nutrients.cholesterolMilli)
        assertNull(entity.minerals.sodiumMilli)
        assertNull(entity.vitamins.vitaminAMicro)
        assertEquals(Instant.parse("2026-07-13T12:00:00Z").epochSeconds, entity.createdEpochSeconds)
        assertEquals(Instant.parse("2026-07-13T12:30:00Z").epochSeconds, entity.updatedEpochSeconds)
    }

    @Test
    fun contentHash_ignoresIdentityTimestampsSourceAndDeleted() {
        val volatileChange =
            claudeSandwichDto.copy(
                id = "different-id",
                createdAt = "2000-01-01T00:00:00Z",
                updatedAt = "2001-01-01T00:00:00Z",
                source = "api",
                deleted = true,
            )
        assertEquals(mapper.contentHash(claudeSandwichDto), mapper.contentHash(volatileChange))
    }

    @Test
    fun contentHash_changesWhenSemanticContentChanges() {
        val nutrientChange =
            claudeSandwichDto.copy(nutrients = claudeSandwichDto.nutrients.copy(kcal = 999.0))
        val nameChange = claudeSandwichDto.copy(name = "Different sandwich")
        val mealChange = claudeSandwichDto.copy(meal = "Dinner")

        assertNotEquals(mapper.contentHash(claudeSandwichDto), mapper.contentHash(nutrientChange))
        assertNotEquals(mapper.contentHash(claudeSandwichDto), mapper.contentHash(nameChange))
        assertNotEquals(mapper.contentHash(claudeSandwichDto), mapper.contentHash(mealChange))
    }

    @Test
    fun goals_roundTripThroughDto() {
        val goal =
            MacronutrientGoal.Manual(
                energyKcal = 2400.0,
                proteinsGrams = 180.0,
                fatsGrams = 80.0,
                carbohydratesGrams = 250.0,
            )
        val dto = mapper.toGoalsDto(goal)
        assertEquals(2400.0, dto.kcal)
        assertEquals(180.0, dto.proteinG)
        assertEquals(250.0, dto.carbsG)
        assertEquals(80.0, dto.fatG)

        val back = mapper.toMacronutrientGoal(dto)
        assertEquals(2400.0, back.energyKcal)
        assertEquals(180.0, back.proteinsGrams)
        assertEquals(80.0, back.fatsGrams)
        assertEquals(250.0, back.carbohydratesGrams)
    }

    private companion object {
        val yogurtFacts =
            NutritionFacts(
                energy = NutrientValue.Complete(165.0),
                proteins = NutrientValue.Complete(17.0),
                carbohydrates = NutrientValue.Complete(6.0),
                fats = NutrientValue.Complete(8.0),
                dietaryFiber = NutrientValue.Complete(0.0),
                sugars = NutrientValue.Complete(6.0),
                saturatedFats = NutrientValue.Complete(5.4),
                salt = NutrientValue.Complete(0.1),
            )

        val claudeSandwichDto =
            FoodEntryDto(
                id = "srv-1",
                date = "2026-07-13",
                meal = "Lunch",
                name = "Chicken sandwich",
                quantity = QuantityDto(1.0, "serving"),
                nutrients =
                    NutrientsDto(
                        kcal = 550.0,
                        proteinG = 30.0,
                        carbsG = 45.0,
                        fatG = 25.0,
                        fiberG = 3.0,
                        sugarG = 5.0,
                        saturatedFatG = 6.0,
                        saltG = 1.2,
                    ),
                source = "claude",
                createdAt = "2026-07-13T12:00:00Z",
                updatedAt = "2026-07-13T12:30:00Z",
                deleted = false,
            )
    }
}
