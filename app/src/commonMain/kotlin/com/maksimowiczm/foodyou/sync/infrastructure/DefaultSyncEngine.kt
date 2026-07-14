package com.maksimowiczm.foodyou.sync.infrastructure

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
import kotlinx.datetime.LocalTime

internal class DefaultSyncEngine(
    private val syncReadDao: SyncReadDao,
    private val mappingDao: SyncEntryMappingDao,
    private val manualEntryRepository: ManualDiaryEntryRepository,
    private val manualEntryDao: ManualDiaryEntryDao,
    private val foodEntryRepository: FoodDiaryEntryRepository,
    private val mealRepository: MealRepository,
    private val goalsRepository: GoalsRepository,
    private val api: SyncApi,
    private val mapper: SyncMapper,
    private val preferencesRepository: UserPreferencesRepository<SyncPreferences>,
    private val tokenRepository: SyncTokenRepository,
    private val uuidFactory: () -> String = ::randomUuid,
) : SyncEngine {

    override suspend fun testConnection(baseUrl: String, token: String): Boolean =
        runCatching { api.health(SyncConnection(baseUrl.trim(), token.trim())) }.getOrDefault(false)

    override suspend fun sync(): SyncResult {
        val preferences = preferencesRepository.observe().first()
        if (!preferences.enabled || preferences.serverUrl.isBlank()) return SyncResult.Disabled
        val token = tokenRepository.getToken()
        if (token.isNullOrBlank()) return SyncResult.Failure("Sync token is not set")
        val connection = SyncConnection(preferences.serverUrl.trim(), token)

        return try {
            val pushed = pushLocalChanges(connection)
            val (pulled, deleted) = pullRemoteChanges(connection, preferences.cursor)
            syncGoals(connection)
            SyncResult.Success(pushed = pushed, pulled = pulled, deleted = deleted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Failure(e.message ?: "Sync failed")
        }
    }

    /** Push new/changed local entries as upserts and vanished ones as tombstones. */
    private suspend fun pushLocalChanges(connection: SyncConnection): Int {
        val meals = mealRepository.observeMeals().first()
        val upserts = mutableListOf<FoodEntryDto>()
        val mappingsToStore = mutableListOf<SyncEntryMappingEntity>()

        val manualRefs = syncReadDao.manualEntryRefs()
        val manualIds = manualRefs.mapTo(mutableSetOf()) { it.id }
        for (ref in manualRefs) {
            val entry = manualEntryRepository.observe(ManualDiaryEntryId(ref.id)).first() ?: continue
            collectPush(TABLE_MANUAL, ref.id, entry, meals, upserts, mappingsToStore)
        }

        val measurementRefs = syncReadDao.measurementRefs()
        val measurementIds = measurementRefs.mapTo(mutableSetOf()) { it.id }
        for (ref in measurementRefs) {
            val entry = foodEntryRepository.observe(FoodDiaryEntryId(ref.id)).first() ?: continue
            collectPush(TABLE_MEASUREMENT, ref.id, entry, meals, upserts, mappingsToStore)
        }

        val tombstoneUuids =
            mappingDao.getAll().filterNot { mapping ->
                when (mapping.localTable) {
                    TABLE_MANUAL -> mapping.localId in manualIds
                    TABLE_MEASUREMENT -> mapping.localId in measurementIds
                    else -> true
                }
            }

        if (upserts.isNotEmpty()) api.push(connection, upserts)
        mappingsToStore.forEach { mappingDao.upsert(it) }
        tombstoneUuids.forEach {
            api.delete(connection, it.uuid)
            mappingDao.deleteByUuid(it.uuid)
        }
        return upserts.size + tombstoneUuids.size
    }

    private suspend fun collectPush(
        table: String,
        localId: Long,
        entry: DiaryEntry,
        meals: List<Meal>,
        upserts: MutableList<FoodEntryDto>,
        mappingsToStore: MutableList<SyncEntryMappingEntity>,
    ) {
        val existing = mappingDao.getByLocal(table, localId)
        val uuid = existing?.uuid ?: uuidFactory()
        val dto = mapper.toDto(entry, mealNameOf(entry.mealId, meals), uuid)
        val hash = mapper.contentHash(dto)
        // Push only genuinely new or locally-changed content (loop closure + ruling A part 3).
        if (existing == null || existing.contentHash != hash) {
            upserts += dto
            mappingsToStore +=
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
    ): Pair<Int, Int> {
        val response = api.pull(connection, cursor)
        var pulled = 0
        var deleted = 0
        for (dto in response.entries) {
            val uuid = dto.id ?: continue
            val existing = mappingDao.getByUuid(uuid)

            if (dto.deleted) {
                if (existing != null) {
                    deleteLocal(existing)
                    mappingDao.deleteByUuid(uuid)
                    deleted++
                }
                continue
            }

            val hash = mapper.contentHash(dto)
            val serverUpdatedAt = dto.updatedAt?.let { Instant.parse(it).epochSeconds } ?: 0L
            when {
                existing == null -> {
                    val mealId = resolveMealId(dto.meal)
                    val localId = manualEntryDao.insert(mapper.toManualEntity(dto, mealId))
                    mappingDao.upsert(
                        SyncEntryMappingEntity(uuid, TABLE_MANUAL, localId, hash, serverUpdatedAt)
                    )
                    pulled++
                }
                existing.localTable == TABLE_MANUAL -> {
                    pulled += applyManualUpdate(dto, existing, hash, serverUpdatedAt)
                }
                else -> {
                    // Measurement (ruling A): app owns structured food entries; never overwrite
                    // from the server. Record serverUpdatedAt so it isn't re-examined next cycle.
                    mappingDao.upsert(existing.copy(serverUpdatedAt = serverUpdatedAt))
                }
            }
        }
        preferencesRepository.update { copy(cursor = response.syncedAt) }
        return pulled to deleted
    }

    /** LWW for a manual entry; returns 1 if the local row was updated, else 0. */
    private suspend fun applyManualUpdate(
        dto: FoodEntryDto,
        existing: SyncEntryMappingEntity,
        hash: String,
        serverUpdatedAt: Long,
    ): Int {
        if (existing.contentHash == hash) {
            // Unchanged / our own echo — just record the server timestamp.
            mappingDao.upsert(existing.copy(serverUpdatedAt = serverUpdatedAt))
            return 0
        }
        val local = manualEntryDao.observe(existing.localId).first()
        return if (local != null && serverUpdatedAt > local.updatedEpochSeconds) {
            val mealId = resolveMealId(dto.meal)
            manualEntryDao.update(mapper.toManualEntity(dto, mealId, existing.localId))
            mappingDao.upsert(existing.copy(contentHash = hash, serverUpdatedAt = serverUpdatedAt))
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

    /** Goals sync in single-goal mode only; snapshot-based LWW with app winning ties (ruling B). */
    private suspend fun syncGoals(connection: SyncConnection) {
        val weekly = goalsRepository.observeWeeklyGoals().first()
        if (weekly.useSeparateGoals) return

        val snapshot = preferencesRepository.observe().first().goalsSnapshot()
        val localGoal = mapper.toGoalsDto(weekly.monday.macronutrientGoal)
        val serverGoal = runCatching { api.getGoals(connection) }.getOrNull()

        when {
            snapshot == null || localGoal != snapshot -> {
                api.setGoals(connection, localGoal)
                saveGoalsSnapshot(localGoal)
            }
            serverGoal != null && serverGoal != snapshot -> {
                applyServerGoals(weekly, serverGoal)
                saveGoalsSnapshot(serverGoal)
            }
        }
    }

    private suspend fun applyServerGoals(weekly: WeeklyGoals, dto: GoalsDto) {
        val daily = weekly.monday.copy(macronutrientGoal = mapper.toMacronutrientGoal(dto))
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

    private suspend fun resolveMealId(mealName: String): Long {
        mealRepository.observeMeals().first().firstOrNull {
            it.name.equals(mealName, ignoreCase = true)
        }
            ?.let {
                return it.id
            }
        // Ruling C: unknown meal name creates an "any time" meal to preserve categorization.
        mealRepository.insertMealWithLastRank(mealName, LocalTime(0, 0), LocalTime(23, 59))
        return mealRepository.observeMeals().first().first {
            it.name.equals(mealName, ignoreCase = true)
        }
            .id
    }

    private fun mealNameOf(mealId: Long, meals: List<Meal>): String =
        meals.firstOrNull { it.id == mealId }?.name ?: DEFAULT_MEAL

    private fun SyncPreferences.goalsSnapshot(): GoalsDto? =
        if (goalKcal != null && goalProteinG != null && goalCarbsG != null && goalFatG != null) {
            GoalsDto(goalKcal, goalProteinG, goalCarbsG, goalFatG)
        } else {
            null
        }

    private companion object {
        const val TABLE_MANUAL = "ManualDiaryEntry"
        const val TABLE_MEASUREMENT = "Measurement"
        const val DEFAULT_MEAL = "Snacks"
    }
}
