package me.egigoka.pomodorough.unit

import me.egigoka.pomodorough.data.MeResponse
import me.egigoka.pomodorough.data.BootstrapResolutionRequest
import me.egigoka.pomodorough.data.NativeChallenge
import me.egigoka.pomodorough.data.NativeExchangeRequest
import me.egigoka.pomodorough.data.SyncRequest
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.TokenPair
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.TokenStore
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

internal class TestTokenStore(initial: TokenPair?) : TokenStore {
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

internal class TestAuthService : PomodoroughService {
    var refreshCalls = 0
    var refreshResult = freshTokens()
    var refreshFailure: Throwable? = null
    val logoutTokens = mutableListOf<String>()
    var logoutFailure: Throwable? = null

    override suspend fun refresh(refreshToken: String): TokenPair {
        refreshCalls += 1
        refreshFailure?.let { throw it }
        return refreshResult
    }

    override suspend fun logout(accessToken: String) {
        logoutTokens += accessToken
        logoutFailure?.let { throw it }
    }

    override suspend fun createChallenge(): NativeChallenge = error("Unused")
    override suspend fun exchange(request: NativeExchangeRequest): TokenPair = error("Unused")
    override suspend fun me(accessToken: String): MeResponse = error("Unused")
    override suspend fun bootstrap(accessToken: String): SyncResponse = error("Unused")
    override suspend fun resolveBootstrap(
        accessToken: String,
        request: BootstrapResolutionRequest,
    ): SyncResponse = error("Unused")
    override suspend fun sync(accessToken: String, request: SyncRequest): SyncResponse = error("Unused")
    override fun revisionStream(accessToken: String, listener: EventSourceListener): EventSource =
        error("Unused")
}

internal fun freshTokens(accessToken: String = "fresh-access") = TokenPair(
    accessToken = accessToken,
    accessTokenExpiresAt = "2999-01-01T00:00:00Z",
    refreshToken = "fresh-refresh",
    refreshTokenExpiresAt = "2999-02-01T00:00:00Z",
)

internal fun expiredTokens(accessToken: String = "expired-access") = TokenPair(
    accessToken = accessToken,
    accessTokenExpiresAt = "2000-01-01T00:00:00Z",
    refreshToken = "current-refresh",
    refreshTokenExpiresAt = "2999-02-01T00:00:00Z",
)
