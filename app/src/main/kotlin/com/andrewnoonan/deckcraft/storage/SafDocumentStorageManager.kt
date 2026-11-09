package com.andrewnoonan.deckcraft.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ImportedSafDocument(
    val uri: Uri,
    val displayName: String,
    val localFile: File,
)

class SafDocumentStorageManager(
    private val contentResolver: ContentResolver,
    baseDir: File,
) : DocumentStorageManager {
    private val workspaceDir = File(baseDir, "saf_workspace").apply { mkdirs() }
    private val pathToUriMap = ConcurrentHashMap<String, Uri>()
    private val slotCounter = AtomicLong(1L)

    fun importReadableUri(uri: Uri): ImportedSafDocument? {
        takePersistablePermissionBestEffort(
            uri = uri,
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )

        val rawName = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Presentation.deckcraft"
        val sanitizedName = sanitizeFileName(rawName, defaultExtension = "deckcraft")
        val slotDir = createUniqueSlotDir()
        val destFile = File(slotDir, sanitizedName)

        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            } ?: return null

            val canonical = destFile.canonicalPath
            pathToUriMap[canonical] = uri
            ImportedSafDocument(
                uri = uri,
                displayName = sanitizedName,
                localFile = destFile,
            )
        }.getOrNull()
    }

    fun registerWritableTargetUri(
        uri: Uri,
        fallbackSuggestedName: String?,
    ): File {
        takePersistablePermissionBestEffort(
            uri = uri,
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )

        val queriedName = queryDisplayName(uri)
        val chosenName = queriedName?.takeIf { it.isNotBlank() }
            ?: fallbackSuggestedName?.takeIf { it.isNotBlank() }
            ?: "Presentation.deckcraft"

        val defaultExt = fallbackSuggestedName
            ?.substringAfterLast('.', missingDelimiterValue = "deckcraft")
            ?.ifBlank { "deckcraft" }
            ?: "deckcraft"

        val sanitized = sanitizeFileName(chosenName, defaultExtension = defaultExt)
        val slotDir = createUniqueSlotDir()
        val targetFile = File(slotDir, sanitized)
        val canonical = targetFile.canonicalPath
        pathToUriMap[canonical] = uri
        return targetFile
    }

    override fun syncSavedPathToOrigin(savedPath: String): Boolean {
        if (savedPath.isBlank()) return false
        val localFile = File(savedPath)
        if (!localFile.isFile) return false
        val canonical = runCatching { localFile.canonicalPath }.getOrDefault(localFile.absolutePath)
        val targetUri = pathToUriMap[canonical] ?: pathToUriMap[localFile.absolutePath] ?: return false

        return runCatching {
            val stream = contentResolver.openOutputStream(targetUri, "wt")
                ?: contentResolver.openOutputStream(targetUri, "w")
                ?: contentResolver.openOutputStream(targetUri)
                ?: return false

            stream.use { output ->
                FileInputStream(localFile).use { input ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            true
        }.getOrDefault(false)
    }

    fun getMappedUriForPath(localPath: String): Uri? {
        val f = File(localPath)
        val canonical = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
        return pathToUriMap[canonical] ?: pathToUriMap[f.absolutePath]
    }

    private fun createUniqueSlotDir(): File {
        val id = slotCounter.getAndIncrement()
        return File(workspaceDir, "doc_$id").apply { mkdirs() }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !cursor.isNull(index)) {
                    cursor.getString(index)
                } else {
                    null
                }
            } else {
                null
            }
        }
    }.getOrNull()

    private fun takePersistablePermissionBestEffort(uri: Uri, flags: Int) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, flags)
        }.onFailure {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    companion object {
        fun sanitizeFileName(raw: String, defaultExtension: String = "deckcraft"): String {
            val cleaned = raw
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("[\\x00-\\x1F<>:\"|?*]"), "_")
                .trim()
                .ifBlank { "Presentation.$defaultExtension" }

            return if (cleaned.contains('.')) {
                cleaned
            } else {
                "$cleaned.$defaultExtension"
            }
        }
    }
}
