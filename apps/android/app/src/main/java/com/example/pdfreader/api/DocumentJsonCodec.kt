package com.example.pdfreader.api

object DocumentJsonCodec {
    fun decodeDocumentSummaries(json: String): List<DocumentSummaryDto> {
        val documentsBody = arrayBody(json, "documents")
        if (documentsBody.isBlank()) return emptyList()
        return objectBodies(documentsBody).map { body ->
            DocumentSummaryDto(
                documentId = stringValue(body, "document_id"),
                title = stringValue(body, "title"),
                version = intValue(body, "version"),
                progressRatio = nullableDoubleValue(body, "progress_ratio"),
                updatedAt = nullableStringValue(body, "updated_at"),
            )
        }
    }

    fun decodeDocumentDetail(json: String): DocumentDetailDto {
        val progressBody = objectBodyOrNull(json, "reading_progress")
        val cacheControlBody = objectBodyOrNull(json, "cache_control")
        return DocumentDetailDto(
            documentId = stringValue(json, "document_id"),
            title = stringValue(json, "title"),
            version = intValue(json, "version"),
            contentHash = stringValue(json, "content_hash"),
            signedUrl = stringValue(json, "signed_url"),
            signedUrlExpiresAt = stringValue(json, "signed_url_expires_at"),
            readingProgress = progressBody?.let {
                ReadingProgressDto(
                    positionType = stringValue(it, "position_type"),
                    positionValue = stringValue(it, "position_value"),
                    progressRatio = doubleValue(it, "progress_ratio"),
                    updatedAt = nullableStringValue(it, "updated_at"),
                )
            },
            cacheControl = cacheControlBody?.let {
                CacheControlDto(
                    maxAgeSeconds = longValue(it, "max_age_seconds"),
                    staleWhileRevalidateSeconds = longValue(it, "stale_while_revalidate_seconds"),
                )
            } ?: CacheControlDto(
                maxAgeSeconds = 86_400,
                staleWhileRevalidateSeconds = 604_800,
            ),
        )
    }

    fun encodeUpdateReadingProgressRequest(request: UpdateReadingProgressRequest): String =
        buildString {
            append('{')
            append("\"client_id\":\"").append(escape(request.clientId)).append("\",")
            append("\"version\":").append(request.version).append(',')
            append("\"position_type\":\"").append(escape(request.positionType)).append("\",")
            append("\"position_value\":\"").append(escape(request.positionValue)).append("\",")
            append("\"progress_ratio\":").append(request.progressRatio)
            append('}')
        }

    private fun stringValue(json: String, key: String): String =
        nullableStringValue(json, key)
            ?: throw IllegalArgumentException("missing string field: $key")

    private fun nullableStringValue(json: String, key: String): String? {
        val match = Regex("\"$key\"\\s*:\\s*(null|\"((?:\\\\.|[^\"])*)\")").find(json)
            ?: return null
        if (match.groupValues[1] == "null") return null
        return unescape(match.groupValues[2])
    }

    private fun intValue(json: String, key: String): Int =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)")
            .find(json)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: throw IllegalArgumentException("missing int field: $key")

    private fun longValue(json: String, key: String): Long =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)")
            .find(json)
            ?.groupValues
            ?.get(1)
            ?.toLong()
            ?: throw IllegalArgumentException("missing long field: $key")

    private fun doubleValue(json: String, key: String): Double =
        nullableDoubleValue(json, key)
            ?: throw IllegalArgumentException("missing double field: $key")

    private fun nullableDoubleValue(json: String, key: String): Double? {
        val match = Regex("\"$key\"\\s*:\\s*(null|-?\\d+(?:\\.\\d+)?)").find(json) ?: return null
        return match.groupValues[1].takeUnless { it == "null" }?.toDouble()
    }

    private fun arrayBody(json: String, key: String): String {
        val start = Regex("\"$key\"\\s*:\\s*\\[").find(json)
            ?: throw IllegalArgumentException("missing array field: $key")
        val bodyStart = start.range.last + 1
        val bodyEnd = findMatching(json, bodyStart - 1, '[', ']')
        return json.substring(bodyStart, bodyEnd)
    }

    private fun objectBodyOrNull(json: String, key: String): String? {
        val nullMatch = Regex("\"$key\"\\s*:\\s*null").find(json)
        if (nullMatch != null) return null
        val start = Regex("\"$key\"\\s*:\\s*\\{").find(json) ?: return null
        val bodyStart = start.range.last
        val bodyEnd = findMatching(json, bodyStart, '{', '}')
        return json.substring(bodyStart, bodyEnd + 1)
    }

    private fun objectBodies(arrayBody: String): List<String> {
        val bodies = mutableListOf<String>()
        var index = 0
        while (index < arrayBody.length) {
            val start = arrayBody.indexOf('{', index)
            if (start == -1) break
            val end = findMatching(arrayBody, start, '{', '}')
            bodies += arrayBody.substring(start, end + 1)
            index = end + 1
        }
        return bodies
    }

    private fun findMatching(
        value: String,
        start: Int,
        open: Char,
        close: Char,
    ): Int {
        var depth = 0
        var inString = false
        var escaping = false
        for (index in start until value.length) {
            val char = value[index]
            if (escaping) {
                escaping = false
                continue
            }
            if (char == '\\' && inString) {
                escaping = true
                continue
            }
            if (char == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            if (char == open) depth += 1
            if (char == close) {
                depth -= 1
                if (depth == 0) return index
            }
        }
        throw IllegalArgumentException("unmatched JSON delimiter: $open")
    }

    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")

    private fun unescape(value: String): String =
        value
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
}
