package com.example.pdfreader.cache

import android.content.Context
import android.content.SharedPreferences
import com.example.pdfreader.domain.ContentHash
import com.example.pdfreader.domain.DocumentId
import com.example.pdfreader.domain.DocumentVersion
import com.example.pdfreader.domain.PositionType
import com.example.pdfreader.domain.ReadingProgress

class SharedPreferencesDocumentMetadataStorage(
    context: Context,
) : DocumentMetadataStorage {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(documentId: DocumentId): CachedDocumentMetadata? =
        preferences.getString(key(documentId), null)?.let { decodeOrNull(it) }

    override fun write(metadata: CachedDocumentMetadata) {
        preferences.edit().putString(key(metadata.documentId), encode(metadata)).apply()
    }

    private fun key(documentId: DocumentId): String = "document_${escape(documentId.value)}"

    private fun encode(metadata: CachedDocumentMetadata): String =
        listOf(
            escape(metadata.documentId.value),
            escape(metadata.title),
            metadata.version.value.toString(),
            escape(metadata.contentHash.value),
            escape(metadata.signedUrl),
            escape(metadata.readingProgress?.positionType?.wireName.orEmpty()),
            escape(metadata.readingProgress?.positionValue.orEmpty()),
            metadata.readingProgress?.progressRatio?.toString().orEmpty(),
        ).joinToString("|")

    private fun decodeOrNull(value: String): CachedDocumentMetadata? {
        val parts = splitEscaped(value)
        if (parts.size != 8) return null
        return runCatching {
            val documentId = DocumentId(unescape(parts[0]))
            val version = DocumentVersion(parts[2].toInt())
            val progress = if (parts[5].isBlank()) {
                null
            } else {
                ReadingProgress(
                    documentId = documentId,
                    version = version,
                    positionType = PositionType.fromWireName(unescape(parts[5])),
                    positionValue = unescape(parts[6]),
                    progressRatio = parts[7].toDouble(),
                )
            }
            CachedDocumentMetadata(
                documentId = documentId,
                title = unescape(parts[1]),
                version = version,
                contentHash = ContentHash(unescape(parts[3])),
                signedUrl = unescape(parts[4]),
                readingProgress = progress,
            )
        }.getOrNull()
    }

    private fun splitEscaped(value: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var escaping = false
        value.forEach { char ->
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
        const val PREFERENCES_NAME = "markdown_reader_document_metadata"
    }
}
