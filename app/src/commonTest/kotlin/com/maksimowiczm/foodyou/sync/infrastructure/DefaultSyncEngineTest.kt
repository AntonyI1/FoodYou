package com.maksimowiczm.foodyou.sync.infrastructure

import com.maksimowiczm.foodyou.common.domain.food.NutrientValue
import com.maksimowiczm.foodyou.common.domain.food.NutritionFacts
import com.maksimowiczm.foodyou.common.domain.userpreferences.UserPreferencesRepository
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
import com.maksimowiczm.foodyou.sync.domain.SyncPreferences
import com.maksimowiczm.foodyou.sync.domain.SyncResult
import com.maksimowiczm.foodyou.sync.domain.SyncTokenRepository
import com.maksimowiczm.foodyou.sync.infrastructure.api.EntriesResponseDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.FoodEntryDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.GoalsDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.NutrientsDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.QuantityDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.SyncApi
import com.maksimowiczm.foodyou.sync.infrastructure.api.SyncConnection
import com.maksimowiczm.foodyou.sync.infrastructure.room.SyncEntryMappingDao
import com.maksimowiczm.foodyou.sync.infrastructure.room.SyncEntryMappingEntity
import com.maksimowiczm.foodyou.sync.infrastructure.room.SyncLocalRef
import com.maksimowiczm.foodyou.sync.infrastructure.room.SyncReadDao
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

class DefaultSyncEngineTest {

    @Test
    fun firstSync_pushesNewLocalManualEntry() = runBlocking {
        val env = TestEnv()
        env.readDao.manual = listOf(SyncLocalRef(id = 10, updatedAt = 100))
        env.manualRepo.store[10] = manualEntry(mealId = 1, name = "Oatmeal", kcal = 300.0)

        val result = env.engine.sync()

        assertEquals(1, env.api.pushed.size)
        assertEquals("Oatmeal", env.api.pushed.single().name)
        assertEquals("app", env.api.pushed.single().source)
        val mapping = env.mappingDao.getByLocal("ManualDiaryEntry", 10)
        assertEquals("uuid-0", mapping?.uuid)
        assertEquals(SyncResult.Success(pushed = 1, pulled = 0, deleted = 0), result)
        assertEquals("2026-07-13T00:00:00Z", env.prefsRepo.current().cursor)
    }

    @Test
    fun secondSync_doesNotRepushUnchangedEntry() = runBlocking {
        val env = TestEnv()
        val entry = manualEntry(mealId = 1, name = "Oatmeal", kcal = 300.0)
        env.readDao.manual = listOf(SyncLocalRef(id = 10, updatedAt = 100))
        env.manualRepo.store[10] = entry
        // Pre-existing mapping with the current content hash -> loop closure.
        val dto = env.mapper.toDto(entry, "Breakfast", "uuid-existing")
        val hash = env.mapper.contentHash(dto)
        env.mappingDao.upsert(SyncEntryMappingEntity("uuid-existing", "ManualDiaryEntry", 10, hash, 0))
        // Server echoes the same entry back on pull.
        env.api.pullResponse =
            EntriesResponseDto(
                entries = listOf(dto.copy(updatedAt = "2026-07-13T09:00:00Z")),
                syncedAt = "2026-07-13T10:00:00Z",
            )

        val result = env.engine.sync()

        assertTrue(env.api.pushed.isEmpty(), "unchanged entry must not be re-pushed")
        assertEquals(SyncResult.Success(pushed = 0, pulled = 0, deleted = 0), result)
        val mapping = env.mappingDao.getByUuid("uuid-existing")
        assertEquals("2026-07-13T09:00:00Z".epochSeconds(), mapping?.serverUpdatedAt)
    }

