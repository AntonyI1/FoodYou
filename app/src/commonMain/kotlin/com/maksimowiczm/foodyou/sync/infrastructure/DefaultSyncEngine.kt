package com.maksimowiczm.foodyou.sync.infrastructure

import com.maksimowiczm.foodyou.common.domain.database.TransactionProvider
import com.maksimowiczm.foodyou.common.domain.userpreferences.UserPreferencesRepository
import com.maksimowiczm.foodyou.fooddiary.domain.entity.DiaryEntry
import com.maksimowiczm.foodyou.fooddiary.domain.entity.FoodDiaryEntryId
import com.maksimowiczm.foodyou.fooddiary.domain.entity.ManualDiaryEntryId
import com.maksimowiczm.foodyou.fooddiary.domain.entity.Meal
import com.maksimowiczm.foodyou.fooddiary.domain.repository.FoodDiaryEntryRepository
import com.maksimowiczm.foodyou.fooddiary.domain.repository.ManualDiaryEntryRepository
import com.maksimowiczm.foodyou.fooddiary.domain.repository.MealRepository
import com.maksimowiczm.foodyou.fooddiary.infrastructure.room.ManualDiaryEntryDao
import com.maksimowiczm.foodyou.goals.domain.entity.WeeklyGoals
import com.maksimowiczm.foodyou.goals.domain.repository.GoalsRepository
import com.maksimowiczm.foodyou.sync.domain.SyncEngine
import com.maksimowiczm.foodyou.sync.domain.SyncException
import com.maksimowiczm.foodyou.sync.domain.SyncPreferences
import com.maksimowiczm.foodyou.sync.domain.SyncResult
import com.maksimowiczm.foodyou.sync.domain.SyncTokenRepository
import com.maksimowiczm.foodyou.sync.infrastructure.api.FoodEntryDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.GoalsDto
import com.maksimowiczm.foodyou.sync.infrastructure.api.SyncApi
import com.maksimowiczm.foodyou.sync.infrastructure.api.SyncConnection
import com.maksimowiczm.foodyou.sync.infrastructure.room.SyncEntryMappingDao
import com.maksimowiczm.foodyou.sync.infrastructure.room.SyncEntryMappingEntity
import com.maksimowiczm.foodyou.sync.infrastructure.room.SyncReadDao
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalTime
import kotlinx.serialization.SerializationException

