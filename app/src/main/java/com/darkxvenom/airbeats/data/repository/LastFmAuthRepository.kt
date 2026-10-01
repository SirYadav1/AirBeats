package com.darkxvenom.airbeats.data.repository

import android.net.Uri
import com.darkxvenom.airbeats.data.local.LastFmSessionPreferences
import com.darkxvenom.airbeats.data.network.LastFmApiClient
import com.darkxvenom.airbeats.data.network.LastFmErrors
import com.darkxvenom.airbeats.data.network.LastFmException
import com.darkxvenom.airbeats.data.network.LastFmSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LastFmAuthState {
    data object Unknown : LastFmAuthState
    data object SignedOut : LastFmAuthState
    data object SigningIn : LastFmAuthState
    data class SignedIn(val username: String) : LastFmAuthState
    data class Error(val message: String) : LastFmAuthState
}

const val LAST_FM_AUTH_CALLBACK_URI = "airbeats://auth-callback"

@Singleton
class LastFmAuthRepository @Inject constructor(
    private val api: LastFmApiClient,
    private val sessionPreferences: LastFmSessionPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val transientState = MutableStateFlow<LastFmAuthState?>(null)

    val authState: StateFlow<LastFmAuthState> = combine(
        sessionPreferences.session,
        transientState,
    ) { session, transient ->
        transient ?: if (!session.isLoaded) {
            LastFmAuthState.Unknown
        } else if (session.isAuthenticated) {
            LastFmAuthState.SignedIn(session.username)
        } else {
            LastFmAuthState.SignedOut
        }
    }.stateIn(scope, SharingStarted.Eagerly, LastFmAuthState.Unknown)

    suspend fun saveApiCredentials(apiKey: String, apiSecret: String) {
        sessionPreferences.setApiCredentials(
            LastFmSigner.normalizeKey(apiKey),
            LastFmSigner.normalizeKey(apiSecret),
        )
    }

    suspend fun obtainRequestToken(): Result<String> {
        val stored = sessionPreferences.session.first()
        val apiKey = stored.apiKey
        val apiSecret = stored.apiSecret

        if (apiKey.isBlank() || apiSecret.isBlank()) {
            val message = "Last.fm API credentials missing"
            transientState.value = LastFmAuthState.Error(message)
            return Result.failure(LastFmException(message))
        }

        return try {
            val signParams = mapOf(
                "method" to "auth.getToken",
                "api_key" to apiKey,
            )
            val sig = LastFmSigner.sign(signParams, apiSecret)
            val bodyParams = signParams + mapOf("api_sig" to sig, "format" to "json")
            val (_, responseText) = api.get(bodyParams)
            if (responseText.isBlank()) throw LastFmException("Empty response from Last.fm")

            val parsed = json.parseToJsonElement(responseText).jsonObject
            val errorCode = (parsed["error"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
            if (errorCode != null) {
                val rawMessage = (parsed["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                throw LastFmException(LastFmErrors.friendlyMessage(errorCode, rawMessage), errorCode)
            }

            val token = parsed["token"]?.jsonPrimitive?.content
            if (token.isNullOrBlank()) throw LastFmException("Last.fm did not return an authorization token")
            Result.success(token)
        } catch (e: Exception) {
            val message = (e as? LastFmException)?.message ?: (e.message ?: "Could not request Last.fm token")
            Result.failure(LastFmException(message))
        }
    }

    fun authUrl(token: String? = null): String? {
        val apiKey = sessionPreferences.currentSession.apiKey
        if (apiKey.isBlank()) return null
        val builder = Uri.parse("https://www.last.fm/api/auth/")
            .buildUpon()
            .appendQueryParameter("api_key", apiKey)
        if (!token.isNullOrBlank()) {
            builder.appendQueryParameter("token", token)
        }
        builder.appendQueryParameter("cb", LAST_FM_AUTH_CALLBACK_URI)
        return builder.build().toString()
    }

    suspend fun completeWebAuth(token: String): Result<String> {
        transientState.value = LastFmAuthState.SigningIn
        val stored = sessionPreferences.session.first()
        val apiKey = stored.apiKey
        val apiSecret = stored.apiSecret

        if (apiKey.isBlank() || apiSecret.isBlank()) {
            val message = "Last.fm API credentials missing"
            transientState.value = LastFmAuthState.Error(message)
            return Result.failure(LastFmException(message))
        }

        return try {
            val signParams = mapOf(
                "method" to "auth.getSession",
                "token" to token,
                "api_key" to apiKey,
            )
            val sig = LastFmSigner.sign(signParams, apiSecret)
            val bodyParams = signParams + mapOf("api_sig" to sig, "format" to "json")
            val (_, responseText) = api.post(bodyParams)
            if (responseText.isBlank()) throw LastFmException("Empty response from Last.fm")

            val parsed = json.parseToJsonElement(responseText).jsonObject
            val errorCode = (parsed["error"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
            if (errorCode != null) {
                val rawMessage = (parsed["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                throw LastFmException(LastFmErrors.friendlyMessage(errorCode, rawMessage), errorCode)
            }

            val sessionObj = parsed["session"]?.jsonObject
            val sessionKey = sessionObj?.get("key")?.jsonPrimitive?.content
            val username = sessionObj?.get("name")?.jsonPrimitive?.content

            if (sessionKey.isNullOrBlank() || username.isNullOrBlank()) {
                throw LastFmException("Last.fm didn't return a session")
            }

            sessionPreferences.saveSession(
                username = username,
                sessionKey = sessionKey,
                apiKey = apiKey,
                apiSecret = apiSecret,
            )
            transientState.value = null
            Result.success(username)
        } catch (e: Exception) {
            val message = (e as? LastFmException)?.message ?: (e.message ?: "Could not complete sign-in")
            transientState.value = LastFmAuthState.Error(message)
            Result.failure(e)
        }
    }

    suspend fun signInDirect(username: String): Result<String> {
        val stored = sessionPreferences.currentSession
        val apiKey = stored.apiKey
        val apiSecret = stored.apiSecret
        val usernameNorm = username.trim()

        if (usernameNorm.isBlank()) {
            val message = "Enter your Last.fm username"
            transientState.value = LastFmAuthState.Error(message)
            return Result.failure(LastFmException(message))
        }

        transientState.value = LastFmAuthState.SigningIn
        return try {
            val (_, responseText) = api.get(
                mapOf(
                    "method" to "user.getinfo",
                    "user" to usernameNorm,
                    "api_key" to apiKey,
                    "format" to "json",
                )
            )
            if (responseText.isBlank()) throw LastFmException("Empty response from Last.fm")

            val parsed = json.parseToJsonElement(responseText).jsonObject
            val errorCode = (parsed["error"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
            if (errorCode != null) {
                val rawMessage = (parsed["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                throw LastFmException(LastFmErrors.friendlyMessage(errorCode, rawMessage), errorCode)
            }

            val confirmedUsername = parsed["user"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: usernameNorm
            sessionPreferences.saveSession(
                username = confirmedUsername,
                sessionKey = "",
                apiKey = apiKey,
                apiSecret = apiSecret,
            )
            transientState.value = null
            Result.success(confirmedUsername)
        } catch (e: Exception) {
            val message = (e as? LastFmException)?.message ?: (e.message ?: "Could not verify credentials")
            transientState.value = LastFmAuthState.Error(message)
            Result.failure(e)
        }
    }

    sealed interface SessionKeyResult {
        data object Success : SessionKeyResult
        data class Failed(val message: String) : SessionKeyResult
    }

    suspend fun obtainSessionKey(password: String): SessionKeyResult {
        val session = sessionPreferences.session.first()
        if (session.apiKey.isBlank() || session.apiSecret.isBlank()) {
            return SessionKeyResult.Failed("API credentials required")
        }
        if (session.username.isBlank()) {
            return SessionKeyResult.Failed("Please connect your Last.fm username first")
        }
        if (password.isBlank()) {
            return SessionKeyResult.Failed("Enter your Last.fm password")
        }

        return try {
            val signParams = mapOf(
                "method" to "auth.getMobileSession",
                "username" to session.username,
                "password" to password,
                "api_key" to session.apiKey,
            )
            val sig = LastFmSigner.sign(signParams, session.apiSecret)
            val bodyParams = signParams + mapOf("api_sig" to sig, "format" to "json")
            val (_, responseText) = api.post(bodyParams)
            if (responseText.isBlank()) return SessionKeyResult.Failed("Empty response from Last.fm")

            val parsed = json.parseToJsonElement(responseText).jsonObject
            val errorCode = (parsed["error"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
            if (errorCode != null) {
                val rawMessage = (parsed["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                return SessionKeyResult.Failed(LastFmErrors.friendlyMessage(errorCode, rawMessage))
            }

            val key = parsed["session"]?.jsonObject?.get("key")?.jsonPrimitive?.content
            if (key.isNullOrBlank()) return SessionKeyResult.Failed("Last.fm did not return a session key")

            sessionPreferences.setSessionKey(key)
            SessionKeyResult.Success
        } catch (e: Exception) {
            SessionKeyResult.Failed(e.message ?: "Could not reach Last.fm")
        }
    }

    suspend fun signOut() {
        transientState.value = null
        sessionPreferences.signOut()
    }

    fun clearError() {
        if (transientState.value is LastFmAuthState.Error) {
            transientState.value = null
        }
    }
}
