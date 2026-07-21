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
 * Verifies the additive v33 -> v34 auto-migration creates the SyncProductMapping table. Instrumented
 * (needs a device/emulator), so it is not part of the host `testDebugUnitTest` gate; run via
 * `:app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class SyncProductMappingMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val file = instrumentation.targetContext.getDatabasePath("SyncProductMappingMigrationTest.db")
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
    fun migrate33To34_createsSyncProductMappingTable() {
        helper.createDatabase(33).close()
        helper.runMigrationsAndValidate(34, emptyList()).use { connection ->
            connection
                .prepare(
                    "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'SyncProductMapping'"
                )
                .use { statement ->
                    statement.step()
                    assertEquals(1L, statement.getLong(0))
                }
        }
    }
}
