package me.egigoka.pomodorough.data.api

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.ApiError
import me.egigoka.pomodorough.data.MeResponse
import me.egigoka.pomodorough.data.NativeChallenge
import me.egigoka.pomodorough.data.NativeExchangeRequest
import me.egigoka.pomodorough.data.RefreshRequest
import me.egigoka.pomodorough.data.SyncRequest
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.TokenPair
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

class ApiException(
    val statusCode: Int,
    message: String,
) : IOException(message)

interface PomodoroughService {
    suspend fun createChallenge(): NativeChallenge
    suspend fun exchange(request: NativeExchangeRequest): TokenPair
    suspend fun refresh(refreshToken: String): TokenPair
    suspend fun me(accessToken: String): MeResponse
    suspend fun sync(accessToken: String, request: SyncRequest): SyncResponse
    suspend fun logout(accessToken: String)
    fun revisionStream(accessToken: String, listener: EventSourceListener): EventSource
}

class PomodoroughApi(
    private val baseUrl: String,
    private val client: OkHttpClient,
    val json: Json,
) : PomodoroughService {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val eventSourceFactory = EventSources.createFactory(client)

    override suspend fun createChallenge(): NativeChallenge =
        post("auth/google/challenge", "{}", null)

    override suspend fun exchange(request: NativeExchangeRequest): TokenPair =
        post("auth/google/exchange", json.encodeToString(request), null)

    override suspend fun refresh(refreshToken: String): TokenPair =
        post("auth/refresh", json.encodeToString(RefreshRequest(refreshToken)), null)

    override suspend fun me(accessToken: String): MeResponse = get("me", accessToken)

    override suspend fun sync(accessToken: String, request: SyncRequest): SyncResponse =
        post("sync", json.encodeToString(request), accessToken)

    override suspend fun logout(accessToken: String) {
        val request = requestBuilder("auth/logout", accessToken)
            .post(ByteArray(0).toRequestBody())
            .build()
        execute(request).use(::requireSuccess)
    }

    override fun revisionStream(
        accessToken: String,
        listener: EventSourceListener,
    ): EventSource {
        val request = requestBuilder("stream", accessToken).build()
        return eventSourceFactory.newEventSource(request, listener)
    }

    private suspend inline fun <reified T> get(path: String, accessToken: String?): T {
        val request = requestBuilder(path, accessToken).get().build()
        return executeJson(request)
    }

    private suspend inline fun <reified T> post(
        path: String,
        body: String,
        accessToken: String?,
    ): T {
        val request = requestBuilder(path, accessToken)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return executeJson(request)
    }

    private fun requestBuilder(path: String, accessToken: String?): Request.Builder {
        return Request.Builder()
            .url("${baseUrl.trimEnd('/')}/${path.trimStart('/')}")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .apply {
                if (accessToken != null) header("Authorization", "Bearer $accessToken")
            }
    }

    private suspend inline fun <reified T> executeJson(request: Request): T {
        return execute(request).use { response ->
            requireSuccess(response)
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw IOException("Server returned an empty response")
            json.decodeFromString(body)
        }
    }

    private fun requireSuccess(response: Response): Response {
        if (response.isSuccessful) return response
        val body = response.body?.string().orEmpty()
        val error = runCatching { json.decodeFromString<ApiError>(body).error }.getOrNull()
        throw ApiException(response.code, error ?: "Request failed (${response.code})")
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        })
    }
}
