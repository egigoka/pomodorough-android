package me.egigoka.pomodorough.unit.positive

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.NativeExchangeRequest
import me.egigoka.pomodorough.data.api.PomodoroughApi
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PomodoroughApiPositiveTest {
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
    fun exchangePostsNativeGooglePayloadWithoutAuthorization() = runTest {
        server.enqueue(tokenResponse())

        val tokens = api.exchange(NativeExchangeRequest("id-token", "challenge", "device-1", "android"))
        val request = server.takeRequest()

        assertEquals("access-token", tokens.accessToken)
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/google/exchange", request.path)
        assertNull(request.getHeader("Authorization"))
        assertEquals(
            """{"idToken":"id-token","challenge":"challenge","deviceId":"device-1","platform":"android"}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun refreshPostsRefreshTokenWithoutAuthorization() = runTest {
        server.enqueue(tokenResponse())

        api.refresh("refresh-secret")
        val request = server.takeRequest()

        assertEquals("/api/v1/auth/refresh", request.path)
        assertNull(request.getHeader("Authorization"))
        assertEquals("""{"refreshToken":"refresh-secret"}""", request.body.readUtf8())
    }

    @Test
    fun meUsesGetAndBearerAuthorization() = runTest {
        server.enqueue(
            jsonResponse(
                """{"user":{"id":"user-1","email":"u@example.com","name":"User","avatarUrl":""},"csrfToken":"csrf"}""",
            ),
        )

        val response = api.me("profile-access")
        val request = server.takeRequest()

        assertEquals("user-1", response.user.id)
        assertEquals("GET", request.method)
        assertEquals("/api/v1/me", request.path)
        assertEquals("Bearer profile-access", request.getHeader("Authorization"))
    }

    @Test
    fun revisionStreamDeliversServerSentEventOverAuthorizedGet() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: 9\n\n"),
        )
        val event = CountDownLatch(1)
        var data: String? = null
        val source = api.revisionStream("stream-access", object : EventSourceListener() {
            override fun onEvent(source: EventSource, id: String?, type: String?, value: String) {
                data = value
                event.countDown()
            }

            override fun onFailure(source: EventSource, t: Throwable?, response: Response?) {
                event.countDown()
            }
        })

        assertTrue("SSE event was not delivered", event.await(3, TimeUnit.SECONDS))
        val request = server.takeRequest(3, TimeUnit.SECONDS)

        source.cancel()
        assertEquals("9", data)
        assertEquals("GET", request?.method)
        assertEquals("/api/v1/stream", request?.path)
        assertEquals("Bearer stream-access", request?.getHeader("Authorization"))
    }

    private fun tokenResponse() = jsonResponse(
        """{
            "accessToken":"access-token",
            "accessTokenExpiresAt":"2999-01-01T00:00:00Z",
            "refreshToken":"refresh-token",
            "refreshTokenExpiresAt":"2999-02-01T00:00:00Z"
        }""",
    )

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
