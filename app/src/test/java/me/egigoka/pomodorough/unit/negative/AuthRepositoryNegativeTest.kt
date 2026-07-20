package me.egigoka.pomodorough.unit.negative

import java.io.IOException
import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.TokenPair
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.auth.AuthRepository
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import me.egigoka.pomodorough.unit.TestAuthService
import me.egigoka.pomodorough.unit.TestTokenStore
import me.egigoka.pomodorough.unit.expiredTokens
import me.egigoka.pomodorough.unit.freshTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryNegativeTest {
    @Test
    fun missingTokensRequiresAuthenticationWithoutRefreshing() = runTest {
        val service = TestAuthService()
        val store = TestTokenStore(null)

        val error = capture<AuthenticationRequired> {
            repository(service, store).authorized { it }
        }

        assertEquals("Sign in required", error.message)
        assertEquals(0, service.refreshCalls)
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun nonUnauthorizedFailureDoesNotRefreshOrClearSession() = runTest {
        val tokens = freshTokens()
        val service = TestAuthService()
        val store = TestTokenStore(tokens)
        val failure = ApiException(403, "forbidden")

        val error = capture<ApiException> {
            repository(service, store).authorized<Nothing> { throw failure }
        }

        assertSame(failure, error)
        assertEquals(0, service.refreshCalls)
        assertEquals(tokens, store.tokens)
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun malformedExpiryIsTreatedAsExpired() = runTest {
        val malformed = TokenPair(
            accessToken = "malformed-access",
            accessTokenExpiresAt = "not-an-instant",
            refreshToken = "refresh-token",
            refreshTokenExpiresAt = "2999-01-01T00:00:00Z",
        )
        val service = TestAuthService()
        val store = TestTokenStore(malformed)

        val accessToken = repository(service, store).authorized { it }

        assertEquals("fresh-access", accessToken)
        assertEquals(1, service.refreshCalls)
        assertEquals(freshTokens(), store.tokens)
    }

    @Test
    fun logoutRefreshNetworkFailurePreservesExpiredTokens() = runTest {
        val tokens = expiredTokens()
        val failure = IOException("network unavailable")
        val service = TestAuthService().apply { refreshFailure = failure }
        val store = TestTokenStore(tokens)

        val error = capture<IOException> { repository(service, store).logout() }

        assertSame(failure, error)
        assertTrue(service.logoutTokens.isEmpty())
        assertEquals(tokens, store.tokens)
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun nonUnauthorizedRetryFailureKeepsRefreshedSession() = runTest {
        val service = TestAuthService()
        val store = TestTokenStore(freshTokens("old-access"))
        val attempts = mutableListOf<String>()

        val error = capture<ApiException> {
            repository(service, store).authorized<Nothing> { token ->
                attempts += token
                if (token == "old-access") throw ApiException(401, "expired")
                throw ApiException(409, "conflict")
            }
        }

        assertEquals(409, error.statusCode)
        assertEquals(listOf("old-access", "fresh-access"), attempts)
        assertEquals(freshTokens(), store.tokens)
        assertEquals(0, store.clearCalls)
    }

    private fun repository(service: TestAuthService, store: TestTokenStore) = AuthRepository(
        api = service,
        tokenVault = store,
        googleServerClientId = "client-id",
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
}
