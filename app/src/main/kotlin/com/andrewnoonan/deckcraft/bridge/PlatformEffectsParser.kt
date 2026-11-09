package com.andrewnoonan.deckcraft.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object PlatformEffectsParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    private data class WirePickerRequest(
        val id: Long = 0L,
        val mode: String = "open_file",
        val title: String? = null,
        @SerialName("suggestedName")
        val suggestedName: String? = null,
        val extensions: List<String> = emptyList(),
    )

    @Serializable
    private data class WireSavedFileEvent(
        val path: String = "",
    )

    @Serializable
    private data class WirePrintJobRequest(
        val jobName: String = "DeckCraft Presentation",
        val pdfPath: String = "",
    )

    @Serializable
    private data class WireImeRect(
        val x: Float = 0f,
        val y: Float = 0f,
        val width: Float = 0f,
        val height: Float = 0f,
    )

    @Serializable
    private data class WireEffects(
        val cursorIcon: String = "default",
        val wantsIme: Boolean = false,
        val imeRect: WireImeRect? = null,
        val repaintAfterMs: Long = -1L,
        val copyText: String? = null,
        val openUrl: String? = null,
        val windowTitle: String = "DeckCraft",
        val activePath: String? = null,
        val hasUnsavedChanges: Boolean = false,
        val closeRequested: Boolean = false,
        val pickerRequest: WirePickerRequest? = null,
        val savedFile: WireSavedFileEvent? = null,
        val printJob: WirePrintJobRequest? = null,
    )

    fun parse(rawJson: String): PlatformEffectsDto {
        if (rawJson.isBlank()) return PlatformEffectsDto()
        return runCatching {
            val w = json.decodeFromString<WireEffects>(rawJson)
            PlatformEffectsDto(
                cursorIcon = w.cursorIcon,
                wantsIme = w.wantsIme,
                imeRect = w.imeRect?.let {
                    ImeRectDto(
                        x = it.x,
                        y = it.y,
                        width = it.width,
                        height = it.height,
                    )
                },
                repaintAfterMs = w.repaintAfterMs,
                copyText = w.copyText,
                openUrl = w.openUrl,
                windowTitle = w.windowTitle.ifBlank { "DeckCraft" },
                activePath = w.activePath,
                hasUnsavedChanges = w.hasUnsavedChanges,
                closeRequested = w.closeRequested,
                pickerRequest = w.pickerRequest?.let {
                    PickerRequestDto(
                        id = it.id,
                        mode = it.mode,
                        title = it.title,
                        suggestedName = it.suggestedName,
                        extensions = it.extensions,
                    )
                },
                savedFile = w.savedFile?.takeIf { it.path.isNotBlank() }?.let {
                    SavedFileEventDto(path = it.path)
                },
                printJob = w.printJob?.takeIf { it.pdfPath.isNotBlank() }?.let {
                    PrintJobRequestDto(
                        jobName = it.jobName.ifBlank { "DeckCraft Presentation" },
                        pdfPath = it.pdfPath,
                    )
                },
            )
        }.getOrElse {
            PlatformEffectsDto()
        }
    }
}
