package com.example.android.data

import android.content.SharedPreferences
import java.util.UUID

class SharedPreferencesClientIdProvider(
    private val preferences: SharedPreferences,
) : ClientIdProvider {
    override fun getOrCreateClientId(): String {
        preferences.getString(KEY_CLIENT_ID, null)?.let { return it }
        val clientId = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_CLIENT_ID, clientId).apply()
        return clientId
    }

    private companion object {
        const val KEY_CLIENT_ID = "client_id"
    }
}
