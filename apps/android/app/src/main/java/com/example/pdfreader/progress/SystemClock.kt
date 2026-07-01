package com.example.pdfreader.progress

object SystemClock : Clock {
    override fun nowMillis(): Long = android.os.SystemClock.elapsedRealtime()
}
