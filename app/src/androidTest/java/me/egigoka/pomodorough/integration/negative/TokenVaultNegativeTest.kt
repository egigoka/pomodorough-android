package me.egigoka.pomodorough.integration.negative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.auth.TokenVault
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TokenVaultNegativeTest {
    @Test
    fun corruptEncryptedPayloadIsRejectedAndDeleted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        preferences.edit().putString(PayloadKey, "not-valid-base64").commit()
        val vault = TokenVault(context, Json)

        assertNull(vault.read())
        assertFalse(preferences.contains(PayloadKey))
    }

    private companion object {
        const val PreferencesName = "pomodorough_tokens"
        const val PayloadKey = "token-pair"
    }
}
