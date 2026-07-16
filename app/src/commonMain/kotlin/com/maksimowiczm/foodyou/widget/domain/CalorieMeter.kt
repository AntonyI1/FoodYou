package com.maksimowiczm.foodyou.widget.domain

import com.maksimowiczm.foodyou.app.ui.common.utility.EnergyFormatter
import kotlin.math.roundToInt

/**
 * The apple meter's state. Pure arithmetic — no `android.graphics` here, so it stays JVM-testable
 * from commonTest (there is no androidUnitTest source set). The renderer turns [strokeFraction]
 * into a path segment; this decides what that fraction is.
 */
internal data class CalorieMeter(val strokeFraction: Float, val label: MeterLabel)

/** What goes inside the apple. */
internal sealed interface MeterLabel {
    /** Under or exactly at goal: big number + "left". */
    data class Remaining(val kcal: Int) : MeterLabel

    /** Over goal: big number + "over" — never a negative "left". */
    data class Over(val kcal: Int) : MeterLabel

    /** No usable goal: the day's total instead, since "left" is meaningless. */
    data class Total(val kcal: Int) : MeterLabel
}

/**
 * @param goalKcal `<= 0` means "no usable goal". The domain cannot express an absent goal
 *   (`observeWeeklyGoals()` falls back to [WeeklyGoals.defaultGoals] and `DailyGoal[Energy]` is
 *   non-null), but zero is reachable — the goal form only rejects negatives — so both collapse here.
 */
internal fun calorieMeter(consumedKcal: Int, goalKcal: Int): CalorieMeter =
    when {
        goalKcal <= 0 -> CalorieMeter(0f, MeterLabel.Total(consumedKcal))
        consumedKcal > goalKcal ->
            // Clamped, so the stroke can't wrap the contour a second time.
            CalorieMeter(1f, MeterLabel.Over(consumedKcal - goalKcal))
        else ->
            CalorieMeter(
                strokeFraction = consumedKcal.toFloat() / goalKcal,
                label = MeterLabel.Remaining(goalKcal - consumedKcal),
            )
    }

/** Same clamp for the P/C/F bars. */
internal fun macroFraction(valueGrams: Int, goalGrams: Int): Float =
    if (goalGrams <= 0) 0f else (valueGrams.toFloat() / goalGrams).coerceAtMost(1f)

/**
 * kcal -> the unit the user actually reads. The meter fraction is a ratio so it needs no
 * conversion; only the displayed integer does.
 */
internal fun MeterLabel.displayValue(formatter: EnergyFormatter): Int =
    when (this) {
        is MeterLabel.Remaining -> formatter.fromKcal(kcal.toDouble())
        is MeterLabel.Over -> formatter.fromKcal(kcal.toDouble())
        is MeterLabel.Total -> formatter.fromKcal(kcal.toDouble())
    }.roundToInt()
