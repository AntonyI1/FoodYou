package com.maksimowiczm.foodyou.sync.infrastructure

import com.maksimowiczm.foodyou.common.domain.database.TransactionProvider
import com.maksimowiczm.foodyou.common.domain.database.TransactionScope
import com.maksimowiczm.foodyou.common.domain.food.NutritionFacts
import com.maksimowiczm.foodyou.common.domain.userpreferences.UserPreferencesRepository
import com.maksimowiczm.foodyou.common.infrastructure.room.toNutritionFacts
import com.maksimowiczm.foodyou.fooddiary.domain.entity.FoodDiaryEntry
import com.maksimowiczm.foodyou.fooddiary.domain.entity.FoodDiaryEntryId
import com.maksimowiczm.foodyou.fooddiary.domain.entity.ManualDiaryEntry
import com.maksimowiczm.foodyou.fooddiary.domain.entity.ManualDiaryEntryId
import com.maksimowiczm.foodyou.fooddiary.domain.entity.Meal
import com.maksimowiczm.foodyou.fooddiary.domain.repository.FoodDiaryEntryRepository
import com.maksimowiczm.foodyou.fooddiary.domain.repository.ManualDiaryEntryRepository
import com.maksimowiczm.foodyou.fooddiary.domain.repository.MealRepository
import com.maksimowiczm.foodyou.fooddiary.infrastructure.room.ManualDiaryEntryDao
import com.maksimowiczm.foodyou.fooddiary.infrastructure.room.ManualDiaryEntryEntity
import com.maksimowiczm.foodyou.goals.domain.entity.DailyGoal
import com.maksimowiczm.foodyou.goals.domain.entity.MacronutrientGoal
import com.maksimowiczm.foodyou.goals.domain.entity.WeeklyGoals
import com.maksimowiczm.foodyou.goals.domain.repository.GoalsRepository
import com.maksimowiczm.foodyou.sync.domain.SyncException
import com.maksimowiczm.foodyou.sync.domain.SyncPreferences
import com.maksimowiczm.foodyou.sync.domain.SyncResult
import com.maksimowiczm.foodyou.sync.domain.SyncTokenRepository
import com.maksimowiczm.foodyou.food.infrastructure.room.ProductEntity
import com.maksimowiczm.foodyou.sync.infrastructure.api.EntriesResponseDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.FoodDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.FoodEntryDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.FoodsResponseDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.GoalsDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.NutrientsDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.QuantityDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.SyncApi
import com.maksimowiczm.foodyou.sync.infrastructure.api.SyncConnection
import com.maksimowiczm.foodyou.sync.infrastructure.room.SyncEntryMappingDao
import com.maksimowiczm.foodyou.sync.infrastructure.room.SyncEntryMappingEntity
import com.maksimowiczm.foodyou.sync.infrastructure.room.SyncLocalRef
import com.maksimowiczm.foodyou.sync.infrastructure.room.SyncProductDao
import com.maksimowiczm.foodyou.sync.infrastructure.room.SyncProductMappingDao
import com.maksimowiczm.foodyou.sync.infrastructure.room.SyncProductMappingEntity
import com.maksimowiczm.foodyou.sync.infrastructure.room.SyncReadDao
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class DefaultSyncEngineTest {

    @Test
    fun firstSync_pushesNewLocalManualEntry() = runBlocking {
        val env = TestEnv()
        env.manualDao.seed(manualEntity(id = 10, mealId = 1, name = "Oatmeal", kcal = 300.0))

        val result = env.engine.sync()

        assertEquals(1, env.api.pushed().size)
        assertEquals("Oatmeal", env.api.pushed().single().name)
        assertEquals("app", env.api.pushed().single().source)
        assertEquals("uuid-0", env.mappingDao.getByLocal("ManualDiaryEntry", 10)?.uuid)
        assertEquals(SyncResult.Success(pushed = 1, pulled = 0, deleted = 0), result)
    }

    @Test
    fun secondSync_doesNotRepushUnchangedEntry() = runBlocking {
        val env = TestEnv()
        env.manualDao.seed(manualEntity(id = 10, mealId = 1, name = "Oatmeal", kcal = 300.0))

        env.engine.sync()
        env.api.clearPushed()
        val result = env.engine.sync()

        assertTrue(env.api.pushed().isEmpty(), "unchanged entry must not re-push (loop closure)")
        assertEquals(0, (result as SyncResult.Success).pushed)
    }

    // findings 1 + 8: a lossy local row must not re-push and clobber the server's richer copy.
    @Test
    fun pulledRichEntry_isNotRepushedAndServerCopyIsPreserved() = runBlocking {
        val env = TestEnv()
        val rich =
            serverEntry(uuid = "rich-1", meal = "breakfast", name = "Greek yogurt")
                .copy(
                    brand = "Fage",
                    barcode = "5201054018471",
                    notes = "post-workout",
                    quantity = QuantityDto(170.0, "g"),
                )
        env.api.seedServer(rich)

        env.engine.sync() // materialize
        env.api.clearPushed()
        env.engine.sync()

        assertTrue(env.api.pushed().isEmpty(), "lossy form must never overwrite the server copy")
        val onServer = env.api.serverEntry("rich-1")
        assertEquals("Fage", onServer?.brand, "server keeps its richer copy")
        assertEquals("breakfast", onServer?.meal, "server meal casing not rewritten")
        assertEquals("post-workout", onServer?.notes)
    }

    @Test
    fun pull_materializesNewServerEntryAsManual() = runBlocking {
        val env = TestEnv()
        env.api.seedServer(serverEntry(uuid = "s1", meal = "Lunch", name = "Sandwich"))

        val result = env.engine.sync()

        val inserted = env.manualDao.store.values.singleOrNull()
        assertEquals("Sandwich", inserted?.name)
        assertEquals(2L, inserted?.mealId)
        assertEquals("ManualDiaryEntry", env.mappingDao.getByUuid("s1")?.localTable)
        assertEquals(1, (result as SyncResult.Success).pulled)
    }

    @Test
    fun pull_unknownMealName_createsAnyTimeMeal() = runBlocking {
        val env = TestEnv()
        env.api.seedServer(serverEntry(uuid = "s2", meal = "Pre-workout", name = "Banana"))

        env.engine.sync()

        val created = env.mealRepo.meals.firstOrNull { it.name == "Pre-workout" }
        assertEquals(LocalTime(0, 0), created?.from)
        assertEquals(LocalTime(23, 59), created?.to)
    }

    @Test
    fun pull_tombstoneDeletesLocalEntry() = runBlocking {
        val env = TestEnv()
        env.api.seedServer(serverEntry(uuid = "s3", meal = "Lunch", name = "Toast"))
        env.engine.sync()
        val localId = env.mappingDao.getByUuid("s3")!!.localId
        env.api.tombstone("s3")

        val result = env.engine.sync()

        assertNull(env.manualDao.store[localId], "tombstoned entry deleted locally")
        assertNull(env.mappingDao.getByUuid("s3"))
        assertEquals(1, (result as SyncResult.Success).deleted)
    }

    // finding 10a: a malformed pull item is skipped; good items still apply.
    @Test
    fun pull_badEntryIsSkipped_othersApplied() = runBlocking {
        val env = TestEnv()
        env.api.seedServer(
            serverEntry(uuid = "bad", meal = "Lunch", name = "X").copy(date = "not-a-date")
        )
        env.api.seedServer(serverEntry(uuid = "good", meal = "Lunch", name = "Sandwich"))

        env.engine.sync()

        assertNotNull(env.mappingDao.getByUuid("good"))
        assertNull(env.mappingDao.getByUuid("bad"))
    }

    @Test
    fun pull_serverEditToMeasurementEntryIsNotApplied() = runBlocking {
        val env = TestEnv()
        env.readDao.measurementIds = listOf(30) // the measurement row still exists -> not a tombstone
        env.mappingDao.upsert(SyncEntryMappingEntity("m1", "Measurement", 30, "local-hash", 0))
        env.api.seedServer(serverEntry(uuid = "m1", meal = "Lunch", name = "Edited by Claude"))

        env.engine.sync()

        assertTrue(env.foodRepo.deleted.isEmpty(), "measurement not deleted")
        assertTrue(env.manualDao.store.isEmpty(), "no manual entry created for the server edit")
        assertEquals("Measurement", env.mappingDao.getByUuid("m1")?.localTable)
    }

    // finding 3: a push whose outcome is unknown reserves the uuid, and it is reused next cycle.
    @Test
    fun lostPushResponse_reusesSameUuidNextCycle() = runBlocking {
        val env = TestEnv()
        env.manualDao.seed(manualEntity(id = 10, mealId = 1, name = "Oatmeal", kcal = 300.0))
        env.api.commitOnPush = false // server won't echo it back, forcing a re-push next cycle
        env.api.pushError = { IllegalStateException("lost response") }

        val first = env.engine.sync()
        assertTrue(first is SyncResult.Failure)
        val reserved = env.mappingDao.getByLocal("ManualDiaryEntry", 10)
        assertNotNull(reserved, "uuid reserved before the push")
        assertNull(reserved.contentHash, "reservation is pending until the push is confirmed")

        env.api.pushError = null
        env.api.clearPushed()
        env.engine.sync()

        assertEquals(reserved.uuid, env.api.pushed().single().id, "same uuid re-pushed (idempotent)")
    }

    // finding 9: retry classification.
    @Test
    fun unauthorized_isTerminalFailure() = runBlocking {
        val env = TestEnv()
        env.manualDao.seed(manualEntity(id = 10, mealId = 1, name = "X", kcal = 1.0))
        env.api.pushError = { SyncException.Unauthorized() }

        assertEquals(false, (env.engine.sync() as SyncResult.Failure).retryable)
    }

    @Test
    fun networkError_isRetryableFailure() = runBlocking {
        val env = TestEnv()
        env.manualDao.seed(manualEntity(id = 10, mealId = 1, name = "X", kcal = 1.0))
        env.api.pushError = { IllegalStateException("connection reset") }

        assertEquals(true, (env.engine.sync() as SyncResult.Failure).retryable)
    }

    // finding 10b: a push failure does not block the pull.
    @Test
    fun pushFailure_doesNotBlockPull() = runBlocking {
        val env = TestEnv()
        env.manualDao.seed(manualEntity(id = 10, mealId = 1, name = "Local", kcal = 1.0))
        env.api.commitOnPush = false
        env.api.pushError = { IllegalStateException("push down") }
        env.api.seedServer(serverEntry(uuid = "srv", meal = "Lunch", name = "FromServer"))

        val result = env.engine.sync()

        assertNotNull(env.mappingDao.getByUuid("srv"), "pull still applied the server entry")
        assertTrue(result is SyncResult.Failure, "push error reported")
    }

    // finding 10e: a hanging (filterNotNull'd) read is bounded, not a deadlock.
    @Test
    fun hangingMeasurementRead_isSkippedNotHung() = runBlocking {
        val env = TestEnv(readTimeoutMs = 50)
        env.readDao.measurementIds = listOf(99)
        env.foodRepo.hangingIds = setOf(99)

        val result = withTimeout(5_000) { env.engine.sync() }

        assertTrue(result is SyncResult.Success)
        assertTrue(env.api.pushed().isEmpty())
    }

    // finding 4: apply runs inside a transaction.
    @Test
    fun applyingServerEntry_runsInATransaction() = runBlocking {
        val env = TestEnv()
        env.api.seedServer(serverEntry(uuid = "s1", meal = "Lunch", name = "Sandwich"))

        env.engine.sync()

        assertTrue(env.tx.used, "insert + mapping must be wrapped in a transaction")
    }

    // ---- goals ----

    @Test
    fun goals_pushLocalWhenChangedSinceSnapshot() = runBlocking {
        val env = TestEnv()
        env.goalsRepo.weekly = weeklyGoal(2400.0, 180.0, 250.0, 80.0)
        env.prefsRepo.set(
            env.prefsRepo.value.copy(
                goalKcal = 2000.0,
                goalProteinG = 150.0,
                goalCarbsG = 200.0,
                goalFatG = 70.0,
            )
        )

        env.engine.sync()

        assertEquals(GoalsDto(2400.0, 180.0, 250.0, 80.0), env.api.setGoalsCalls.singleOrNull())
    }

    @Test
    fun goals_applyServerWhenLocalUnchanged() = runBlocking {
        val env = TestEnv()
        env.goalsRepo.weekly = weeklyGoal(2000.0, 150.0, 200.0, 70.0)
        env.prefsRepo.set(
            env.prefsRepo.value.copy(
                goalKcal = 2000.0,
                goalProteinG = 150.0,
                goalCarbsG = 200.0,
                goalFatG = 70.0,
            )
        )
        env.api.serverGoals = GoalsDto(2500.0, 190.0, 260.0, 90.0)

        env.engine.sync()

        assertTrue(env.api.setGoalsCalls.isEmpty())
        assertEquals(2500.0, env.goalsRepo.weekly.monday.macronutrientGoal.energyKcal)
    }

    // finding 7: first contact — a pre-set server goal wins over the app defaults.
    @Test
    fun goals_firstContactServerWins() = runBlocking {
        val env = TestEnv()
        env.goalsRepo.weekly = weeklyGoal(2000.0, 150.0, 200.0, 70.0)
        env.api.serverGoals = GoalsDto(3000.0, 200.0, 300.0, 100.0)

        env.engine.sync()

        assertTrue(env.api.setGoalsCalls.isEmpty(), "must not clobber pre-set server goals")
        assertEquals(3000.0, env.goalsRepo.weekly.monday.macronutrientGoal.energyKcal)
    }

    // finding 7: first contact — server unset, so seed it from the app.
    @Test
    fun goals_firstContactSeedsServerWhenUnset() = runBlocking {
        val env = TestEnv()
        env.goalsRepo.weekly = weeklyGoal(2000.0, 150.0, 200.0, 70.0)
        env.api.serverGoals = GoalsDto()

        env.engine.sync()

        assertEquals(GoalsDto(2000.0, 150.0, 200.0, 70.0), env.api.setGoalsCalls.singleOrNull())
    }

    @Test
    fun goals_skippedInSeparateGoalsMode() = runBlocking {
        val env = TestEnv()
        env.goalsRepo.weekly = weeklyGoal(2000.0, 150.0, 200.0, 70.0, separate = true)

        env.engine.sync()

        assertTrue(env.api.setGoalsCalls.isEmpty())
    }

    // partial-goal stalemate fix: first contact — a partial server goal (merge-semantics server) is
    // completed by overlaying local values, applied, pushed back complete, then both sides converge.
    @Test
    fun goals_firstContactPartialServerMergesAndConverges() = runBlocking {
        val env = TestEnv()
        env.goalsRepo.weekly = weeklyGoal(2000.0, 150.0, 200.0, 70.0)
        env.api.serverGoals = GoalsDto(kcal = null, proteinG = 180.0, carbsG = null, fatG = null)

        env.engine.sync()

        val applied = env.goalsRepo.weekly.monday.macronutrientGoal
        assertEquals(180.0, applied.proteinsGrams, "server's set field wins")
        assertEquals(2000.0, applied.energyKcal, "local fills the server's nulls")
        assertEquals(200.0, applied.carbohydratesGrams)
        assertEquals(70.0, applied.fatsGrams)
        assertEquals(
            GoalsDto(2000.0, 180.0, 200.0, 70.0),
            env.api.setGoalsCalls.singleOrNull(),
            "merged complete goal pushed back to converge the server row",
        )
        assertEquals(180.0, env.prefsRepo.value.goalProteinG, "snapshot written")
        assertEquals(2000.0, env.prefsRepo.value.goalKcal, "snapshot written")

        env.api.setGoalsCalls.clear()
        env.engine.sync()

        assertTrue(env.api.setGoalsCalls.isEmpty(), "converged — no further goal calls")
    }

    // partial-goal stalemate fix: steady state — an already-synced server row turns partial; the set
    // field overlays local, the completed goal is pushed back, and both sides converge.
    @Test
    fun goals_steadyStatePartialServerOverlaysLocal() = runBlocking {
        val env = TestEnv()
        env.goalsRepo.weekly = weeklyGoal(2000.0, 150.0, 200.0, 70.0)
        env.prefsRepo.set(
            env.prefsRepo.value.copy(
                goalKcal = 2000.0,
                goalProteinG = 150.0,
                goalCarbsG = 200.0,
                goalFatG = 70.0,
            )
        )
        env.api.serverGoals = GoalsDto(kcal = null, proteinG = 185.0, carbsG = null, fatG = null)

        env.engine.sync()

        val applied = env.goalsRepo.weekly.monday.macronutrientGoal
        assertEquals(185.0, applied.proteinsGrams, "server's set field overlays local")
        assertEquals(2000.0, applied.energyKcal, "local unchanged where server is null")
        assertEquals(200.0, applied.carbohydratesGrams)
        assertEquals(70.0, applied.fatsGrams)
        assertEquals(
            GoalsDto(2000.0, 185.0, 200.0, 70.0),
            env.api.setGoalsCalls.singleOrNull(),
            "merged complete goal pushed back to converge the server row",
        )
        assertEquals(185.0, env.prefsRepo.value.goalProteinG, "snapshot advanced to the merged goal")

        env.api.setGoalsCalls.clear()
        env.engine.sync()

        assertTrue(env.api.setGoalsCalls.isEmpty(), "converged — no further goal calls")
    }

    // ---- foods catalog pull ----

    @Test
    fun foods_pull_insertsNewProductAndMapping() = runBlocking {
        val env = TestEnv()
        env.api.seedFood(serverFood(id = "f1", name = "Rolled oats", kcal = 389.0, servingWeightG = 40.0))

        val result = env.engine.sync()

        val product = env.productDao.store.values.singleOrNull()
        assertEquals("Rolled oats", product?.name)
        assertEquals(389.0, product?.nutrients?.energy)
        assertEquals(40.0, product?.servingWeight)
        val mapping = env.productMappingDao.getByUuid("f1")
        assertNotNull(mapping)
        assertEquals(product?.id, mapping.localProductId)
        assertEquals(1, (result as SyncResult.Success).productsPulled)
    }

    @Test
    fun foods_pull_appliesRunInATransaction() = runBlocking {
        val env = TestEnv() // default goals are separate-mode, so only the foods apply uses tx here
        env.api.seedFood(serverFood(id = "f1", name = "Oats", kcal = 389.0))

        env.engine.sync()

        assertTrue(env.tx.used, "product insert + mapping must be wrapped in a transaction")
    }

    @Test
    fun foods_pull_updatesProductWhenServerUpdatedAtAdvances() = runBlocking {
        val env = TestEnv()
        env.api.seedFood(serverFood(id = "f1", name = "Oats", kcal = 380.0, updatedAt = "2026-07-13T08:00:00Z"))
        env.engine.sync()
        val originalId = env.productMappingDao.getByUuid("f1")!!.localProductId

        // Server food changes and its updated_at advances.
        env.api.seedFood(serverFood(id = "f1", name = "Oats (updated)", kcal = 400.0, updatedAt = "2026-07-13T10:00:00Z"))
        val result = env.engine.sync()

        val product = env.productDao.store.values.single()
        assertEquals(originalId, product.id, "updated in place, same local product id")
        assertEquals("Oats (updated)", product.name)
        assertEquals(400.0, product.nutrients.energy)
        assertEquals(1, env.productDao.store.size, "no duplicate product created")
        assertEquals(
            Instant.parse("2026-07-13T10:00:00Z").epochSeconds,
            env.productMappingDao.getByUuid("f1")!!.serverUpdatedAt,
        )
        assertEquals(1, (result as SyncResult.Success).productsPulled)
    }

    @Test
    fun foods_pull_skipsUnchangedFood() = runBlocking {
        val env = TestEnv()
        env.api.seedFood(serverFood(id = "f1", name = "Oats", kcal = 389.0, updatedAt = "2026-07-13T08:00:00Z"))
        env.engine.sync()

        // Same food, same updated_at on the next sync -> nothing re-applied.
        val result = env.engine.sync()

        assertEquals(0, (result as SyncResult.Success).productsPulled, "unchanged food must not re-apply")
        assertEquals(0, env.productDao.updates, "product must not be rewritten when unchanged")
    }

    @Test
    fun foods_pull_persistsCursor() = runBlocking {
        val env = TestEnv()
        env.api.seedFood(serverFood(id = "f1", name = "Oats", kcal = 389.0))

        env.engine.sync()

        // Without persisting foodsCursor, every sync would re-pull the whole catalog.
        assertEquals("2026-07-14T00:00:00Z", env.prefsRepo.value.foodsCursor)
    }

    @Test
    fun foods_pull_badFoodSkipped_othersApplied() = runBlocking {
        val env = TestEnv()
        env.api.seedFood(serverFood(id = "bad", name = "X", kcal = 1.0).copy(updatedAt = "not-an-instant"))
        env.api.seedFood(serverFood(id = "good", name = "Oats", kcal = 389.0))

        env.engine.sync()

        assertNotNull(env.productMappingDao.getByUuid("good"))
        assertNull(env.productMappingDao.getByUuid("bad"))
    }

    @Test
    fun foods_pull_stillHappensWhenPushFails() = runBlocking {
        val env = TestEnv()
        env.manualDao.seed(manualEntity(id = 10, mealId = 1, name = "Local", kcal = 1.0))
        env.api.commitOnPush = false
        env.api.pushError = { IllegalStateException("push down") }
        env.api.seedFood(serverFood(id = "f1", name = "Oats", kcal = 389.0))

        val result = env.engine.sync()

        assertNotNull(env.productMappingDao.getByUuid("f1"), "foods pull still applied despite push failure")
        assertTrue(result is SyncResult.Failure, "push error still reported")
    }

    // ---- fakes & builders ----

    private class TestEnv(readTimeoutMs: Long = 5_000L) {
        val manualDao = FakeManualDao()
        val readDao = FakeReadDao(manualDao)
        val mappingDao = FakeMappingDao()
        val productDao = FakeSyncProductDao()
        val productMappingDao = FakeSyncProductMappingDao()
        val manualRepo = FakeManualRepo(manualDao)
        val foodRepo = FakeFoodRepo()
        val mealRepo = FakeMealRepo()
        val goalsRepo = FakeGoalsRepo()
        val tx = FakeTransactionProvider()
        val api = FakeSyncApi()
        val prefsRepo = FakePrefsRepo(SyncPreferences(serverUrl = "http://server", enabled = true))
        private var uuidCounter = 0

        val engine =
            DefaultSyncEngine(
                syncReadDao = readDao,
                mappingDao = mappingDao,
                productMappingDao = productMappingDao,
                syncProductDao = productDao,
                manualEntryRepository = manualRepo,
                manualEntryDao = manualDao,
                foodEntryRepository = foodRepo,
                mealRepository = mealRepo,
                goalsRepository = goalsRepo,
                transactionProvider = tx,
                api = api,
                mapper = SyncMapper(TimeZone.UTC),
                preferencesRepository = prefsRepo,
                tokenRepository = FakeTokenRepo("token"),
                uuidFactory = { "uuid-${uuidCounter++}" },
                readTimeoutMs = readTimeoutMs,
            )
    }

    private class FakeTransactionProvider : TransactionProvider {
        var used = false

        override suspend fun <T> withTransaction(block: suspend TransactionScope<T>.() -> T): T {
            used = true
            val scope =
                object : TransactionScope<T> {
                    override suspend fun rollback(result: T) = error("unused")
                }
            return scope.block()
        }
    }

    private class FakeManualDao : ManualDiaryEntryDao {
        val store = mutableMapOf<Long, ManualDiaryEntryEntity>()
        private var nextId = 100L

        fun seed(entity: ManualDiaryEntryEntity) {
            store[entity.id] = entity
        }

        override fun observe(id: Long): Flow<ManualDiaryEntryEntity?> = flowOf(store[id])

        override fun observeAll(mealId: Long, dateEpochDay: Long) =
            flowOf(emptyList<ManualDiaryEntryEntity>())

        override suspend fun insert(entry: ManualDiaryEntryEntity): Long {
            val id = if (entry.id != 0L) entry.id else nextId++
            store[id] = entry.copy(id = id)
            return id
        }

        override suspend fun update(entry: ManualDiaryEntryEntity) {
            store[entry.id] = entry
        }

        override suspend fun delete(id: Long) {
            store.remove(id)
        }
    }

    private class FakeReadDao(private val dao: FakeManualDao) : SyncReadDao {
        var measurementIds: List<Long> = emptyList()

        override suspend fun manualEntryRefs() = dao.store.keys.map { SyncLocalRef(it) }

        override suspend fun measurementRefs() = measurementIds.map { SyncLocalRef(it) }
    }

    private class FakeManualRepo(private val dao: FakeManualDao) : ManualDiaryEntryRepository {
        override fun observe(id: ManualDiaryEntryId): Flow<ManualDiaryEntry?> =
            flowOf(dao.store[id.value]?.toDomain())

        override fun observeAll(mealId: Long, date: LocalDate) = flowOf(emptyList<ManualDiaryEntry>())

        override suspend fun insert(
            name: String,
            mealId: Long,
            date: LocalDate,
            nutritionFacts: NutritionFacts,
            createdAt: LocalDateTime,
        ) = error("unused")

        override suspend fun update(entry: ManualDiaryEntry) = error("unused")

        override suspend fun delete(id: ManualDiaryEntryId) = error("unused")
    }

    private class FakeMappingDao : SyncEntryMappingDao {
        private val byUuid = mutableMapOf<String, SyncEntryMappingEntity>()

        override suspend fun getByUuid(uuid: String) = byUuid[uuid]

        override suspend fun getByLocal(localTable: String, localId: Long) =
            byUuid.values.firstOrNull { it.localTable == localTable && it.localId == localId }

        override suspend fun getAll() = byUuid.values.toList()

        override suspend fun upsert(mapping: SyncEntryMappingEntity) {
            byUuid[mapping.uuid] = mapping
        }

        override suspend fun deleteByUuid(uuid: String) {
            byUuid.remove(uuid)
        }
    }

    private class FakeSyncProductDao : SyncProductDao {
        val store = mutableMapOf<Long, ProductEntity>()
        var updates = 0
            private set

        private var nextId = 500L

        override suspend fun insert(product: ProductEntity): Long {
            val id = if (product.id != 0L) product.id else nextId++
            store[id] = product.copy(id = id)
            return id
        }

        override suspend fun update(product: ProductEntity) {
            updates++
            store[product.id] = product
        }
    }

    private class FakeSyncProductMappingDao : SyncProductMappingDao {
        private val byUuid = mutableMapOf<String, SyncProductMappingEntity>()

        override suspend fun getByUuid(uuid: String) = byUuid[uuid]

        override suspend fun upsert(mapping: SyncProductMappingEntity) {
            byUuid[mapping.uuid] = mapping
        }
    }

    private class FakeFoodRepo : FoodDiaryEntryRepository {
        val deleted = mutableListOf<Long>()
        var hangingIds: Set<Long> = emptySet()

        override fun observe(id: FoodDiaryEntryId): Flow<FoodDiaryEntry?> =
            if (id.value in hangingIds) flow { awaitCancellation() } else flowOf(null)

        override fun observeAll(mealId: Long, date: LocalDate) = flowOf(emptyList<FoodDiaryEntry>())

        override suspend fun insert(
            measurement: com.maksimowiczm.foodyou.common.domain.measurement.Measurement,
            mealId: Long,
            date: LocalDate,
            food: com.maksimowiczm.foodyou.fooddiary.domain.entity.DiaryFood,
            createdAt: LocalDateTime,
        ) = error("unused")

        override suspend fun update(entry: FoodDiaryEntry) = error("unused")

        override suspend fun delete(id: FoodDiaryEntryId) {
            deleted += id.value
        }
    }

    private class FakeMealRepo : MealRepository {
        val meals =
            mutableListOf(
                Meal(1, "Breakfast", LocalTime(6, 0), LocalTime(10, 0), 0),
                Meal(2, "Lunch", LocalTime(12, 0), LocalTime(14, 0), 1),
            )
        private var nextId = 5L

        override fun observeMeal(mealId: Long) = flowOf(meals.firstOrNull { it.id == mealId })

        override fun observeMeals() = flowOf(meals.toList())

        override suspend fun insertMealWithLastRank(name: String, from: LocalTime, to: LocalTime) {
            meals += Meal(nextId++, name, from, to, meals.size)
        }

        override suspend fun deleteMeal(mealId: Long) = error("unused")

        override suspend fun updateMeal(id: Long, name: String, from: LocalTime, to: LocalTime) =
            error("unused")

        override suspend fun reorderMeals(order: List<Long>) = error("unused")
    }

    private class FakeGoalsRepo : GoalsRepository {
        var weekly = weeklyGoal(2000.0, 150.0, 200.0, 70.0, separate = true)

        override suspend fun updateWeeklyGoals(weeklyGoals: WeeklyGoals) {
            weekly = weeklyGoals
        }

        override fun observeWeeklyGoals() = flowOf(weekly)

        override fun observeDailyGoals(date: LocalDate) = flowOf(weekly.monday)
    }

    private class FakeSyncApi : SyncApi {
        private val server = mutableMapOf<String, FoodEntryDto>()
        private val serverFoods = mutableMapOf<String, FoodDto>()
        private val pushedEntries = mutableListOf<FoodEntryDto>()
        val setGoalsCalls = mutableListOf<GoalsDto>()
        var serverGoals: GoalsDto? = null
        var pushError: (() -> Throwable)? = null
        var commitOnPush = true
        private var stamp = 1

        fun seedServer(dto: FoodEntryDto) {
            server[dto.id!!] = dto
        }

        // Re-seeding the same id replaces it, so a test can simulate a server-side food change.
        fun seedFood(dto: FoodDto) {
            serverFoods[dto.id] = dto
        }

        fun tombstone(uuid: String) {
            server[uuid]?.let { server[uuid] = it.copy(deleted = true) }
        }

        fun serverEntry(uuid: String) = server[uuid]

        fun pushed() = pushedEntries.toList()

        fun clearPushed() = pushedEntries.clear()

        override suspend fun health(connection: SyncConnection) = true

        override suspend fun pull(connection: SyncConnection, updatedSince: String?) =
            EntriesResponseDto(server.values.toList(), "2026-07-14T00:00:00Z")

        override suspend fun pullFoods(connection: SyncConnection, updatedSince: String?) =
            FoodsResponseDto(serverFoods.values.toList(), "2026-07-14T00:00:00Z")

        override suspend fun push(connection: SyncConnection, entries: List<FoodEntryDto>) {
            pushedEntries += entries
            if (commitOnPush) {
                entries.forEach {
                    server[it.id!!] = it.copy(updatedAt = "2026-07-14T00:00:0${stamp++}Z")
                }
            }
            pushError?.let { throw it() }
        }

        override suspend fun delete(connection: SyncConnection, id: String) {
            server.remove(id)
        }

        override suspend fun getGoals(connection: SyncConnection) = serverGoals ?: GoalsDto()

        override suspend fun setGoals(connection: SyncConnection, goals: GoalsDto) {
            setGoalsCalls += goals
            serverGoals = goals
        }
    }

    private class FakePrefsRepo(initial: SyncPreferences) :
        UserPreferencesRepository<SyncPreferences> {
        var value = initial
            private set

        fun set(preferences: SyncPreferences) {
            value = preferences
        }

        override fun observe() = flowOf(value)

        override suspend fun update(transform: SyncPreferences.() -> SyncPreferences) {
            value = value.transform()
        }
    }

    private class FakeTokenRepo(private val token: String?) : SyncTokenRepository {
        override suspend fun setToken(token: String) = error("unused")

        override suspend fun getToken() = token

        override fun hasToken() = flowOf(token != null)
    }
}

