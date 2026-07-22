package me.egigoka.pomodorough.data.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.BootstrapResolutionRequest
import me.egigoka.pomodorough.data.BootstrapStrategy
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
            "durationAcknowledgements":[],
            "durationsMs":{"focus":1500000,"short_break":300000,"long_break":900000},
            "taskAcknowledgements":[],
            "tasks":[],
            "serverTime":"2026-01-01T00:00:00Z",
            "serverHlcWallMs":1767225600000,
            "serverHlcCounter":7
        }"""))

        val response = api.sync("access-token", SyncRequest("device-1", 7, emptyList(), emptyList()))
        val request = server.takeRequest()
        val body = request.body.readUtf8()

        assertEquals(8, response.revision)
        assertEquals(7, response.serverHlcCounter)
        assertEquals("Bearer access-token", request.getHeader("Authorization"))
        assertEquals("/api/v1/sync", request.path)
        assertTrue(body.contains("\"deviceId\":\"device-1\""))
        assertTrue(body.contains("\"lastRevision\":7"))
        assertTrue(body.contains("\"commands\":[]"))
        assertTrue(body.contains("\"durationOperations\":[]"))
    }

    @Test
    fun bootstrapIsReadOnlyAuthorizedGet() = runTest {
        server.enqueue(jsonResponse(canonicalResponse(revision = 12)))

        val response = api.bootstrap("access-token")
        val request = server.takeRequest()

        assertEquals(12L, response.revision)
        assertTrue(response.acknowledgements.isEmpty())
        assertEquals("GET", request.method)
        assertEquals("/api/v1/bootstrap", request.path)
        assertEquals("Bearer access-token", request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun bootstrapResolutionSendsExactRequiredEnvelope() = runTest {
        server.enqueue(jsonResponse(canonicalResponse(revision = 13)))
        val requestModel = BootstrapResolutionRequest(
            requestId = "bootstrap-request-1",
            deviceId = "device-1",
            expectedRevision = 12,
            strategy = BootstrapStrategy.Merge,
            commands = emptyList(),
            taskOperations = emptyList(),
            durationOperations = emptyList(),
        )

        api.resolveBootstrap("access-token", requestModel)
        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject

        assertEquals("POST", request.method)
        assertEquals("/api/v1/bootstrap/resolve", request.path)
        assertEquals(
            setOf(
                "requestId",
                "deviceId",
                "expectedRevision",
                "strategy",
                "commands",
                "taskOperations",
                "durationOperations",
            ),
            body.keys,
        )
        assertEquals("merge", body.getValue("strategy").jsonPrimitive.content)
        assertTrue(body.getValue("commands").jsonArray.isEmpty())
        assertTrue(body.getValue("taskOperations").jsonArray.isEmpty())
        assertTrue(body.getValue("durationOperations").jsonArray.isEmpty())
    }

    @Test
    fun bootstrapRevisionConflictHasStructuredType() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"revision_conflict"}"""),
        )
        val request = BootstrapResolutionRequest(
            requestId = "bootstrap-request-1",
            deviceId = "device-1",
            expectedRevision = 12,
            strategy = BootstrapStrategy.KeepRemote,
            commands = emptyList(),
            taskOperations = emptyList(),
            durationOperations = emptyList(),
        )

        val error = capture<BootstrapConflictException> {
            api.resolveBootstrap("access-token", request)
        }

        assertEquals(BootstrapConflictKind.Revision, error.kind)
        assertEquals(409, error.statusCode)
        assertEquals("revision_conflict", error.message)
    }

    @Test
    fun durationSyncUsesExactWireKeysAndDecodesCanonicalDurations() = runTest {
        server.enqueue(jsonResponse("""{
            "acknowledgements":[],
            "durationAcknowledgements":[{"operationId":"duration-1","outcome":"applied","reason":""}],
            "revision":8,
            "canonicalTimer":null,
            "history":[],
            "durationsMs":{"focus":1800000,"short_break":360000,"long_break":1200000},
            "taskAcknowledgements":[],
            "tasks":[],
            "serverTime":"2026-01-01T00:00:00Z",
            "serverHlcWallMs":1767225600000,
            "serverHlcCounter":0
        }"""))
        val operation = DurationOperation(
            id = "duration-1",
            phase = "short_break",
            durationMs = 360_000,
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = 1_767_225_600_000,
            hlcCounter = 2,
        )

        val response = api.sync(
            "access-token",
            SyncRequest("device-1", 7, emptyList(), listOf(operation)),
        )
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val encoded = body.getValue("durationOperations").jsonArray.single().jsonObject

        assertEquals(
            setOf("id", "phase", "durationMs", "occurredAt", "hlcWallMs", "hlcCounter"),
            encoded.keys,
        )
        assertEquals(360_000, encoded.getValue("durationMs").jsonPrimitive.long)
        assertEquals("duration-1", response.durationAcknowledgements.single().operationId)
        assertEquals(1_800_000L, response.durationsMs.focus)
        assertEquals(360_000L, response.durationsMs.shortBreak)
        assertEquals(1_200_000L, response.durationsMs.longBreak)
    }

    @Test
    fun syncRejectsResponseWithoutCanonicalDurations() = runTest {
        server.enqueue(jsonResponse("""{
            "acknowledgements":[],
            "durationAcknowledgements":[],
            "revision":8,
            "canonicalTimer":null,
            "history":[],
            "taskAcknowledgements":[],
            "tasks":[],
            "serverTime":"2026-01-01T00:00:00Z",
            "serverHlcWallMs":1767225600000,
            "serverHlcCounter":0
        }"""))

        capture<SerializationException> {
            api.sync("access-token", SyncRequest("device-1", 7, emptyList(), emptyList()))
        }
    }

    @Test
    fun syncRejectsResponseWithoutDurationAcknowledgements() = runTest {
        server.enqueue(jsonResponse("""{
            "acknowledgements":[],
            "revision":8,
            "canonicalTimer":null,
            "history":[],
            "durationsMs":{"focus":1500000,"short_break":300000,"long_break":900000},
            "taskAcknowledgements":[],
            "tasks":[],
            "serverTime":"2026-01-01T00:00:00Z",
            "serverHlcWallMs":1767225600000,
            "serverHlcCounter":0
        }"""))

        capture<SerializationException> {
            api.sync("access-token", SyncRequest("device-1", 7, emptyList(), emptyList()))
        }
    }

    @Test
    fun syncRejectsResponseWithoutTaskAcknowledgements() = runTest {
        server.enqueue(jsonResponse("""{
            "acknowledgements":[],
            "durationAcknowledgements":[],
            "revision":8,
            "canonicalTimer":null,
            "history":[],
            "durationsMs":{"focus":1500000,"short_break":300000,"long_break":900000},
            "tasks":[],
            "serverTime":"2026-01-01T00:00:00Z",
            "serverHlcWallMs":1767225600000,
            "serverHlcCounter":0
        }"""))

        capture<SerializationException> {
            api.sync("access-token", SyncRequest("device-1", 7, emptyList(), emptyList()))
        }
    }

    @Test
    fun syncRejectsResponseWithoutCanonicalTasks() = runTest {
        server.enqueue(jsonResponse("""{
            "acknowledgements":[],
            "durationAcknowledgements":[],
            "taskAcknowledgements":[],
            "revision":8,
            "canonicalTimer":null,
            "history":[],
            "durationsMs":{"focus":1500000,"short_break":300000,"long_break":900000},
            "serverTime":"2026-01-01T00:00:00Z",
            "serverHlcWallMs":1767225600000,
            "serverHlcCounter":0
        }"""))

        capture<SerializationException> {
            api.sync("access-token", SyncRequest("device-1", 7, emptyList(), emptyList()))
        }
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

    private fun canonicalResponse(revision: Long) = """{
        "acknowledgements":[],
        "revision":$revision,
        "canonicalTimer":null,
        "history":[],
        "durationAcknowledgements":[],
        "durationsMs":{"focus":1500000,"short_break":300000,"long_break":900000},
        "taskAcknowledgements":[],
        "tasks":[],
        "serverTime":"2026-01-01T00:00:00Z",
        "serverHlcWallMs":1767225600000,
        "serverHlcCounter":0
    }"""

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
