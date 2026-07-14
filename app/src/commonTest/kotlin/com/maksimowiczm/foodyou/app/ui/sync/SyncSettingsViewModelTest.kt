package com.maksimowiczm.foodyou.app.ui.sync

import com.maksimowiczm.foodyou.common.domain.date.DateProvider
import com.maksimowiczm.foodyou.common.domain.userpreferences.UserPreferencesRepository
import com.maksimowiczm.foodyou.sync.domain.SyncEngine
import com.maksimowiczm.foodyou.sync.domain.SyncPreferences
import com.maksimowiczm.foodyou.sync.domain.SyncResult
import com.maksimowiczm.foodyou.sync.domain.SyncRunner
import com.maksimowiczm.foodyou.sync.domain.SyncTokenRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Regression tests for [SyncSettingsViewModel].
 *
 * The "typing comes out reversed" bug in the Server URL field was rooted in the field's value being
 * driven by the async preferences round-trip: each keystroke wrote to DataStore and the lagging
 * value flowed back, resetting the Compose text cursor to index 0. The fix makes the field own its
 * state and commit on Save & test, so [SyncSettingsViewModel.saveAndTestConnection] now takes the
 * typed URL directly instead of reading it back from preferences.
 *
 * These tests lock in that contract: the value that gets normalized, persisted and probed is exactly
 * what the user typed — not whatever the async write last managed to persist. The old design read the
 * URL from preferences, so with a not-yet-persisted keystroke it would have probed a stale/blank URL;
 * that is the behaviour [saveAndTest_normalizesAndProbesTheTypedUrl_notThePersistedValue] pins down.
 * (The cursor behaviour itself is Compose-UI-layer; this module has no Compose UI-test harness, so
 * the guard lives at the ViewModel seam the fix introduced.)
 */
class SyncSettingsViewModelTest {

    @Test
    fun saveAndTest_normalizesAndProbesTheTypedUrl_notThePersistedValue() = runBlocking {
        // Persisted URL is still empty — as it would be if the last keystroke's async write hadn't
        // landed yet. The old code read the URL from here and would have probed "" (blank -> false).
        val prefs = FakePrefsRepo(SyncPreferences(serverUrl = ""))
        val token = FakeTokenRepo()
        val engine = RecordingSyncEngine(connectionResult = true)
        val vm = viewModel(prefs, token, engine)

        val ok = vm.saveAndTestConnection(url = "192.0.2.10:8365", token = "s3cret")

        assertTrue(ok)
        assertEquals(
            "http://192.0.2.10:8365",
            engine.probedBaseUrl,
            "probes the typed URL, scheme-normalized",
        )
        assertEquals("s3cret", engine.probedToken)
        assertEquals("http://192.0.2.10:8365", prefs.value.serverUrl, "persists the typed URL")
        assertEquals("s3cret", token.stored)
    }

    @Test
    fun saveAndTest_blankUrl_returnsFalseWithoutProbing() = runBlocking {
        val prefs = FakePrefsRepo(SyncPreferences(serverUrl = "http://stale"))
        val engine = RecordingSyncEngine(connectionResult = true)
        val vm = viewModel(prefs, FakeTokenRepo(), engine)

        val ok = vm.saveAndTestConnection(url = "   ", token = "s3cret")

        assertFalse(ok)
        assertNull(engine.probedBaseUrl, "a blank URL must not hit the network")
    }

    @Test
    fun currentServerUrl_returnsPersistedValueForSeeding() = runBlocking {
        val prefs = FakePrefsRepo(SyncPreferences(serverUrl = "http://192.0.2.10:8365"))
        val vm = viewModel(prefs, FakeTokenRepo(), RecordingSyncEngine())

        assertEquals("http://192.0.2.10:8365", vm.currentServerUrl())
    }

    // ---- fakes & builder ----

    private fun viewModel(
        prefs: FakePrefsRepo,
        token: FakeTokenRepo,
        engine: RecordingSyncEngine,
    ): SyncSettingsViewModel =
        SyncSettingsViewModel(
            preferencesRepository = prefs,
            tokenRepository = token,
            syncEngine = engine,
            syncRunner = SyncRunner(engine, prefs, FixedDateProvider),
        )

    private class FakePrefsRepo(initial: SyncPreferences) :
        UserPreferencesRepository<SyncPreferences> {
        var value = initial
            private set

        override fun observe() = flowOf(value)

        override suspend fun update(transform: SyncPreferences.() -> SyncPreferences) {
            value = value.transform()
        }
    }

    private class FakeTokenRepo : SyncTokenRepository {
        var stored: String? = null
            private set

        override suspend fun setToken(token: String) {
            stored = token
        }

        override suspend fun getToken() = stored

        override fun hasToken() = flowOf(stored != null)
    }

    private class RecordingSyncEngine(private val connectionResult: Boolean = false) : SyncEngine {
        var probedBaseUrl: String? = null
            private set

        var probedToken: String? = null
            private set

        override suspend fun sync(): SyncResult = SyncResult.Skipped

        override suspend fun testConnection(baseUrl: String, token: String): Boolean {
            probedBaseUrl = baseUrl
            probedToken = token
            return connectionResult
        }
    }

    private object FixedDateProvider : DateProvider {
        override fun nowInstant(): Instant = Instant.fromEpochSeconds(0)

        override fun observeInstant(interval: Duration): Flow<Instant> = flowOf(nowInstant())

        override fun observeDate(timeZone: TimeZone): Flow<LocalDate> = flowOf(LocalDate(2026, 1, 1))
    }
}
