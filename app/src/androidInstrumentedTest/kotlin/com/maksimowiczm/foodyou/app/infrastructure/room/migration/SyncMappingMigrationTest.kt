package com.maksimowiczm.foodyou.app.infrastructure.room.migration

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maksimowiczm.foodyou.app.infrastructure.room.FoodYouDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Verifies the additive v32 -> v33 auto-migration creates the SyncEntryMapping table. Instrumented
 * (needs a device/emulator), so it is not part of the host `testDebugUnitTest` gate; run via
 * `:app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class SyncMappingMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val file = instrumentation.targetContext.getDatabasePath("SyncMappingMigrationTest.db")
    private val driver: SQLiteDriver = BundledSQLiteDriver()

    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            instrumentation = instrumentation,
            file = file,
            driver = driver,
            databaseClass = FoodYouDatabase::class,
        )

    @Test
    fun migrate32To33_createsSyncEntryMappingTable() {
        helper.createDatabase(32).close()
        helper.runMigrationsAndValidate(33, emptyList()).use { connection ->
            connection
                .prepare(
                    "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'SyncEntryMapping'"
                )
                .use { statement ->
                    statement.step()
                    assertEquals(1L, statement.getLong(0))
                }
        }
    }
}
