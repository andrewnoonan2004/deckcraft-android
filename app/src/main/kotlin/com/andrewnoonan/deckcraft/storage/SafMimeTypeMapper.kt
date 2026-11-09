package com.andrewnoonan.deckcraft.storage

object SafMimeTypeMapper {
    private val extensionToMimes = mapOf(
        "deckcraft" to listOf("application/octet-stream", "application/zip"),
        "slidecraft" to listOf("application/octet-stream", "application/zip"),
        "pptx" to listOf(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.ms-powerpoint",
            "application/octet-stream",
        ),
        "potx" to listOf(
            "application/vnd.openxmlformats-officedocument.presentationml.template",
            "application/octet-stream",
        ),
        "ppsx" to listOf(
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
            "application/octet-stream",
        ),
        "thmx" to listOf(
            "application/vnd.ms-officetheme",
            "application/octet-stream",
        ),
        "pdf" to listOf("application/pdf"),
        "png" to listOf("image/png"),
        "jpg" to listOf("image/jpeg"),
        "jpeg" to listOf("image/jpeg"),
        "gif" to listOf("image/gif"),
        "webp" to listOf("image/webp"),
        "bmp" to listOf("image/bmp"),
        "svg" to listOf("image/svg+xml"),
        "tif" to listOf("image/tiff"),
        "tiff" to listOf("image/tiff"),
        "mp3" to listOf("audio/mpeg"),
        "wav" to listOf("audio/wav", "audio/x-wav"),
        "m4a" to listOf("audio/mp4", "audio/x-m4a"),
        "aac" to listOf("audio/aac"),
        "ogg" to listOf("audio/ogg"),
        "opus" to listOf("audio/opus", "audio/ogg"),
        "flac" to listOf("audio/flac"),
        "mp4" to listOf("video/mp4"),
        "m4v" to listOf("video/x-m4v", "video/mp4"),
        "mov" to listOf("video/quicktime"),
        "webm" to listOf("video/webm"),
        "mkv" to listOf("video/x-matroska"),
        "txt" to listOf("text/plain"),
        "md" to listOf("text/markdown", "text/plain"),
        "rtf" to listOf("application/rtf", "text/rtf"),
        "json" to listOf("application/json", "text/plain"),
    )

    fun toOpenMimeTypes(extensions: List<String>): Array<String> {
        val cleaned = extensions
            .map { it.trim().trimStart('.').lowercase() }
            .filter { it.isNotEmpty() }

        if (cleaned.isEmpty()) {
            return arrayOf(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
                "application/vnd.openxmlformats-officedocument.presentationml.template",
                "application/octet-stream",
                "*/*",
            )
        }

        // Because Android SAF doesn't natively register `.deckcraft` MIME types on all OEM file
        // managers, include `*/*` whenever `.deckcraft` is requested so custom deckcraft files are selectable.
        val needsWildcard = cleaned.any { it == "deckcraft" || it == "slidecraft" || it == "thmx" }

        val mapped = cleaned.flatMap { ext ->
            extensionToMimes[ext] ?: listOf("*/*")
        }.distinct()

        val result = if (needsWildcard && !mapped.contains("*/*")) {
            mapped + "*/*"
        } else {
            mapped
        }
        return if (result.isEmpty()) arrayOf("*/*") else result.toTypedArray()
    }

    fun toCreateDocumentMimeType(
        suggestedName: String?,
        extensions: List<String>,
    ): String {
        val extFromName = suggestedName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }

        val firstExt = extFromName ?: extensions
            .map { it.trim().trimStart('.').lowercase() }
            .firstOrNull { it.isNotEmpty() }

        return when (firstExt) {
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "potx" -> "application/vnd.openxmlformats-officedocument.presentationml.template"
            "ppsx" -> "application/vnd.openxmlformats-officedocument.presentationml.slideshow"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "rtf" -> "application/rtf"
            "deckcraft", "slidecraft" -> "application/octet-stream"
            else -> "application/octet-stream"
        }
    }
}
