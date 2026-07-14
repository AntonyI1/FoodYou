package com.maksimowiczm.foodyou.sync.infrastructure.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import com.maksimowiczm.foodyou.common.config.NetworkConfig
import com.maksimowiczm.foodyou.sync.domain.SyncException
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class KtorSyncApiTest {

    private val connection = SyncConnection("http://server:8365", "tok")

    @Test
    fun pull_sendsAuthorizedGetAndParsesResponse() = runBlocking {
        lateinit var captured: HttpRequestData
        val api = apiReturning({ captured = it }) {
            respondJson(
                """
                {"entries":[{"id":"s1","date":"2026-07-13","meal":"Lunch","name":"Sandwich",
                "quantity":{"amount":1.0,"unit":"serving"},"nutrients":{"kcal":500.0,"protein_g":30.0},
                "source":"claude","created_at":"2026-07-13T08:00:00Z",
                "updated_at":"2026-07-13T09:00:00Z","deleted":false}],
                "synced_at":"2026-07-13T10:00:00Z"}
                """
                    .trimIndent()
            )
        }

        val response = api.pull(connection, "2026-01-01T00:00:00Z")

        assertEquals("2026-07-13T10:00:00Z", response.syncedAt)
        val entry = response.entries.single()
        assertEquals("s1", entry.id)
        assertEquals(500.0, entry.nutrients.kcal)
        assertEquals(30.0, entry.nutrients.proteinG)
        assertEquals(HttpMethod.Get, captured.method)
        assertTrue(captured.url.encodedPath.endsWith("/api/v1/entries"))
        assertEquals("Bearer tok", captured.headers[HttpHeaders.Authorization])
        assertEquals("true", captured.url.parameters["include_deleted"])
        assertEquals("2026-01-01T00:00:00Z", captured.url.parameters["updated_since"])
    }

    @Test
    fun push_sendsPostToEntriesWithBearer() = runBlocking {
        lateinit var captured: HttpRequestData
        val api = apiReturning({ captured = it }) { respond("", HttpStatusCode.OK) }

        api.push(connection, listOf(sampleEntry()))

        assertEquals(HttpMethod.Post, captured.method)
        assertTrue(captured.url.encodedPath.endsWith("/api/v1/entries"))
        assertEquals("Bearer tok", captured.headers[HttpHeaders.Authorization])
    }

    @Test
    fun delete_sendsDeleteToEntryIdWithBearer() = runBlocking {
        lateinit var captured: HttpRequestData
        val api = apiReturning({ captured = it }) { respond("", HttpStatusCode.OK) }

        api.delete(connection, "abc-123")

        assertEquals(HttpMethod.Delete, captured.method)
        assertTrue(captured.url.encodedPath.endsWith("/api/v1/entries/abc-123"))
        assertEquals("Bearer tok", captured.headers[HttpHeaders.Authorization])
    }

    @Test
    fun getGoals_parsesResponse() = runBlocking {
        val api = apiReturning({}) {
            respondJson("""{"kcal":2400.0,"protein_g":180.0,"carbs_g":250.0,"fat_g":80.0}""")
        }

        val goals = api.getGoals(connection)

        assertEquals(GoalsDto(2400.0, 180.0, 250.0, 80.0), goals)
    }

    @Test
    fun setGoals_sendsPutToGoals() = runBlocking {
        lateinit var captured: HttpRequestData
        val api = apiReturning({ captured = it }) { respond("", HttpStatusCode.OK) }

        api.setGoals(connection, GoalsDto(2400.0, 180.0, 250.0, 80.0))

        assertEquals(HttpMethod.Put, captured.method)
        assertTrue(captured.url.encodedPath.endsWith("/api/v1/goals"))
        assertEquals("Bearer tok", captured.headers[HttpHeaders.Authorization])
    }

    @Test
    fun health_reflectsHttpStatus() = runBlocking {
        val healthy = apiReturning({}) { respond("", HttpStatusCode.OK) }
        val down = apiReturning({}) { respond("", HttpStatusCode.InternalServerError) }

        assertTrue(healthy.health(connection))
        assertFalse(down.health(connection))
    }

    // finding 10c: a 401 surfaces as a typed error, not a serialization failure on the error body.
    @Test
    fun unauthorized_throwsTypedError() = runBlocking {
        val api =
            apiReturning({}) {
                respond(
                    """{"error":"unauthorized"}""",
                    HttpStatusCode.Unauthorized,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        assertFailsWith<SyncException.Unauthorized> { api.pull(connection, null) }
        Unit
    }

    // finding 10d: deleting an already-gone entry is success (the tombstone goal is met).
    @Test
    fun delete_notFound_isTreatedAsSuccess() = runBlocking {
        val api = apiReturning({}) { respond("", HttpStatusCode.NotFound) }

        api.delete(connection, "already-gone") // must not throw
    }

    // finding 5: goals with null fields parse (server returns null for unset targets).
    @Test
    fun getGoals_parsesNullFields() = runBlocking {
        val api =
            apiReturning({}) {
                respondJson("""{"kcal":null,"protein_g":null,"carbs_g":null,"fat_g":null}""")
            }

        assertEquals(GoalsDto(), api.getGoals(connection))
    }

    private fun apiReturning(
        capture: (HttpRequestData) -> Unit,
        handler: io.ktor.client.engine.mock.MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData,
    ): KtorSyncApi {
        val client =
            HttpClient(
                MockEngine { request ->
                    capture(request)
                    handler()
                }
            ) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        return KtorSyncApi(client, FakeNetworkConfig)
    }

    private object FakeNetworkConfig : NetworkConfig {
        override val userAgent = "FoodYou-Test"
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun sampleEntry() =
        FoodEntryDto(
            id = "c1",
            date = "2026-07-13",
            meal = "Lunch",
            name = "Chicken",
            quantity = QuantityDto(1.0, "serving"),
            nutrients = NutrientsDto(kcal = 500.0),
            source = "app",
            createdAt = "2026-07-13T08:00:00Z",
            updatedAt = "2026-07-13T08:00:00Z",
            deleted = false,
        )
}