    @Test
    fun pull_materializesNewServerEntryAsManual() = runBlocking {
        val env = TestEnv()
        env.api.pullResponse =
            EntriesResponseDto(
                entries = listOf(serverEntry(uuid = "s1", meal = "Lunch", name = "Sandwich")),
                syncedAt = "2026-07-13T10:00:00Z",
            )

        val result = env.engine.sync()

        val inserted = env.manualDao.store.values.singleOrNull()
        assertEquals("Sandwich", inserted?.name)
        assertEquals(2L, inserted?.mealId) // Lunch resolved to existing meal id 2
        val mapping = env.mappingDao.getByUuid("s1")
        assertEquals("ManualDiaryEntry", mapping?.localTable)
        assertEquals(SyncResult.Success(pushed = 0, pulled = 1, deleted = 0), result)
    }

    @Test
    fun pull_unknownMealName_createsAnyTimeMeal() = runBlocking {
        val env = TestEnv()
        env.api.pullResponse =
            EntriesResponseDto(
                entries = listOf(serverEntry(uuid = "s2", meal = "Pre-workout", name = "Banana")),
                syncedAt = "2026-07-13T10:00:00Z",
            )

        env.engine.sync()

        val created = env.mealRepo.meals.firstOrNull { it.name == "Pre-workout" }
        assertEquals(LocalTime(0, 0), created?.from)
        assertEquals(LocalTime(23, 59), created?.to)
        val inserted = env.manualDao.store.values.single()
        assertEquals(created?.id, inserted.mealId)
    }

    @Test
    fun localDelete_pushesTombstoneAndRemovesMapping() = runBlocking {
        val env = TestEnv()
        // Mapping exists but the local row is gone (readDao returns nothing).
        env.mappingDao.upsert(SyncEntryMappingEntity("u1", "ManualDiaryEntry", 10, "hash", 0))

        val result = env.engine.sync()

        assertEquals(listOf("u1"), env.api.deleted)
        assertNull(env.mappingDao.getByUuid("u1"))
        assertEquals(SyncResult.Success(pushed = 1, pulled = 0, deleted = 0), result)
    }

    @Test
    fun pull_tombstoneDeletesLocalEntry() = runBlocking {
        val env = TestEnv()
        val entry = manualEntry(mealId = 1, name = "Oatmeal", kcal = 300.0)
        env.readDao.manual = listOf(SyncLocalRef(id = 10, updatedAt = 100))
        env.manualRepo.store[10] = entry
        env.manualDao.store[10] = manualEntity(id = 10)
        val dto = env.mapper.toDto(entry, "Breakfast", "u2")
        env.mappingDao.upsert(
            SyncEntryMappingEntity("u2", "ManualDiaryEntry", 10, env.mapper.contentHash(dto), 0)
        )
        env.api.pullResponse =
            EntriesResponseDto(
                entries = listOf(dto.copy(deleted = true, updatedAt = "2026-07-13T09:00:00Z")),
                syncedAt = "2026-07-13T10:00:00Z",
            )

        val result = env.engine.sync()

        assertNull(env.manualDao.store[10], "tombstoned entry must be deleted locally")
        assertNull(env.mappingDao.getByUuid("u2"))
        assertEquals(1, result.let { (it as SyncResult.Success).deleted })
    }

    @Test
    fun pull_serverEditToMeasurementEntryIsNotApplied() = runBlocking {
        // Ruling A: app owns structured food entries. A server-side edit is recorded but never
        // written back onto the local Measurement, and the stale local is not re-pushed.
        val env = TestEnv()
        env.readDao.measurement = listOf(SyncLocalRef(id = 30, updatedAt = 200))
        // foodRepo.observe(30) returns null -> push skips it (local unchanged, nothing to send).
        env.mappingDao.upsert(SyncEntryMappingEntity("m1", "Measurement", 30, "local-hash", 0))
        env.api.pullResponse =
            EntriesResponseDto(
                entries =
                    listOf(
                        serverEntry(uuid = "m1", meal = "Lunch", name = "Edited by Claude")
                            .copy(updatedAt = "2026-07-13T09:00:00Z")
                    ),
                syncedAt = "2026-07-13T10:00:00Z",
            )

        val result = env.engine.sync()

        assertTrue(env.api.pushed.isEmpty(), "unchanged measurement must not be re-pushed")
        assertTrue(env.foodRepo.deleted.isEmpty(), "measurement must not be deleted")
        assertTrue(env.manualDao.store.isEmpty(), "server edit must not create a manual entry")
        val mapping = env.mappingDao.getByUuid("m1")
        assertEquals("Measurement", mapping?.localTable)
        assertEquals("2026-07-13T09:00:00Z".epochSeconds(), mapping?.serverUpdatedAt)
        assertEquals(SyncResult.Success(pushed = 0, pulled = 0, deleted = 0), result)
    }

