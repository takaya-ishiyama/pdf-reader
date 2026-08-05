package com.example.android.data

interface ClientIdProvider {
    fun getOrCreateClientId(): String
}