private fun ManualDiaryEntryEntity.toDomain(): ManualDiaryEntry =
    ManualDiaryEntry(
        id = ManualDiaryEntryId(id),
        mealId = mealId,
        date = LocalDate.fromEpochDays(dateEpochDay.toInt()),
        name = name,
        nutritionFacts =
            toNutritionFacts(nutrients = nutrients, vitamins = vitamins, minerals = minerals),
        createdAt = Instant.fromEpochSeconds(createdEpochSeconds).toLocalDateTime(TimeZone.UTC),
        updatedAt = Instant.fromEpochSeconds(updatedEpochSeconds).toLocalDateTime(TimeZone.UTC),
    )

private fun manualEntity(id: Long, mealId: Long, name: String, kcal: Double): ManualDiaryEntryEntity =
    SyncMapper(TimeZone.UTC)
        .toManualEntity(
            FoodEntryDto(
                id = "seed",
                date = "2026-07-13",
                meal = "Breakfast",
                name = name,
                quantity = QuantityDto(1.0, "serving"),
                nutrients = NutrientsDto(kcal = kcal),
                source = "app",
                createdAt = "2026-07-13T08:00:00Z",
                updatedAt = "2026-07-13T08:00:00Z",
            ),
            mealId = mealId,
            localId = id,
        )