    @Test
    fun goals_pushLocalWhenChangedSinceSnapshot() = runBlocking {
        val env = TestEnv()
        env.goalsRepo.weekly = weeklyGoal(kcal = 2400.0, protein = 180.0, carbs = 250.0, fat = 80.0)
        // No snapshot yet -> local is considered changed.

        env.engine.sync()

        assertEquals(GoalsDto(2400.0, 180.0, 250.0, 80.0), env.api.setGoals.singleOrNull())
        assertEquals(2400.0, env.prefsRepo.current().goalKcal)
    }

    @Test
    fun goals_applyServerWhenLocalUnchanged() = runBlocking {
        val env = TestEnv()
        env.goalsRepo.weekly = weeklyGoal(kcal = 2000.0, protein = 150.0, carbs = 200.0, fat = 70.0)
        // Snapshot equals local -> local unchanged.
        env.prefsRepo.set(
            env.prefsRepo.current()
                .copy(goalKcal = 2000.0, goalProteinG = 150.0, goalCarbsG = 200.0, goalFatG = 70.0)
        )
        env.api.serverGoals = GoalsDto(2500.0, 190.0, 260.0, 90.0)

        env.engine.sync()

        assertTrue(env.api.setGoals.isEmpty(), "local unchanged -> must not push goals")
        assertEquals(2500.0, env.goalsRepo.weekly.monday.macronutrientGoal.energyKcal)
        assertEquals(2500.0, env.prefsRepo.current().goalKcal)
    }

    @Test
    fun goals_skippedInSeparateGoalsMode() = runBlocking {
        val env = TestEnv()
        env.goalsRepo.weekly =
            weeklyGoal(kcal = 2000.0, protein = 150.0, carbs = 200.0, fat = 70.0, separate = true)

        env.engine.sync()

        assertTrue(env.api.setGoals.isEmpty())
        assertNull(env.prefsRepo.current().goalKcal)
    }

    // ---- fakes & builders ----

    private class TestEnv {
        val readDao = FakeSyncReadDao()
        val mappingDao = FakeSyncEntryMappingDao()
        val manualRepo = FakeManualRepo()
        val manualDao = FakeManualDao()
        val foodRepo = FakeFoodEntryRepo()
        val mealRepo = FakeMealRepo()
        val goalsRepo = FakeGoalsRepo()
        val api = FakeSyncApi()
        val prefsRepo = FakePrefsRepo(SyncPreferences(serverUrl = "http://server", enabled = true))
        val tokenRepo = FakeTokenRepo("token")
        val mapper = SyncMapper(TimeZone.UTC)
        private var uuidCounter = 0

        val engine =
            DefaultSyncEngine(
                syncReadDao = readDao,
                mappingDao = mappingDao,
                manualEntryRepository = manualRepo,
                manualEntryDao = manualDao,
                foodEntryRepository = foodRepo,
                mealRepository = mealRepo,
                goalsRepository = goalsRepo,
                api = api,
                mapper = mapper,
                preferencesRepository = prefsRepo,
                tokenRepository = tokenRepo,
                uuidFactory = { "uuid-${uuidCounter++}" },
            )
    }

