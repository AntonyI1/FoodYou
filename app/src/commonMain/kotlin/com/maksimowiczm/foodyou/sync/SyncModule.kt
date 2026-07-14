package com.maksimowiczm.foodyou.sync

import com.maksimowiczm.foodyou.common.infrastructure.koin.userPreferencesRepository
import com.maksimowiczm.foodyou.common.infrastructure.koin.userPreferencesRepositoryOf
import com.maksimowiczm.foodyou.sync.domain.SyncEngine
import com.maksimowiczm.foodyou.sync.domain.SyncPreferences
import com.maksimowiczm.foodyou.sync.domain.SyncRunner
import com.maksimowiczm.foodyou.sync.domain.SyncTokenRepository
import com.maksimowiczm.foodyou.sync.infrastructure.DefaultSyncEngine
import com.maksimowiczm.foodyou.sync.infrastructure.SyncMapper
import com.maksimowiczm.foodyou.sync.infrastructure.api.KtorSyncApi
import com.maksimowiczm.foodyou.sync.infrastructure.api.SyncApi
import com.maksimowiczm.foodyou.sync.infrastructure.repository.DataStoreSyncPreferencesRepository
import com.maksimowiczm.foodyou.sync.infrastructure.repository.DataStoreSyncTokenRepository
import com.maksimowiczm.foodyou.sync.infrastructure.room.SyncDatabase
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.factoryOf
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.dsl.onClose

private val Scope.syncDatabase: SyncDatabase
    get() = get()

val syncModule = module {
    single(named(SyncApi::class.qualifiedName!!)) {
            HttpClient {
                install(HttpTimeout)
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        }
        .onClose { it?.close() }

    factory<SyncApi> { KtorSyncApi(client = get(named(SyncApi::class.qualifiedName!!))) }

    single { SyncMapper() }

    userPreferencesRepositoryOf(::DataStoreSyncPreferencesRepository)
    factoryOf(::DataStoreSyncTokenRepository).bind<SyncTokenRepository>()

    factory { syncDatabase.syncEntryMappingDao }
    factory { syncDatabase.syncReadDao }

    factory<SyncEngine> {
        DefaultSyncEngine(
            syncReadDao = get(),
            mappingDao = get(),
            manualEntryRepository = get(),
            manualEntryDao = get(),
            foodEntryRepository = get(),
            mealRepository = get(),
            goalsRepository = get(),
            api = get(),
            mapper = get(),
            preferencesRepository = userPreferencesRepository(),
            tokenRepository = get(),
        )
    }

    factory {
        SyncRunner(
            engine = get(),
            preferencesRepository = userPreferencesRepository(),
            dateProvider = get(),
        )
    }
}
