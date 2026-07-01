package com.example.pdfreader.progress

import android.content.Context
import android.content.SharedPreferences
import com.example.pdfreader.domain.DocumentId
import com.example.pdfreader.domain.DocumentVersion
import com.example.pdfreader.domain.PositionType
import com.example.pdfreader.domain.ReadingProgress

class SharedPreferencesProgressOfflineQueue(
    context: Context,
) : ProgressOfflineQueue {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun enqueue(progress: ReadingProgress) {
        val rows = rows().toMutableList()
        rows += encode(progress)
        preferences.edit().putString(QUEUE_KEY, rows.joinToString("\n")).apply()
    }

    override fun drain(): List<ReadingProgress> {
        val queued = rows().mapNotNull { decodeOrNull(it) }
        preferences.edit().remove(QUEUE_KEY).apply()
        return queued
    }

    override fun size(): Int = rows().size

    private fun rows(): List<String> =
        preferences.getString(QUEUE_KEY, null)
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.toList()
            ?: emptyList()

    private fun encode(progress: ReadingProgress): String =
        listOf(
            escape(progress.documentId.value),
            progress.version.value.toString(),
            progress.positionType.wireName,
            escape(progress.positionValue),
            progress.progressRatio.toString(),
        ).joinToString("|")

    private fun decodeOrNull(row: String): ReadingProgress? {
        val parts = splitEscaped(row)
        if (parts.size != 5) return null
        return runCatching {
            ReadingProgress(
                documentId = DocumentId(unescape(parts[0])),
                version = DocumentVersion(parts[1].toInt()),
                positionType = PositionType.fromWireName(parts[2]),
                positionValue = unescape(parts[3]),
                progressRatio = parts[4].toDouble(),
            )
        }.getOrNull()
    }

    private fun splitEscaped(row: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var escaping = false
        row.forEach { char ->
            when {
                escaping -> {
                    current.append('\\')
                    current.append(char)
                    escaping = false
                }
                char == '\\' -> escaping = true
                char == '|' -> {
                    parts += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        if (escaping) current.append('\\')
        parts += current.toString()
        return parts
    }

    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("|", "\\p")
            .replace("\n", "\\n")

    private fun unescape(value: String): String {
        val output = StringBuilder()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    '\\' -> output.append('\\')
                    'p' -> output.append('|')
                    'n' -> output.append('\n')
                    else -> {
                        output.append('\\')
                        output.append(value[index + 1])
                    }
                }
                index += 2
            } else {
                output.append(char)
                index += 1
            }
        }
        return output.toString()
    }

    private companion object {
        const val PREFERENCES_NAME = "markdown_reader_progress_queue"
        const val QUEUE_KEY = "pending_progress"
    }
}
