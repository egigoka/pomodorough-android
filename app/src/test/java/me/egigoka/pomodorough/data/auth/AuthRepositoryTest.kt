package me.egigoka.pomodorough.data.auth

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.MeResponse
import me.egigoka.pomodorough.data.NativeChallenge
import me.egigoka.pomodorough.data.NativeExchangeRequest
import me.egigoka.pomodorough.data.SyncRequest
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.TokenPair
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.api.PomodoroughService
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentExpiredRequestsShareOneRefresh() = runTest {
        val store = FakeTokenStore(expiredTokens())
        val service = FakeService()
        val releaseRefresh = CompletableDeferred<Unit>()
        service.refreshHandler = {
            releaseRefresh.await()
            freshTokens()
        }
        val repository = repository(service, store)

        val first = async { repository.authorized { it } }
        val second = async { repository.authorized { it } }
        runCurrent()

        assertEquals(1, service.refreshCalls)
        releaseRefresh.complete(Unit)
        assertEquals("fresh-access", first.await())
        assertEquals("fresh-access", second.await())
        assertEquals(1, service.refreshCalls)
        assertEquals(freshTokens(), store.tokens)
    }

    @Test
    fun unauthorizedRequestRefreshesAndRetriesOnce() = runTest {
        val store = FakeTokenStore(freshTokens(accessToken = "old-access"))
        val service = FakeService().apply { refreshHandler = { freshTokens() } }
        val repository = repository(service, store)
        val attempts = mutableListOf<String>()

        val result = repository.authorized { token ->
            attempts += token
            if (token == "old-access") throw ApiException(401, "expired")
            "accepted"
        }

        assertEquals("accepted", result)
        assertEquals(listOf("old-access", "fresh-access"), attempts)
        assertEquals(1, service.refreshCalls)
    }

    @Test
    fun secondUnauthorizedResponseClearsSession() = runTest {
        val store = FakeTokenStore(freshTokens(accessToken = "old-access"))
        val service = FakeService().apply { refreshHandler = { freshTokens() } }
        val repository = repository(service, store)

        val error = capture<AuthenticationRequired> {
            repository.authorized<Nothing> { throw ApiException(401, "expired") }
        }

        assertEquals("Session expired", error.message)
        assertNull(store.tokens)
        assertEquals(1, store.clearCalls)
    }

    @Test
    fun refreshUnauthorizedClearsSession() = runTest {
        val store = FakeTokenStore(expiredTokens())
        val service = FakeService().apply {
            refreshHandler = { throw ApiException(401, "invalid refresh token") }
        }

        capture<AuthenticationRequired> { repository(service, store).authorized { it } }

        assertNull(store.tokens)
        assertEquals(1, store.clearCalls)
    }

    @Test
    fun ambiguousRefreshFailurePreservesCurrentPair() = runTest {
        val original = expiredTokens()
        val store = FakeTokenStore(original)
        val failure = IOException("connection reset")
        val service = FakeService().apply { refreshHandler = { throw failure } }

        val thrown = capture<IOException> { repository(service, store).authorized { it } }

        assertSame(failure, thrown)
        assertEquals(original, store.tokens)
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun logoutServerFailurePreservesTokens() = runTest {
        val original = freshTokens()
        val store = FakeTokenStore(original)
        val service = FakeService().apply { logoutFailure = ApiException(500, "unavailable") }

        capture<ApiException> { repository(service, store).logout() }

        assertEquals(original, store.tokens)
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun logoutUnauthorizedClearsTokens() = runTest {
        val store = FakeTokenStore(freshTokens())
        val service = FakeService().apply { logoutFailure = ApiException(401, "unauthorized") }

        repository(service, store).logout()

        assertNull(store.tokens)
        assertEquals(1, store.clearCalls)
    }

    private fun repository(service: FakeService, store: FakeTokenStore) = AuthRepository(
        api = service,
        tokenVault = store,
        googleServerClientId = "client-id",
    )

    private fun freshTokens(accessToken: String = "fresh-access") = TokenPair(
        accessToken = accessToken,
        accessTokenExpiresAt = "2999-01-01T00:00:00Z",
        refreshToken = "fresh-refresh",
        refreshTokenExpiresAt = "2999-02-01T00:00:00Z",
    )

    private fun expiredTokens() = TokenPair(
        accessToken = "expired-access",
        accessTokenExpiresAt = "2000-01-01T00:00:00Z",
        refreshToken = "current-refresh",
        refreshTokenExpiresAt = "2999-02-01T00:00:00Z",
    )

    private suspend inline fun <reified T : Throwable> capture(crossinline block: suspend () -> Unit): T {
        return try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            assertTrue("Expected ${T::class.java.name}, got ${error::class.java.name}", error is T)
            error as T
        }
    }

    private class FakeTokenStore(initial: TokenPair?) : TokenStore {
        var tokens = initial
        var clearCalls = 0

        override fun read(): TokenPair? = tokens

        override fun write(tokens: TokenPair) {
            this.tokens = tokens
        }

        override fun clear() {
            tokens = null
            clearCalls += 1
        }
    }

    private class FakeService : PomodoroughService {
        var refreshCalls = 0
        var refreshHandler: suspend (String) -> TokenPair = { error("Unexpected refresh") }
        var logoutFailure: Throwable? = null

        override suspend fun refresh(refreshToken: String): TokenPair {
            refreshCalls += 1
            return refreshHandler(refreshToken)
        }

        override suspend fun logout(accessToken: String) {
            logoutFailure?.let { throw it }
        }

        override suspend fun createChallenge(): NativeChallenge = error("Unused")
        override suspend fun exchange(request: NativeExchangeRequest): TokenPair = error("Unused")
        override suspend fun me(accessToken: String): MeResponse = error("Unused")
        override suspend fun sync(accessToken: String, request: SyncRequest): SyncResponse = error("Unused")
        override fun revisionStream(accessToken: String, listener: EventSourceListener): EventSource =
            error("Unused")
    }
}