private fun serverFood(
    id: String,
    name: String,
    kcal: Double,
    updatedAt: String = "2026-07-13T08:00:00Z",
    servingWeightG: Double? = null,
): FoodDto =
    FoodDto(
        id = id,
        name = name,
        per100g = NutrientsDto(kcal = kcal, proteinG = 5.0),
        servingWeightG = servingWeightG,
        isLiquid = false,
        createdAt = "2026-07-13T08:00:00Z",
        updatedAt = updatedAt,
    )

private fun serverEntry(uuid: String, meal: String, name: String): FoodEntryDto =
    FoodEntryDto(
        id = uuid,
        date = "2026-07-13",
        meal = meal,
        name = name,
        quantity = QuantityDto(1.0, "serving"),
        nutrients = NutrientsDto(kcal = 400.0, proteinG = 20.0),
        source = "claude",
        createdAt = "2026-07-13T08:00:00Z",
        updatedAt = "2026-07-13T08:00:00Z",
        deleted = false,
    )

private fun weeklyGoal(
    kcal: Double,
    protein: Double,
    carbs: Double,
    fat: Double,
    separate: Boolean = false,
): WeeklyGoals {
    val daily =
        DailyGoal(
            MacronutrientGoal.Manual(
                energyKcal = kcal,
                proteinsGrams = protein,
                fatsGrams = fat,
                carbohydratesGrams = carbs,
            ),
            emptyMap(),
        )
    return WeeklyGoals(separate, daily, daily, daily, daily, daily, daily, daily)
}
