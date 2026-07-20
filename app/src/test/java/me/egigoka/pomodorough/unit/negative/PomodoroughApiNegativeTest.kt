package me.egigoka.pomodorough.unit.negative

import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.api.PomodoroughApi
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PomodoroughApiNegativeTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PomodoroughApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = PomodoroughApi(
            baseUrl = server.url("/api/v1").toString(),
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun malformedSuccessfulPayloadIsRejected() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"accessToken":12}"""),
        )

        capture<SerializationException> { api.refresh("refresh-token") }
    }

    @Test
    fun truncatedResponseBodyIsRejected() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"user":{"id":"user-1"}}""")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        capture<IOException> { api.me("access-token") }
    }

    @Test
    fun unstructuredErrorDoesNotExposeResponseBody() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("internal implementation detail"),
        )

        val error = capture<ApiException> { api.me("access-token") }

        assertEquals(500, error.statusCode)
        assertEquals("Request failed (500)", error.message)
    }

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
