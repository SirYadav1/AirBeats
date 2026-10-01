package com.darkxvenom.airbeats.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.darkxvenom.airbeats.constants.LastFmApiKey
import com.darkxvenom.airbeats.constants.LastFmApiSecret
import com.darkxvenom.airbeats.constants.LastFmSessionKey
import com.darkxvenom.airbeats.constants.LastFmUsername
import com.darkxvenom.airbeats.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

data class LastFmSessionData(
    val apiKey: String = "",
    val apiSecret: String = "",
    val sessionKey: String = "",
    val username: String = "",
    val isLoaded: Boolean = true,
) {
    val isAuthenticated: Boolean get() = isLoaded && username.isNotBlank()
    val hasApiKey: Boolean get() = apiKey.isNotBlank() && apiSecret.isNotBlank()
    val hasSessionKey: Boolean get() = sessionKey.isNotBlank()
}

typealias LastFmSession = LastFmSessionData

@Singleton
class LastFmSessionPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val OLD_DEFAULT_API_KEY = "8a213904535359a35d944d1885b5976b"
        const val OLD_DEFAULT_API_SECRET = "9a7569b9f7cb2f43a2906df0dfd66df2"

        // Production Last.fm credentials for Airbeats
        const val DEFAULT_API_KEY = "e4ff17cfd46f9de0433018bc72d8f8e4"
        const val DEFAULT_API_SECRET = "91a200bc71d725949101232c96020733"
        const val DEFAULT_USERNAME = "Darkboy336"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val session: StateFlow<LastFmSessionData> = context.dataStore.data
        .map { prefs ->
            val userKey = prefs[LastFmApiKey]?.trim().orEmpty()
            val userSecret = prefs[LastFmApiSecret]?.trim().orEmpty()
            val effectiveKey = if (userKey.isBlank() || userKey == OLD_DEFAULT_API_KEY) DEFAULT_API_KEY else userKey
            val effectiveSecret = if (userSecret.isBlank() || userSecret == OLD_DEFAULT_API_SECRET) DEFAULT_API_SECRET else userSecret
            val sk = prefs[LastFmSessionKey]?.trim().orEmpty()
            val user = prefs[LastFmUsername]?.trim().orEmpty()

            LastFmSessionData(
                apiKey = effectiveKey,
                apiSecret = effectiveSecret,
                sessionKey = sk,
                username = user,
                isLoaded = true,
            )
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = LastFmSessionData(
                apiKey = DEFAULT_API_KEY,
                apiSecret = DEFAULT_API_SECRET,
                sessionKey = "",
                username = "",
                isLoaded = false,
            )
        )

    val currentSession: LastFmSessionData get() = session.value

    suspend fun saveSession(
        username: String,
        sessionKey: String = "",
        apiKey: String = "",
        apiSecret: String = "",
    ) {
        context.dataStore.edit { prefs ->
            prefs[LastFmUsername] = username.trim()
            if (sessionKey.isNotBlank()) {
                prefs[LastFmSessionKey] = sessionKey.trim()
            }
            if (apiKey.isNotBlank()) {
                prefs[LastFmApiKey] = apiKey.trim()
            }
            if (apiSecret.isNotBlank()) {
                prefs[LastFmApiSecret] = apiSecret.trim()
            }
        }
    }

    suspend fun setApiCredentials(apiKey: String, apiSecret: String) {
        context.dataStore.edit { prefs ->
            if (apiKey.isBlank()) prefs.remove(LastFmApiKey) else prefs[LastFmApiKey] = apiKey.trim()
            if (apiSecret.isBlank()) prefs.remove(LastFmApiSecret) else prefs[LastFmApiSecret] = apiSecret.trim()
        }
    }

    suspend fun setSessionKey(key: String) {
        context.dataStore.edit { prefs ->
            if (key.isBlank()) prefs.remove(LastFmSessionKey) else prefs[LastFmSessionKey] = key.trim()
        }
    }

    suspend fun signOut() {
        context.dataStore.edit { prefs ->
            prefs.remove(LastFmSessionKey)
            prefs.remove(LastFmUsername)
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { prefs ->
            prefs.remove(LastFmApiKey)
            prefs.remove(LastFmApiSecret)
            prefs.remove(LastFmSessionKey)
            prefs.remove(LastFmUsername)
        }
    }
}
