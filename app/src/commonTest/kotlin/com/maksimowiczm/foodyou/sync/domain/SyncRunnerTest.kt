package com.maksimowiczm.foodyou.sync.domain

import com.maksimowiczm.foodyou.common.domain.date.DateProvider
import com.maksimowiczm.foodyou.common.domain.userpreferences.UserPreferencesRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class SyncRunnerTest {

    @Test
    fun success_recordsLastSyncAndClearsError() = runBlocking {
        val prefs = FakePrefs(SyncPreferences(lastError = "old error"))
        val runner = runner(SyncResult.Success(1, 2, 0), prefs)

        runner.runSync()

        assertEquals(NOW.epochSeconds, prefs.value.lastSyncEpochSeconds)
        assertNull(prefs.value.lastError)
    }

    @Test
    fun failure_recordsError() = runBlocking {
        val prefs = FakePrefs(SyncPreferences())
        val runner = runner(SyncResult.Failure("boom"), prefs)

        runner.runSync()

        assertEquals("boom", prefs.value.lastError)
        assertNull(prefs.value.lastSyncEpochSeconds)
    }

    @Test
    fun disabled_leavesStatusUnchanged() = runBlocking {
        val prefs = FakePrefs(SyncPreferences(lastSyncEpochSeconds = 42, lastError = "old"))
        val runner = runner(SyncResult.Disabled, prefs)

        runner.runSync()

        assertEquals(42, prefs.value.lastSyncEpochSeconds)
        assertEquals("old", prefs.value.lastError)
    }

    private fun runner(result: SyncResult, prefs: FakePrefs) =
        SyncRunner(FakeEngine(result), prefs, FakeDateProvider)

    private class FakeEngine(private val result: SyncResult) : SyncEngine {
        override suspend fun sync() = result

        override suspend fun testConnection(baseUrl: String, token: String) = true
    }

    private class FakePrefs(var value: SyncPreferences) :
        UserPreferencesRepository<SyncPreferences> {
        override fun observe() = flowOf(value)

        override suspend fun update(transform: SyncPreferences.() -> SyncPreferences) {
            value = value.transform()
        }
    }

    private object FakeDateProvider : DateProvider {
        override fun nowInstant() = NOW

        override fun observeInstant(interval: Duration): Flow<Instant> = flowOf(NOW)

        override fun observeDate(timeZone: TimeZone): Flow<LocalDate> = error("unused")
    }

    private companion object {
        val NOW = Instant.fromEpochSeconds(1_000_000)
    }
}
