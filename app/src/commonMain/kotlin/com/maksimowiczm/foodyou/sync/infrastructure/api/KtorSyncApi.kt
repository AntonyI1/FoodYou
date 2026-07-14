package com.maksimowiczm.foodyou.sync.infrastructure.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

internal class KtorSyncApi(private val client: HttpClient) : SyncApi {

    override suspend fun health(connection: SyncConnection): Boolean =
        client.get(url(connection, "health")).status.isSuccess()

    override suspend fun pull(
        connection: SyncConnection,
        updatedSince: String?,
    ): EntriesResponseDto =
        client
            .get(url(connection, "entries")) {
                bearerAuth(connection.token)
                parameter("include_deleted", "true")
                updatedSince?.let { parameter("updated_since", it) }
            }
            .body()

    override suspend fun push(connection: SyncConnection, entries: List<FoodEntryDto>) {
        val response =
            client.post(url(connection, "entries")) {
                bearerAuth(connection.token)
                contentType(ContentType.Application.Json)
                setBody(BulkEntriesDto(entries))
            }
        check(response.status.isSuccess()) { "Push failed: ${response.status}" }
    }

    override suspend fun getGoals(connection: SyncConnection): GoalsDto =
        client.get(url(connection, "goals")) { bearerAuth(connection.token) }.body()

    override suspend fun setGoals(connection: SyncConnection, goals: GoalsDto) {
        val response =
            client.put(url(connection, "goals")) {
                bearerAuth(connection.token)
                contentType(ContentType.Application.Json)
                setBody(goals)
            }
        check(response.status.isSuccess()) { "Set goals failed: ${response.status}" }
    }

    private fun url(connection: SyncConnection, path: String) =
        "${connection.baseUrl.trimEnd('/')}/api/v1/$path"
}
