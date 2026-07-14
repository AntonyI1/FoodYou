package com.maksimowiczm.foodyou.sync.infrastructure.api

import com.maksimowiczm.foodyou.common.config.NetworkConfig
import com.maksimowiczm.foodyou.sync.domain.SyncException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.userAgent

internal class KtorSyncApi(
    private val client: HttpClient,
    private val networkConfig: NetworkConfig,
) : SyncApi {

    override suspend fun health(connection: SyncConnection): Boolean =
        client.get(url(connection, "health")) { common(connection, auth = false) }.status.isSuccess()

    override suspend fun pull(
        connection: SyncConnection,
        updatedSince: String?,
    ): EntriesResponseDto =
        client
            .get(url(connection, "entries")) {
                common(connection)
                parameter("include_deleted", "true")
                updatedSince?.let { parameter("updated_since", it) }
            }
            .checked()
            .body()

    override suspend fun push(connection: SyncConnection, entries: List<FoodEntryDto>) {
        client
            .post(url(connection, "entries")) {
                common(connection)
                contentType(ContentType.Application.Json)
                setBody(BulkEntriesDto(entries))
            }
            .checked()
    }

    override suspend fun delete(connection: SyncConnection, id: String) {
        val response = client.delete(url(connection, "entries/$id")) { common(connection) }
        // Already gone is success — the tombstone goal is achieved (item 10d).
        if (response.status == HttpStatusCode.NotFound) return
        response.checked()
    }

    override suspend fun getGoals(connection: SyncConnection): GoalsDto =
        client.get(url(connection, "goals")) { common(connection) }.checked().body()

    override suspend fun setGoals(connection: SyncConnection, goals: GoalsDto) {
        client
            .put(url(connection, "goals")) {
                common(connection)
                contentType(ContentType.Application.Json)
                setBody(goals)
            }
            .checked()
    }

    private fun HttpRequestBuilder.common(connection: SyncConnection, auth: Boolean = true) {
        userAgent(networkConfig.userAgent)
        if (auth) bearerAuth(connection.token)
    }

    /**
     * Map non-2xx to typed errors so a 401 surfaces as "unauthorized" rather than a cryptic
     * serialization failure from trying to parse the error body as a success DTO (item 10c).
     */
    private fun HttpResponse.checked(): HttpResponse {
        if (status.isSuccess()) return this
        throw when {
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
                SyncException.Unauthorized()
            status.value in 500..599 -> SyncException.ServerError(status.value)
            else -> SyncException.ClientError(status.value)
        }
    }

    private fun url(connection: SyncConnection, path: String) =
        "${connection.baseUrl.trimEnd('/')}/api/v1/$path"
}
