package com.maksimowiczm.foodyou.widget.domain

import com.maksimowiczm.foodyou.common.domain.date.DateProvider
import com.maksimowiczm.foodyou.common.domain.food.NutritionFacts
import com.maksimowiczm.foodyou.common.domain.food.sum
import com.maksimowiczm.foodyou.common.domain.userpreferences.UserPreferencesRepository
import com.maksimowiczm.foodyou.fooddiary.domain.usecase.ObserveDiaryMealsUseCase
import com.maksimowiczm.foodyou.goals.domain.repository.GoalsRepository
import com.maksimowiczm.foodyou.settings.domain.entity.Settings
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate

/**
 * Reads today's totals + goal once per widget update. One-shot on purpose: the diary flow combines
 * a 1-second poller, so collecting it would push RemoteViews at 1 Hz forever.
 *
 * [totalsSource] is injected so the failure paths are testable without a repository graph, matching
 * `DefaultSyncEngine`'s `uuidFactory` idiom. Production uses the secondary constructor.
 */
internal class ObserveWidgetSummaryUseCase(
    private val totalsSource: (LocalDate) -> Flow<NutritionFacts>,
    private val goalsRepository: GoalsRepository,
    private val settingsRepository: UserPreferencesRepository<Settings>,
    private val dateProvider: DateProvider,
    private val readTimeoutMs: Long = 3_000L,
) {
    constructor(
        observeDiaryMealsUseCase: ObserveDiaryMealsUseCase,
        goalsRepository: GoalsRepository,
        settingsRepository: UserPreferencesRepository<Settings>,
        dateProvider: DateProvider,
    ) : this(
        totalsSource = { date ->
            observeDiaryMealsUseCase.observe(date).map { meals ->
                meals.map { it.nutritionFacts }.sum()
            }
        },
        goalsRepository = goalsRepository,
        settingsRepository = settingsRepository,
        dateProvider = dateProvider,
    )

    suspend fun get(): WidgetSummary =
        try {
            // Zero meals => the use case's inner combine() never emits (kotlinx Combine.kt:18 bails
            // out on an empty flow array) and the 1s poller keeps the outer flow alive, so first()
            // would suspend forever and wedge the widget on its loading layout. Not padding.
            withTimeoutOrNull(readTimeoutMs) {
                val date = dateProvider.now().date
                val facts = totalsSource(date).first()
                // Resolves single-daily vs per-weekday goals; both modes fan out to all 7 days.
                val goal = goalsRepository.observeDailyGoals(date).first()
                val energyFormat = settingsRepository.observe().first().energyFormat
                widgetSummary(facts, goal, energyFormat)
            } ?: WidgetSummary.Unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The goal blob is JSON-decoded and validated with error()/require(); DataStore surfaces
            // IOException. A throw here would escape provideGlance before provideContent runs.
            WidgetSummary.Unavailable
        }
}
