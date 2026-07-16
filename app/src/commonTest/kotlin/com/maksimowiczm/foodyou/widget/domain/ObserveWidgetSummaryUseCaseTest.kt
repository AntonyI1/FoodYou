package com.maksimowiczm.foodyou.widget.domain

import com.maksimowiczm.foodyou.common.domain.date.DateProvider
import com.maksimowiczm.foodyou.common.domain.food.NutrientValue
import com.maksimowiczm.foodyou.common.domain.food.NutritionFacts
import com.maksimowiczm.foodyou.common.domain.userpreferences.UserPreferencesRepository
import com.maksimowiczm.foodyou.goals.domain.entity.DailyGoal
import com.maksimowiczm.foodyou.goals.domain.entity.MacronutrientGoal
import com.maksimowiczm.foodyou.goals.domain.entity.WeeklyGoals
import com.maksimowiczm.foodyou.goals.domain.repository.GoalsRepository
import com.maksimowiczm.foodyou.settings.domain.entity.AppLaunchInfo
import com.maksimowiczm.foodyou.settings.domain.entity.EnergyFormat
import com.maksimowiczm.foodyou.settings.domain.entity.Settings
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class ObserveWidgetSummaryUseCaseTest {

    // Three sources, three failure modes: each source can only reach one guard, so each test pins
    // the guard it is named for rather than an outcome several paths could produce.

    // finding: zero meals makes the diary flow never emit; first() would suspend forever.
    @Test
    fun hangingRead_isBoundedNotHung() = runBlocking {
        val env = TestEnv(totalsSource = { flow { awaitCancellation() } }, readTimeoutMs = 50)

        // Bounded here too: if the guard regresses this fails fast instead of wedging CI.
        val result = withTimeout(5_000) { env.useCase.get() }

        assertEquals(WidgetSummary.Unavailable, result)
    }

    // The goal blob is JSON-decoded and validated with error()/require(); withTimeoutOrNull would
    // not catch any of that, and a throw escapes provideGlance before provideContent runs.
    @Test
    fun readThrows_rendersUnavailable() = runBlocking {
        val env = TestEnv(totalsSource = { flow { throw IllegalStateException("bad goals blob") } })

        val result = env.useCase.get()

        assertEquals(WidgetSummary.Unavailable, result)
    }

    // runCatching would swallow this and run provideContent on a dead Glance session.
    @Test
    fun cancellation_isPropagatedNotSwallowed() = runBlocking {
        val env = TestEnv(totalsSource = { flow { throw CancellationException("session gone") } })

        var propagated = false
        try {
            env.useCase.get()
        } catch (e: CancellationException) {
            propagated = true
        }

        assertTrue(propagated, "cancellation must not be swallowed")
    }

    @Test
    fun todaysTotalsAndGoal_areSelected() = runBlocking {
        val env = TestEnv(totalsSource = { flowOf(facts(energy = 1500.0, proteins = 80.0)) })

        val result = env.useCase.get()

        assertEquals(WEDNESDAY, env.goals.requestedDate, "must read today's goal, not another day")
        val day = result as WidgetSummary.Day
        assertEquals(1500, day.energy)
        assertEquals(2000, day.energyGoal)
        assertEquals(80, day.proteins)
        assertEquals(0, day.carbohydrates, "an absent nutrient reads as zero")
        assertEquals(EnergyFormat.Kilocalories, day.energyFormat)
    }

    private class TestEnv(
        totalsSource: (LocalDate) -> Flow<NutritionFacts>,
        readTimeoutMs: Long = 3_000L,
    ) {
        val goals = FakeGoalsRepo()

        val useCase =
            ObserveWidgetSummaryUseCase(
                totalsSource = totalsSource,
                goalsRepository = goals,
                settingsRepository = FakeSettingsRepo(),
                dateProvider = FakeDateProvider,
                readTimeoutMs = readTimeoutMs,
            )
    }

    private class FakeGoalsRepo : GoalsRepository {
        var requestedDate: LocalDate? = null

        override suspend fun updateWeeklyGoals(weeklyGoals: WeeklyGoals) = error("unused")

        override fun observeWeeklyGoals(): Flow<WeeklyGoals> = error("unused")

        override fun observeDailyGoals(date: LocalDate): Flow<DailyGoal> {
            requestedDate = date
            // A distinct goal per weekday, so reading the wrong day is visible.
            val kcal = if (date.dayOfWeek == DayOfWeek.WEDNESDAY) 2000.0 else 9999.0
            return flowOf(dailyGoal(kcal))
        }
    }

    private class FakeSettingsRepo : UserPreferencesRepository<Settings> {
        override fun observe(): Flow<Settings> = flowOf(settings())

        override suspend fun update(transform: Settings.() -> Settings) = error("unused")
    }

    private object FakeDateProvider : DateProvider {
        override fun nowInstant() = NOW

        override fun observeInstant(interval: Duration): Flow<Instant> = flowOf(NOW)

        override fun observeDate(timeZone: TimeZone): Flow<LocalDate> = error("unused")
    }

    private companion object {
        val WEDNESDAY = LocalDate(2026, 7, 15)

        // Midday, so the local-TZ round-trip in DateProvider.now() can't slide onto another day.
        val NOW =
            LocalDateTime(2026, 7, 15, 12, 0).toInstant(TimeZone.currentSystemDefault())

        fun settings(energyFormat: EnergyFormat = EnergyFormat.Kilocalories) =
            Settings(
                lastRememberedVersion = null,
                hidePreviewDialog = false,
                showTranslationWarning = true,
                nutrientsOrder = emptyList(),
                secureScreen = false,
                homeCardOrder = emptyList(),
                expandGoalCard = true,
                onboardingFinished = true,
                energyFormat = energyFormat,
                appLaunchInfo =
                    AppLaunchInfo(
                        firstLaunch = null,
                        firstLaunchCurrentVersion = null,
                        launchesCount = 1,
                    ),
            )

        fun dailyGoal(energyKcal: Double) =
            DailyGoal(
                macronutrientGoal =
                    MacronutrientGoal.Manual(
                        energyKcal = energyKcal,
                        proteinsGrams = 150.0,
                        fatsGrams = 70.0,
                        carbohydratesGrams = 200.0,
                    ),
                map = emptyMap(),
            )

        fun facts(energy: Double, proteins: Double) =
            NutritionFacts(
                energy = NutrientValue.Complete(energy),
                proteins = NutrientValue.Complete(proteins),
            )
    }
}