internal class DefaultSyncEngine(
    private val syncReadDao: SyncReadDao,
    private val mappingDao: SyncEntryMappingDao,
    private val manualEntryRepository: ManualDiaryEntryRepository,
    // Writes go through the DAO, not the repository: pulled entries must be stored with the server's
    // exact created/updated timestamps, but the repository's insert() forces updatedAt = createdAt.
    private val manualEntryDao: ManualDiaryEntryDao,
    private val foodEntryRepository: FoodDiaryEntryRepository,
    private val mealRepository: MealRepository,
    private val goalsRepository: GoalsRepository,
    private val transactionProvider: TransactionProvider,
    private val api: SyncApi,
    private val mapper: SyncMapper,
    private val preferencesRepository: UserPreferencesRepository<SyncPreferences>,
    private val tokenRepository: SyncTokenRepository,
    private val uuidFactory: () -> String = ::randomUuid,
    private val readTimeoutMs: Long = 5_000L,
) : SyncEngine {

    override suspend fun testConnection(baseUrl: String, token: String): Boolean =
        runCatching { api.health(SyncConnection(baseUrl.trim(), token.trim())) }.getOrDefault(false)

    override suspend fun sync(): SyncResult {
        val preferences = preferencesRepository.observe().first()
        if (!preferences.enabled || preferences.serverUrl.isBlank()) return SyncResult.Disabled
        val token = tokenRepository.getToken()
        if (token.isNullOrBlank()) return SyncResult.Failure("Sync token is not set", retryable = false)
        val connection = SyncConnection(preferences.serverUrl.trim(), token)
        val meals = mealRepository.observeMeals().first()

        // Push and pull are independent: a push failure must not block the pull, and vice versa.
        val errors = mutableListOf<Throwable>()
        var pushed = 0
        var pulled = 0
        var deleted = 0

        try {
            pushed = pushLocalChanges(connection, meals)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors += e
        }

        try {
            val (p, d) = pullRemoteChanges(connection, preferences.cursor, meals)
            pulled = p
            deleted = d
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors += e
        }

        try {
            syncGoals(connection)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors += e
        }

        return if (errors.isEmpty()) {
            SyncResult.Success(pushed = pushed, pulled = pulled, deleted = deleted)
        } else {
            SyncResult.Failure(
                message = errors.first().message ?: "Sync failed",
                retryable = errors.all { it.isTransient() },
            )
        }
    }

    /** Push new/changed local entries as upserts and vanished ones as tombstones. */
    private suspend fun pushLocalChanges(connection: SyncConnection, meals: List<Meal>): Int {
        val upserts = mutableListOf<FoodEntryDto>()
        val reservations = mutableListOf<SyncEntryMappingEntity>()

        val manualRefs = syncReadDao.manualEntryRefs()
        val manualIds = manualRefs.mapTo(mutableSetOf()) { it.id }
        for (ref in manualRefs) {
            isolate {
                val entry =
                    manualEntryRepository.observe(ManualDiaryEntryId(ref.id)).first()
                        ?: return@isolate
                collectPush(TABLE_MANUAL, ref.id, entry, meals, upserts, reservations)
            }
        }

        val measurementRefs = syncReadDao.measurementRefs()
        val measurementIds = measurementRefs.mapTo(mutableSetOf()) { it.id }
        for (ref in measurementRefs) {
            isolate {
                // observe() filterNotNull()s, so a since-deleted row would suspend forever; bound it.
                val entry =
                    withTimeoutOrNull(readTimeoutMs) {
                        foodEntryRepository.observe(FoodDiaryEntryId(ref.id)).first()
                    } ?: return@isolate
                collectPush(TABLE_MEASUREMENT, ref.id, entry, meals, upserts, reservations)
            }
        }

        // Reserve uuids BEFORE the push (contentHash = null = "pushed state unknown"). If the
        // response is lost after the server committed, the same uuid is re-pushed next cycle and the
        // server's upsert-by-id makes it idempotent — no duplicate rows.
        reservations.forEach { mappingDao.upsert(it.copy(contentHash = null)) }
        if (upserts.isNotEmpty()) api.push(connection, upserts)
        // Push accepted — finalize the reservations with their real content hash.
        reservations.forEach { mappingDao.upsert(it) }

        val tombstones =
            mappingDao.getAll().filterNot { mapping ->
                when (mapping.localTable) {
                    TABLE_MANUAL -> mapping.localId in manualIds
                    TABLE_MEASUREMENT -> mapping.localId in measurementIds
                    else -> true
                }
            }
        for (mapping in tombstones) {
            isolate {
                api.delete(connection, mapping.uuid)
                mappingDao.deleteByUuid(mapping.uuid)
            }
        }
        return upserts.size + tombstones.size
    }

    private suspend fun collectPush(
        table: String,
        localId: Long,
        entry: DiaryEntry,
        meals: List<Meal>,
        upserts: MutableList<FoodEntryDto>,
        reservations: MutableList<SyncEntryMappingEntity>,
    ) {
        val existing = mappingDao.getByLocal(table, localId)
        val uuid = existing?.uuid ?: uuidFactory()
        val dto = mapper.toDto(entry, mealNameOf(entry.mealId, meals), uuid)
        val hash = mapper.contentHash(dto)
        // Push new content, genuinely-changed content, or a reservation with an unknown outcome.
        if (existing == null || existing.contentHash == null || existing.contentHash != hash) {
            upserts += dto
            reservations +=
                SyncEntryMappingEntity(
                    uuid = uuid,
                    localTable = table,
                    localId = localId,
                    contentHash = hash,
                    serverUpdatedAt = existing?.serverUpdatedAt ?: 0L,
                )
        }
    }

    private suspend fun pullRemoteChanges(
        connection: SyncConnection,
        cursor: String?,
        meals: List<Meal>,
    ): Pair<Int, Int> {
        val response = api.pull(connection, cursor)
        val liveMeals = meals.toMutableList() // grows if a pull creates a meal, without re-reading
        var pulled = 0
        var deleted = 0
        for (dto in response.entries) {
            isolate {
                val uuid = dto.id ?: return@isolate
                val existing = mappingDao.getByUuid(uuid)

                if (dto.deleted) {
                    if (existing != null) {
                        deleteLocal(existing)
                        mappingDao.deleteByUuid(uuid)
                        deleted++
                    }
                    return@isolate
                }

                val serverUpdatedAt = dto.updatedAt?.let { Instant.parse(it).epochSeconds } ?: 0L
                when {
                    existing == null -> {
                        applyNewServerEntry(dto, uuid, serverUpdatedAt, liveMeals)
                        pulled++
                    }
                    existing.localTable == TABLE_MANUAL ->
                        pulled += applyManualUpdate(dto, existing, serverUpdatedAt, liveMeals)
                    else ->
                        // Measurement (ruling A): app owns structured food entries; never overwrite
                        // from the server. Record serverUpdatedAt so it isn't re-examined next cycle.
                        mappingDao.upsert(existing.copy(serverUpdatedAt = serverUpdatedAt))
                }
            }
        }
        preferencesRepository.update { copy(cursor = response.syncedAt) }
        return pulled to deleted
    }

    /** Materialize a new server entry as a manual row, atomically with its mapping. */
    private suspend fun applyNewServerEntry(
        dto: FoodEntryDto,
        uuid: String,
        serverUpdatedAt: Long,
        meals: MutableList<Meal>,
    ) {
        val meal = resolveMeal(dto.meal, meals)
        val storedHash = mapper.localManualContentHash(dto, meal.name)
        transactionProvider.withTransaction {
            val localId = manualEntryDao.insert(mapper.toManualEntity(dto, meal.id))
            mappingDao.upsert(
                SyncEntryMappingEntity(uuid, TABLE_MANUAL, localId, storedHash, serverUpdatedAt)
            )
        }
    }

    /** LWW for a manual entry; returns 1 if the local row was updated, else 0. */
    private suspend fun applyManualUpdate(
        dto: FoodEntryDto,
        existing: SyncEntryMappingEntity,
        serverUpdatedAt: Long,
        meals: MutableList<Meal>,
    ): Int {
        val meal = resolveMeal(dto.meal, meals)
        // Compare against the LOCAL push-form hash so brand/quantity/casing differences that the
        // local row can't represent don't count as a change (loop closure, meal casing).
        val incomingHash = mapper.localManualContentHash(dto, meal.name)
        if (existing.contentHash != null && existing.contentHash == incomingHash) {
            mappingDao.upsert(existing.copy(serverUpdatedAt = serverUpdatedAt))
            return 0
        }
        val local = manualEntryDao.observe(existing.localId).first()
        return if (local != null && serverUpdatedAt > local.updatedEpochSeconds) {
            transactionProvider.withTransaction {
                manualEntryDao.update(mapper.toManualEntity(dto, meal.id, existing.localId))
                mappingDao.upsert(
                    existing.copy(contentHash = incomingHash, serverUpdatedAt = serverUpdatedAt)
                )
            }
            1
        } else {
            // Local is newer (already pushed this cycle) — keep local, record server timestamp.
            mappingDao.upsert(existing.copy(serverUpdatedAt = serverUpdatedAt))
            0
        }
    }

    private suspend fun deleteLocal(mapping: SyncEntryMappingEntity) {
        when (mapping.localTable) {
            TABLE_MANUAL -> manualEntryDao.delete(mapping.localId)
            TABLE_MEASUREMENT -> foodEntryRepository.delete(FoodDiaryEntryId(mapping.localId))
        }
    }

    /** Goals sync in single-goal mode only; snapshot-based LWW, app wins ties (ruling B). */
    private suspend fun syncGoals(connection: SyncConnection) {
        val weekly = goalsRepository.observeWeeklyGoals().first()
        if (weekly.useSeparateGoals) return

        val snapshot = preferencesRepository.observe().first().goalsSnapshot()
        val localGoal = mapper.toGoalsDto(weekly.monday.macronutrientGoal)
        val serverGoal = runCatching { api.getGoals(connection) }.getOrNull()

        when {
            snapshot != null ->
                // Established relationship: app wins on a local change, else accept server changes.
                if (localGoal != snapshot) {
                    api.setGoals(connection, localGoal)
                    saveGoalsSnapshot(localGoal)
                } else if (serverGoal != null && !serverGoal.isEmpty && serverGoal != snapshot) {
                    convergeServerGoal(connection, weekly, localGoal, serverGoal)
                }
            // First contact: if the server already has any goal set (e.g. Claude configured it),
            // it wins — don't clobber it with the app's defaults.
            serverGoal != null && !serverGoal.isEmpty ->
                convergeServerGoal(connection, weekly, localGoal, serverGoal)
            // Server never had goals (or is unreachable): seed it from the app.
            else -> {
                api.setGoals(connection, localGoal)
                saveGoalsSnapshot(localGoal)
            }
        }
    }

    /**
     * Apply a non-empty server goal and converge both sides. The server's set_goals is
     * merge-semantics, so a server row can be PARTIAL (some macros set, some null) — which
     * [SyncMapper.toMacronutrientGoal] can't materialize. Overlay the server's set fields onto the
     * (always-complete) local goal to form a complete goal, apply it, and — only when the server row
     * was partial — push the completed goal back so the server row becomes complete too. A complete
     * server goal is already converged, so no push-back. Without this, a partial server goal is never
     * applied and the snapshot is never written: goals sync stalemates silently, forever.
     */
    private suspend fun convergeServerGoal(
        connection: SyncConnection,
        weekly: WeeklyGoals,
        localGoal: GoalsDto,
        serverGoal: GoalsDto,
    ) {
        val merged = serverGoal.overlayOnto(localGoal)
        if (!applyServerGoals(weekly, merged)) return
        if (!serverGoal.isComplete) api.setGoals(connection, merged)
        saveGoalsSnapshot(merged)
    }

    /** Overlay this goal's explicitly-set (non-null) fields onto [local]; server wins field-wise. */
    private fun GoalsDto.overlayOnto(local: GoalsDto): GoalsDto =
        GoalsDto(
            kcal = kcal ?: local.kcal,
            proteinG = proteinG ?: local.proteinG,
            carbsG = carbsG ?: local.carbsG,
            fatG = fatG ?: local.fatG,
        )

    /** Returns true if a complete server goal was applied locally. */
    private suspend fun applyServerGoals(weekly: WeeklyGoals, dto: GoalsDto): Boolean {
        val goal = mapper.toMacronutrientGoal(dto) ?: return false
        val daily = weekly.monday.copy(macronutrientGoal = goal)
        goalsRepository.updateWeeklyGoals(
            WeeklyGoals(
                useSeparateGoals = false,
                monday = daily,
                tuesday = daily,
                wednesday = daily,
                thursday = daily,
                friday = daily,
                saturday = daily,
                sunday = daily,
            )
        )
        return true
    }

    private suspend fun saveGoalsSnapshot(dto: GoalsDto) {
        preferencesRepository.update {
            copy(
                goalKcal = dto.kcal,
                goalProteinG = dto.proteinG,
                goalCarbsG = dto.carbsG,
                goalFatG = dto.fatG,
            )
        }
    }

    /**
     * Find a meal by name (case-insensitive) or create an "any time" one (ruling C). Meal creation
     * is intentionally outside the entry transaction — a meal is idempotent by name, so a stray one
     * from a failed entry insert is harmless, and this avoids reading a Room Flow inside a write
     * transaction. [meals] is updated in place so a later entry in the same pull reuses it.
     */
    private suspend fun resolveMeal(mealName: String, meals: MutableList<Meal>): Meal {
        meals.firstOrNull { it.name.equals(mealName, ignoreCase = true) }?.let { return it }
        mealRepository.insertMealWithLastRank(mealName, MEAL_START, MEAL_END)
        val created =
            mealRepository.observeMeals().first().first {
                it.name.equals(mealName, ignoreCase = true)
            }
        meals += created
        return created
    }

    private fun mealNameOf(mealId: Long, meals: List<Meal>): String =
        meals.firstOrNull { it.id == mealId }?.name ?: DEFAULT_MEAL

    private fun SyncPreferences.goalsSnapshot(): GoalsDto? =
        if (goalKcal != null && goalProteinG != null && goalCarbsG != null && goalFatG != null) {
            GoalsDto(goalKcal, goalProteinG, goalCarbsG, goalFatG)
        } else {
            null
        }

    /** Transient (network/timeout/5xx) failures are retried; terminal ones surface the error. */
    private fun Throwable.isTransient(): Boolean =
        when (this) {
            is SyncException -> retryable
            is SerializationException -> false
            else -> true
        }

    /** Per-item isolation: a single bad row/entry is skipped without aborting the whole phase. */
    private inline fun isolate(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Skip this item; the phase (and the cursor) still advances.
        }
    }

    private companion object {
        const val TABLE_MANUAL = "ManualDiaryEntry"
        const val TABLE_MEASUREMENT = "Measurement"
        const val DEFAULT_MEAL = "Snacks"
        val MEAL_START = LocalTime(0, 0)
        val MEAL_END = LocalTime(23, 59)
    }
}
