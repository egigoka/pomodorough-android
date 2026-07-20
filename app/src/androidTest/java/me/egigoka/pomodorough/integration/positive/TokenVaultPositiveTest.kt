package me.egigoka.pomodorough.integration.positive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.TokenPair
import me.egigoka.pomodorough.data.auth.TokenVault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TokenVaultPositiveTest {
    private lateinit var context: Context
    private lateinit var vault: TokenVault

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        vault = TokenVault(context, Json)
    }

    @After
    fun tearDown() {
        vault.clear()
    }

    @Test
    fun encryptedTokensRoundTripAndClear() {
        val tokens = TokenPair(
            accessToken = "access-secret",
            accessTokenExpiresAt = "2999-01-01T00:00:00Z",
            refreshToken = "refresh-secret",
            refreshTokenExpiresAt = "2999-02-01T00:00:00Z",
        )

        vault.write(tokens)

        assertEquals(tokens, vault.read())
        vault.clear()
        assertNull(vault.read())
    }

    private companion object {
        const val PreferencesName = "pomodorough_tokens"
    }
}
