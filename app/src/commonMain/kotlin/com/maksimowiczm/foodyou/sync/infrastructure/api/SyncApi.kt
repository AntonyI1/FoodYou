package com.maksimowiczm.foodyou.sync.infrastructure.api

/** Where and how to reach the self-hosted server for one sync run. */
data class SyncConnection(val baseUrl: String, val token: String)

/**
 * Thin transport over the server's REST contract (DESIGN §4). All calls except [health] send the
 * bearer token. Implementations do no domain logic — mapping and reconciliation live in the engine.
 */
interface SyncApi {

    /** `GET /api/v1/health` (no auth). True when the server is reachable and healthy. */
    suspend fun health(connection: SyncConnection): Boolean

    /** Sync pull: `GET /api/v1/entries?updated_since&include_deleted=true`. */
    suspend fun pull(connection: SyncConnection, updatedSince: String?): EntriesResponseDto

    /** Bulk upsert push: `POST /api/v1/entries` with `{entries:[...]}`. */
    suspend fun push(connection: SyncConnection, entries: List<FoodEntryDto>)

    /** `GET /api/v1/goals`. */
    suspend fun getGoals(connection: SyncConnection): GoalsDto

    /** `PUT /api/v1/goals`. */
    suspend fun setGoals(connection: SyncConnection, goals: GoalsDto)
}
