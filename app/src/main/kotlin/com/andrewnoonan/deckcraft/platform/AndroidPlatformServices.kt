package com.andrewnoonan.deckcraft.platform

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import com.andrewnoonan.deckcraft.storage.SafMimeTypeMapper
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class AndroidClipboardBridge(
    private val context: Context,
) : ClipboardBridge {
    override fun setText(text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText("DeckCraft", text))
    }

    override fun getText(): String? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        return clip.getItemAt(0)?.coerceToText(context)?.toString()
    }
}

class AndroidPrintBridge(
    private val activityProvider: () -> Activity?,
) : PrintBridge {
    override fun printPdfFile(jobName: String, pdfPath: String): Boolean {
        val activity = activityProvider() ?: return false
        val pdfFile = File(pdfPath)
        if (!pdfFile.isFile) return false
        val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return false
        val safeJobName = jobName.ifBlank { "DeckCraft Presentation" }
        return runCatching {
            activity.runOnUiThread {
                printManager.print(
                    safeJobName,
                    PdfFilePrintDocumentAdapter(safeJobName, pdfFile),
                    PrintAttributes.Builder().build(),
                )
            }
            true
        }.getOrDefault(false)
    }

    private class PdfFilePrintDocumentAdapter(
        private val jobName: String,
        private val sourceFile: File,
    ) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(
                if (jobName.lowercase().endsWith(".pdf")) jobName else "$jobName.pdf",
            )
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback.onLayoutFinished(info, newAttributes != oldAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            runCatching {
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            if (cancellationSignal?.isCanceled == true) {
                                callback.onWriteCancelled()
                                return
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            }.onFailure { error ->
                callback.onWriteFailed(error.message ?: "Failed to write PDF to system print spooler")
            }
        }
    }
}

@Serializable
private data class ShellPrefs(
    val desktopBarExpanded: Boolean = true,
)

class FilePreferencesStore(
    private val filesDir: File,
) : PreferencesStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val enginePrefsFile = File(filesDir, "deckcraft_engine_prefs.json")
    private val shellPrefsFile = File(filesDir, "deckcraft_shell_prefs.json")

    init {
        filesDir.mkdirs()
    }

    @Synchronized
    override fun loadEnginePrefs(): String? {
        if (!enginePrefsFile.isFile) return null
        return runCatching { enginePrefsFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    override fun saveEnginePrefs(json: String) {
        if (json.isBlank()) return
        writeAtomically(enginePrefsFile, json)
    }

    @Synchronized
    override fun isDesktopBarExpanded(): Boolean {
        if (!shellPrefsFile.isFile) return true
        return runCatching {
            json.decodeFromString<ShellPrefs>(shellPrefsFile.readText()).desktopBarExpanded
        }.getOrDefault(true)
    }

    @Synchronized
    override fun setDesktopBarExpanded(expanded: Boolean) {
        runCatching {
            writeAtomically(shellPrefsFile, json.encodeToString(ShellPrefs(desktopBarExpanded = expanded)))
        }
    }

    private fun writeAtomically(target: File, content: String) {
        runCatching {
            val tmp = File(filesDir, ".${target.name}.tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }
}

class AndroidUrlLauncherBridge(
    private val context: Context,
) : UrlLauncherBridge {
    override fun openHttpsUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) {
            return false
        }
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}

class AndroidShareBridge(
    private val context: Context,
) : ShareBridge {
    override fun sharePresentationFile(localPath: String, title: String): Boolean {
        val file = File(localPath)
        if (!file.isFile) return false
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val mimeType = SafMimeTypeMapper.toCreateDocumentMimeType(file.name, emptyList())
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }
}
