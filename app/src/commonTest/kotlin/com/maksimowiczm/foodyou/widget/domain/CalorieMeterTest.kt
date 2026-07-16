package com.maksimowiczm.foodyou.widget.domain

import com.maksimowiczm.foodyou.app.ui.common.utility.EnergyFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

class CalorieMeterTest {

    @Test
    fun halfway_isHalfFraction() {
        val meter = calorieMeter(consumedKcal = 1000, goalKcal = 2000)

        assertEquals(0.5f, meter.strokeFraction)
        assertEquals(MeterLabel.Remaining(1000), meter.label)
    }

    // The outline must not wrap the contour a second time.
    @Test
    fun overGoal_clampsAtOne_andShowsOver() {
        val meter = calorieMeter(consumedKcal = 2500, goalKcal = 2000)

        assertEquals(1f, meter.strokeFraction)
        assertEquals(MeterLabel.Over(500), meter.label)
    }

    @Test
    fun exactlyAtGoal_isFull_withZeroRemaining() {
        val meter = calorieMeter(consumedKcal = 2000, goalKcal = 2000)

        assertEquals(1f, meter.strokeFraction)
        assertEquals(MeterLabel.Remaining(0), meter.label)
    }

    @Test
    fun zeroConsumed_isEmptyTrack() {
        val meter = calorieMeter(consumedKcal = 0, goalKcal = 2000)

        assertEquals(0f, meter.strokeFraction)
        assertEquals(MeterLabel.Remaining(2000), meter.label)
    }

    // "No goal set" is only reachable as zero — the goal form rejects negatives, not zero.
    @Test
    fun zeroGoal_showsDayTotal_withEmptyTrack() {
        val meter = calorieMeter(consumedKcal = 1240, goalKcal = 0)

        assertEquals(0f, meter.strokeFraction)
        assertEquals(MeterLabel.Total(1240), meter.label)
    }

    @Test
    fun negativeGoal_treatedAsNoGoal() {
        val meter = calorieMeter(consumedKcal = 1240, goalKcal = -1)

        assertEquals(0f, meter.strokeFraction)
        assertEquals(MeterLabel.Total(1240), meter.label)
    }

    @Test
    fun macroFraction_clampsAndHandlesZeroGoal() {
        assertEquals(0.5f, macroFraction(valueGrams = 75, goalGrams = 150))
        assertEquals(1f, macroFraction(valueGrams = 200, goalGrams = 150))
        assertEquals(0f, macroFraction(valueGrams = 75, goalGrams = 0))
    }

    @Test
    fun kilocalories_displayValueIsUnchanged() {
        assertEquals(200, MeterLabel.Remaining(200).displayValue(EnergyFormatter.kilocalories))
    }

    // A kJ user must not be shown a kcal number: 200 * 4.184 = 836.8 -> 837.
    @Test
    fun kilojoules_convertsDisplayValue() {
        assertEquals(837, MeterLabel.Remaining(200).displayValue(EnergyFormatter.kilojoules))
        assertEquals(837, MeterLabel.Over(200).displayValue(EnergyFormatter.kilojoules))
        assertEquals(837, MeterLabel.Total(200).displayValue(EnergyFormatter.kilojoules))
    }
}
