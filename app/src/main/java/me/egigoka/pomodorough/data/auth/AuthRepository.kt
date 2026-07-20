package me.egigoka.pomodorough.data.auth

import android.annotation.SuppressLint
import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.egigoka.pomodorough.data.NativeExchangeRequest
import me.egigoka.pomodorough.data.TokenPair
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.api.PomodoroughService

class AuthenticationRequired(message: String = "Sign in required") : Exception(message)

interface AuthSession {
    suspend fun signIn(activity: Activity, deviceId: String): TokenPair
    fun hasTokens(): Boolean
    suspend fun <T> authorized(block: suspend (String) -> T): T
    suspend fun logout()
    fun clear()
}

class AuthRepository(
    private val api: PomodoroughService,
    private val tokenVault: TokenStore,
    private val googleServerClientId: String,
) : AuthSession {
    private val refreshMutex = Mutex()

    @SuppressLint("CredentialManagerSignInWithGoogle")
    override suspend fun signIn(activity: Activity, deviceId: String): TokenPair {
        val challenge = api.createChallenge()
        val googleOption = GetGoogleIdOption.Builder()
            .setServerClientId(googleServerClientId)
            .setNonce(challenge.nonce)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val result = CredentialManager.create(activity).getCredential(
            context = activity,
            request = GetCredentialRequest.Builder()
                .addCredentialOption(googleOption)
                .build(),
        )
        val credential = result.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw AuthenticationRequired("Google did not return an ID token")
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        return api.exchange(
            NativeExchangeRequest(
                idToken = idToken,
                challenge = challenge.challenge,
                deviceId = deviceId,
                platform = "android",
            ),
        ).also(tokenVault::write)
    }

    override fun hasTokens(): Boolean = tokenVault.read() != null

    override suspend fun <T> authorized(block: suspend (String) -> T): T {
        val initial = validAccessToken()
        return try {
            block(initial.accessToken)
        } catch (error: ApiException) {
            if (error.statusCode != 401) throw error
            val refreshed = refresh(initial.accessToken, force = true)
            try {
                block(refreshed.accessToken)
            } catch (retryError: ApiException) {
                if (retryError.statusCode != 401) throw retryError
                tokenVault.clear()
                throw AuthenticationRequired("Session expired")
            }
        }
    }

    override suspend fun logout() {
        val tokens = tokenVault.read()
        if (tokens == null) {
            tokenVault.clear()
            return
        }
        try {
            val current = if (isAccessFresh(tokens)) tokens else refresh(tokens.accessToken, force = true)
            api.logout(current.accessToken)
            tokenVault.clear()
        } catch (error: ApiException) {
            if (error.statusCode == 401) {
                tokenVault.clear()
                return
            }
            throw error
        }
    }

    override fun clear() = tokenVault.clear()

    private suspend fun validAccessToken(): TokenPair {
        val current = tokenVault.read() ?: throw AuthenticationRequired()
        return if (isAccessFresh(current)) current else refresh(current.accessToken, force = false)
    }

    private suspend fun refresh(previousAccessToken: String, force: Boolean): TokenPair =
        refreshMutex.withLock {
            val current = tokenVault.read() ?: throw AuthenticationRequired()
            if (current.accessToken != previousAccessToken || (!force && isAccessFresh(current))) {
                return@withLock current
            }
            try {
                api.refresh(current.refreshToken).also(tokenVault::write)
            } catch (error: ApiException) {
                if (error.statusCode == 401) {
                    tokenVault.clear()
                    throw AuthenticationRequired("Session expired")
                }
                throw error
            }
        }

    private fun isAccessFresh(tokens: TokenPair): Boolean {
        val expiresAt = runCatching { Instant.parse(tokens.accessTokenExpiresAt) }.getOrNull() ?: return false
        return expiresAt.isAfter(Instant.now().plusSeconds(60))
    }
}
