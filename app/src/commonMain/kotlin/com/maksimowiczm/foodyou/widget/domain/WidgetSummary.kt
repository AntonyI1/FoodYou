package com.maksimowiczm.foodyou.widget.domain

import com.maksimowiczm.foodyou.common.domain.food.NutritionFacts
import com.maksimowiczm.foodyou.common.domain.food.NutritionFactsField
import com.maksimowiczm.foodyou.goals.domain.entity.DailyGoal
import com.maksimowiczm.foodyou.settings.domain.entity.EnergyFormat
import kotlin.math.roundToInt

/** What the widget draws. [Unavailable] is rendered, never a stuck loading layout. */
internal sealed interface WidgetSummary {

    data class Day(
        val energy: Int,
        val energyGoal: Int,
        val proteins: Int,
        val proteinsGoal: Int,
        val carbohydrates: Int,
        val carbohydratesGoal: Int,
        val fats: Int,
        val fatsGoal: Int,
        val energyFormat: EnergyFormat,
    ) : WidgetSummary

    /** The read timed out or threw. Shows an empty apple + a dash — still tappable. */
    data object Unavailable : WidgetSummary
}

/**
 * Mirrors the home screen's mapping (GoalsViewModel), including its absent-value-as-zero
 * behaviour. Pure: takes already-read facts + goal so it stays testable without repositories.
 */
internal fun widgetSummary(
    facts: NutritionFacts,
    goal: DailyGoal,
    energyFormat: EnergyFormat,
): WidgetSummary.Day =
    WidgetSummary.Day(
        energy = facts.energy.value?.roundToInt() ?: 0,
        energyGoal = goal[NutritionFactsField.Energy].roundToInt(),
        proteins = facts.proteins.value?.roundToInt() ?: 0,
        proteinsGoal = goal[NutritionFactsField.Proteins].roundToInt(),
        carbohydrates = facts.carbohydrates.value?.roundToInt() ?: 0,
        carbohydratesGoal = goal[NutritionFactsField.Carbohydrates].roundToInt(),
        fats = facts.fats.value?.roundToInt() ?: 0,
        fatsGoal = goal[NutritionFactsField.Fats].roundToInt(),
        energyFormat = energyFormat,
    )
