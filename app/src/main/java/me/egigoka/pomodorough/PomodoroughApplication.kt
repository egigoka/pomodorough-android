package me.egigoka.pomodorough

import android.app.Application
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.TimerRepository
import me.egigoka.pomodorough.data.api.PomodoroughApi
import me.egigoka.pomodorough.data.auth.AuthRepository
import me.egigoka.pomodorough.data.auth.TokenVault
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import okhttp3.OkHttpClient

class PomodoroughApplication : Application() {
    lateinit var timerRepository: TimerRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
        val api = PomodoroughApi(BuildConfig.API_BASE_URL, client, json)
        val database = PomodoroughDatabase.create(this)
        val auth = AuthRepository(
            api = api,
            tokenVault = TokenVault(this, json),
            googleServerClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID,
        )
        timerRepository = TimerRepository(
            context = this,
            dao = database.timerDao(),
            api = api,
            auth = auth,
            json = json,
        )
    }
}
