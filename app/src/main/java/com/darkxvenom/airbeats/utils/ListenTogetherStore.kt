package com.darkxvenom.airbeats.utils

import android.content.Context
import android.os.Build

object ListenTogetherStore {
    private const val PREFS = "listen_together_session"
    private const val KEY_CODE = "code"
    private const val KEY_PARTICIPANT_ID = "participant_id"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_IS_HOST = "is_host"

    private const val KEY_IS_LAN = "is_lan"
    private const val KEY_HOST_ADDRESS = "host_address"

    fun defaultName(): String =
        Build.MODEL
            ?.takeIf { it.isNotBlank() }
            ?.let { "AirBeats on $it" }
            ?: "AirBeats listener"

    fun save(
        context: Context,
        session: ListenTogetherSession,
        displayName: String,
        isHost: Boolean,
        isLan: Boolean = false,
        hostAddress: String? = null,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CODE, session.code)
            .putString(KEY_PARTICIPANT_ID, session.participantId)
            .putString(KEY_DISPLAY_NAME, displayName.ifBlank { defaultName() })
            .putBoolean(KEY_IS_HOST, isHost)
            .putBoolean(KEY_IS_LAN, isLan)
            .putString(KEY_HOST_ADDRESS, hostAddress)
            .apply()
    }

    fun load(context: Context): SavedListenTogetherSession? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_CODE, null)?.takeIf { it.isNotBlank() } ?: return null
        val participantId = prefs.getString(KEY_PARTICIPANT_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        return SavedListenTogetherSession(
            code = code,
            participantId = participantId,
            displayName = prefs.getString(KEY_DISPLAY_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultName(),
            isHost = prefs.getBoolean(KEY_IS_HOST, false),
            isLan = prefs.getBoolean(KEY_IS_LAN, false),
            hostAddress = prefs.getString(KEY_HOST_ADDRESS, null),
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

data class SavedListenTogetherSession(
    val code: String,
    val participantId: String,
    val displayName: String,
    val isHost: Boolean,
    val isLan: Boolean = false,
    val hostAddress: String? = null,
)
