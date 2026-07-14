package com.maksimowiczm.foodyou.app.ui.sync

import com.maksimowiczm.foodyou.common.infrastructure.koin.userPreferencesRepository
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel

internal fun Module.syncSettings() {
    viewModel {
        SyncSettingsViewModel(
            preferencesRepository = userPreferencesRepository(),
            tokenRepository = get(),
            syncEngine = get(),
            syncRunner = get(),
        )
    }
}
