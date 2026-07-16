package com.maksimowiczm.foodyou.widget

import com.maksimowiczm.foodyou.settings.domain.entity.Settings
import com.maksimowiczm.foodyou.widget.domain.ObserveWidgetSummaryUseCase
import org.koin.core.qualifier.named
import org.koin.dsl.module

val widgetModule = module {
    // Binds the secondary constructor: the primary takes the injected totals source, which only
    // tests supply.
    factory {
        ObserveWidgetSummaryUseCase(
            observeDiaryMealsUseCase = get(),
            goalsRepository = get(),
            settingsRepository = get(named(Settings::class.qualifiedName!!)),
            dateProvider = get(),
        )
    }
}
