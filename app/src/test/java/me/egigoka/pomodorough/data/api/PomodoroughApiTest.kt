package me.egigoka.pomodorough.data.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.SyncRequest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PomodoroughApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PomodoroughApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = PomodoroughApi(
            baseUrl = server.url("/api/v1/").toString(),
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun challengeUsesExpectedEndpointAndBody() = runTest {
        server.enqueue(jsonResponse("""{
            "challenge":"sealed",
            "nonce":"nonce",
            "expiresAt":"2026-01-01T00:05:00Z"
        }"""))

        val challenge = api.createChallenge()
        val request = server.takeRequest()

        assertEquals("sealed", challenge.challenge)
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/google/challenge", request.path)
        assertEquals("{}", request.body.readUtf8())
        assertEquals("no-store", request.getHeader("Cache-Control"))
    }

    @Test
    fun syncSendsBearerTokenAndRequestPayload() = runTest {
        server.enqueue(jsonResponse("""{
            "acknowledgements":[],
            "revision":8,
            "canonicalTimer":null,
            "history":[],
            "serverTime":"2026-01-01T00:00:00Z",
            "serverHlcWallMs":1767225600000
        }"""))

        val response = api.sync("access-token", SyncRequest("device-1", 7, emptyList()))
        val request = server.takeRequest()
        val body = request.body.readUtf8()

        assertEquals(8, response.revision)
        assertEquals("Bearer access-token", request.getHeader("Authorization"))
        assertEquals("/api/v1/sync", request.path)
        assertTrue(body.contains("\"deviceId\":\"device-1\""))
        assertTrue(body.contains("\"lastRevision\":7"))
        assertTrue(body.contains("\"commands\":[]"))
    }

    @Test
    fun structuredErrorBecomesApiException() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"device mismatch"}"""),
        )

        val error = capture<ApiException> { api.me("token") }

        assertEquals(403, error.statusCode)
        assertEquals("device mismatch", error.message)
    }

    @Test
    fun malformedErrorUsesStatusFallback() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("not-json"))

        val error = capture<ApiException> { api.me("token") }

        assertEquals(502, error.statusCode)
        assertEquals("Request failed (502)", error.message)
    }

    @Test
    fun emptySuccessfulJsonResponseIsRejected() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val error = capture<java.io.IOException> { api.me("token") }

        assertEquals("Server returned an empty response", error.message)
    }

    @Test
    fun logoutPostsEmptyAuthorizedRequest() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        api.logout("logout-token")
        val request = server.takeRequest()

        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/logout", request.path)
        assertEquals(0, request.bodySize)
        assertEquals("Bearer logout-token", request.getHeader("Authorization"))
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private suspend inline fun <reified T : Throwable> capture(crossinline block: suspend () -> Unit): T {
        return try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            assertTrue("Expected ${T::class.java.name}, got ${error::class.java.name}", error is T)
            error as T
        }
    }
}