    private class FakeSyncReadDao(
        var manual: List<SyncLocalRef> = emptyList(),
        var measurement: List<SyncLocalRef> = emptyList(),
    ) : SyncReadDao {
        override suspend fun manualEntryRefs() = manual

        override suspend fun measurementRefs() = measurement
    }

    private class FakeSyncEntryMappingDao : SyncEntryMappingDao {
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

    private class FakeManualRepo : ManualDiaryEntryRepository {
        val store = mutableMapOf<Long, ManualDiaryEntry>()

        override fun observe(id: ManualDiaryEntryId): Flow<ManualDiaryEntry?> =
            flowOf(store[id.value])

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

    private class FakeManualDao : ManualDiaryEntryDao {
        val store = mutableMapOf<Long, ManualDiaryEntryEntity>()
        private var nextId = 100L

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

    private class FakeFoodEntryRepo : FoodDiaryEntryRepository {
        val store = mutableMapOf<Long, FoodDiaryEntry>()
        val deleted = mutableListOf<Long>()

        override fun observe(id: FoodDiaryEntryId): Flow<FoodDiaryEntry?> = flowOf(store[id.value])

        override fun observeAll(mealId: Long, date: LocalDate) =
            flowOf(emptyList<FoodDiaryEntry>())

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
                Meal(3, "Dinner", LocalTime(18, 0), LocalTime(20, 0), 2),
                Meal(4, "Snacks", LocalTime(0, 0), LocalTime(23, 59), 3),
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
        val pushed = mutableListOf<FoodEntryDto>()
        val deleted = mutableListOf<String>()
        val setGoals = mutableListOf<GoalsDto>()
        var pullResponse = EntriesResponseDto(emptyList(), "2026-07-13T00:00:00Z")
        var serverGoals: GoalsDto? = null

        override suspend fun health(connection: SyncConnection) = true

        override suspend fun pull(connection: SyncConnection, updatedSince: String?) = pullResponse

        override suspend fun push(connection: SyncConnection, entries: List<FoodEntryDto>) {
            pushed += entries
        }

        override suspend fun delete(connection: SyncConnection, id: String) {
            deleted += id
        }

        override suspend fun getGoals(connection: SyncConnection) =
            serverGoals ?: error("no server goals")

        override suspend fun setGoals(connection: SyncConnection, goals: GoalsDto) {
            setGoals += goals
        }
    }

    private class FakePrefsRepo(initial: SyncPreferences) :
        UserPreferencesRepository<SyncPreferences> {
        private var value = initial

        fun current() = value

        fun set(preferences: SyncPreferences) {
            value = preferences
        }

        override fun observe() = flowOf(value)

        override suspend fun update(transform: SyncPreferences.() -> SyncPreferences) {
            value = value.transform()
        }
    }

    private class FakeTokenRepo(private var token: String?) : SyncTokenRepository {
        override suspend fun setToken(token: String) {
            this.token = token
        }

        override suspend fun getToken() = token

        override suspend fun clear() {
            token = null
        }

        override fun hasToken() = flowOf(token != null)
    }
}

private fun manualEntry(mealId: Long, name: String, kcal: Double): ManualDiaryEntry =
    ManualDiaryEntry(
        id = ManualDiaryEntryId(0),
        mealId = mealId,
        date = LocalDate(2026, 7, 13),
        name = name,
        nutritionFacts = NutritionFacts(energy = NutrientValue.Complete(kcal)),
        createdAt = LocalDateTime(2026, 7, 13, 8, 0, 0),
        updatedAt = LocalDateTime(2026, 7, 13, 8, 0, 0),
    )

private fun manualEntity(id: Long): ManualDiaryEntryEntity =
    SyncMapper(TimeZone.UTC)
        .toManualEntity(
            serverEntry(uuid = "seed", meal = "Breakfast", name = "Seed"),
            mealId = 1,
            localId = id,
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

private fun String.epochSeconds(): Long = kotlin.time.Instant.parse(this).epochSeconds
