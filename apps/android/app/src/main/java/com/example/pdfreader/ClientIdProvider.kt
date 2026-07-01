package com.example.pdfreader

import android.content.Context
import java.util.UUID

class ClientIdProvider(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("markdown_reader", Context.MODE_PRIVATE)

    fun getOrCreate(): String {
        val existing = preferences.getString(KEY_CLIENT_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_CLIENT_ID, generated).apply()
        return generated
    }

    private companion object {
        const val KEY_CLIENT_ID = "client_id"
    }
}
