package com.andrewnoonan.deckcraft.ui.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.andrewnoonan.deckcraft.bridge.DeckCraftNativeBridge
import com.andrewnoonan.deckcraft.bridge.PickerRequestDto
import com.andrewnoonan.deckcraft.bridge.PlatformEffectsDto
import com.andrewnoonan.deckcraft.input.EguiKeys
import com.andrewnoonan.deckcraft.input.StickyModifierState
import com.andrewnoonan.deckcraft.platform.ClipboardBridge
import com.andrewnoonan.deckcraft.platform.PreferencesStore
import com.andrewnoonan.deckcraft.platform.PrintBridge
import com.andrewnoonan.deckcraft.platform.ShareBridge
import com.andrewnoonan.deckcraft.platform.UrlLauncherBridge
import com.andrewnoonan.deckcraft.storage.DocumentStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class WorkbenchUiState(
    val windowTitle: String = "DeckCraft",
    val activeDocumentPath: String? = null,
    val hasUnsavedChanges: Boolean = false,
    val stickyModifiers: StickyModifierState = StickyModifierState(),
    val isDesktopBarExpanded: Boolean = true,
    val isDesktopBarVisible: Boolean = true,
    val uiScalePercent: Int = 100,
    val pendingPickerRequest: PickerRequestDto? = null,
    val wantsSoftKeyboard: Boolean = false,
    val closeRequested: Boolean = false,
    val isFullscreen: Boolean = false,
    val inSlideshow: Boolean = false,
)

class WorkbenchViewModel(
    private val nativeBridge: DeckCraftNativeBridge,
    private val storageManager: DocumentStorageManager,
    private val clipboardBridge: ClipboardBridge,
    private val printBridge: PrintBridge,
    private val preferencesStore: PreferencesStore,
    private val urlLauncherBridge: UrlLauncherBridge,
    private val shareBridge: ShareBridge,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        WorkbenchUiState(
            isDesktopBarExpanded = preferencesStore.isDesktopBarExpanded(),
        ),
    )
    val uiState: StateFlow<WorkbenchUiState> = _uiState.asStateFlow()

    var engineHandle: Long = 0L
        private set

    @Synchronized
    fun initializeEngine(filesDir: String, cacheDir: String): Long {
        if (engineHandle != 0L) {
            return engineHandle
        }
        val initialPrefs = preferencesStore.loadEnginePrefs()
        engineHandle = nativeBridge.createEngine(filesDir, cacheDir, initialPrefs)
        return engineHandle
    }

    fun toggleCtrl() {
        _uiState.update { state ->
            state.copy(stickyModifiers = state.stickyModifiers.copy(ctrl = !state.stickyModifiers.ctrl))
        }
    }

    fun toggleShift() {
        _uiState.update { state ->
            state.copy(stickyModifiers = state.stickyModifiers.copy(shift = !state.stickyModifiers.shift))
        }
    }

    fun toggleAlt() {
        _uiState.update { state ->
            state.copy(stickyModifiers = state.stickyModifiers.copy(alt = !state.stickyModifiers.alt))
        }
    }

    fun toggleRightClickNextTap() {
        _uiState.update { state ->
            state.copy(
                stickyModifiers = state.stickyModifiers.copy(
                    rightClickLatch = !state.stickyModifiers.rightClickLatch,
                ),
            )
        }
    }

    fun consumeRightClickMode() {
        _uiState.update { state ->
            if (!state.stickyModifiers.rightClickLatch) {
                state
            } else {
                state.copy(stickyModifiers = state.stickyModifiers.copy(rightClickLatch = false))
            }
        }
    }

    fun clearStickyModifiers() {
        _uiState.update { state -> state.copy(stickyModifiers = StickyModifierState()) }
    }

    fun setDesktopBarExpanded(expanded: Boolean) {
        preferencesStore.setDesktopBarExpanded(expanded)
        _uiState.update { state -> state.copy(isDesktopBarExpanded = expanded) }
    }

    fun toggleDesktopBarExpanded() {
        val next = !_uiState.value.isDesktopBarExpanded
        setDesktopBarExpanded(next)
    }

    fun setDesktopBarVisible(visible: Boolean) {
        _uiState.update { state -> state.copy(isDesktopBarVisible = visible) }
    }

    fun adjustUiScale(deltaPercent: Int) {
        val nextPercent = (_uiState.value.uiScalePercent + deltaPercent).coerceIn(75, 200)
        _uiState.update { state -> state.copy(uiScalePercent = nextPercent) }
        val handle = engineHandle
        if (handle != 0L) {
            nativeBridge.setUiScaleOverride(handle, nextPercent / 100.0f)
        }
    }

    fun dispatchCommand(commandId: String) {
        val handle = engineHandle
        if (handle != 0L && commandId.isNotBlank()) {
            nativeBridge.executeCommand(handle, commandId)
        }
    }

    fun sendEscapeKey() {
        val handle = engineHandle
        if (handle != 0L) {
            nativeBridge.pushKey(handle, EguiKeys.ESCAPE, true, false, 0)
            nativeBridge.pushKey(handle, EguiKeys.ESCAPE, false, false, 0)
        }
    }

    fun requestSoftKeyboardToggle() {
        _uiState.update { state -> state.copy(wantsSoftKeyboard = !state.wantsSoftKeyboard) }
    }

    fun shareActiveDocument(): Boolean {
        val path = _uiState.value.activeDocumentPath ?: return false
        if (path.isBlank()) return false
        return shareBridge.sharePresentationFile(path, _uiState.value.windowTitle)
    }

    fun onFrameEffects(effects: PlatformEffectsDto) {
        effects.copyText?.let { text ->
            clipboardBridge.setText(text)
        }
        effects.openUrl?.let { url ->
            if (url.trim().startsWith("https://", ignoreCase = true)) {
                urlLauncherBridge.openHttpsUrl(url.trim())
            }
        }
        effects.savedFile?.let { saved ->
            if (saved.path.isNotBlank()) {
                storageManager.syncSavedPathToOrigin(saved.path)
            }
        }
        effects.printJob?.let { job ->
            if (job.pdfPath.isNotBlank()) {
                printBridge.printPdfFile(job.jobName, job.pdfPath)
            }
        }

        _uiState.update { state ->
            val nextActivePath = when (val incomingPath = effects.activePath) {
                null -> state.activeDocumentPath
                else -> incomingPath.ifBlank { null }
            }
            val nextTitle = when {
                effects.activePath != null -> effects.windowTitle.ifBlank { "DeckCraft" }
                effects.windowTitle != "DeckCraft" -> effects.windowTitle
                state.activeDocumentPath != null -> state.windowTitle
                else -> "DeckCraft"
            }
            state.copy(
                windowTitle = nextTitle,
                activeDocumentPath = nextActivePath,
                hasUnsavedChanges = if (effects.activePath != null) effects.hasUnsavedChanges else state.hasUnsavedChanges,
                wantsSoftKeyboard = effects.wantsIme,
                closeRequested = effects.closeRequested,
                pendingPickerRequest = effects.pickerRequest ?: state.pendingPickerRequest,
                isFullscreen = effects.fullscreen,
                inSlideshow = effects.inSlideshow,
            )
        }
    }

    fun clearPendingPickerRequest() {
        _uiState.update { state -> state.copy(pendingPickerRequest = null) }
    }

    fun onFilesPicked(requestId: Long, localPaths: List<String>) {
        _uiState.update { state ->
            if (state.pendingPickerRequest?.id == requestId || state.pendingPickerRequest != null) {
                state.copy(pendingPickerRequest = null)
            } else {
                state
            }
        }
        val handle = engineHandle
        if (handle != 0L) {
            nativeBridge.deliverPickedFiles(handle, requestId, localPaths)
        }
    }

    fun openExternalDocument(displayName: String, localPath: String) {
        val handle = engineHandle
        if (handle != 0L && localPath.isNotBlank()) {
            nativeBridge.openExternalFile(handle, displayName, localPath)
        }
    }

    fun persistAndSavePreferences() {
        val handle = engineHandle
        if (handle != 0L) {
            val json = nativeBridge.persistState(handle)
            if (json.isNotBlank()) {
                preferencesStore.saveEnginePrefs(json)
            }
        }
    }

    @Synchronized
    fun shutdownEngine() {
        val handle = engineHandle
        if (handle != 0L) {
            persistAndSavePreferences()
            nativeBridge.destroyEngine(handle)
            engineHandle = 0L
        }
    }

    override fun onCleared() {
        shutdownEngine()
        super.onCleared()
    }
}

class WorkbenchViewModelFactory(
    private val nativeBridge: DeckCraftNativeBridge,
    private val storageManager: DocumentStorageManager,
    private val clipboardBridge: ClipboardBridge,
    private val printBridge: PrintBridge,
    private val preferencesStore: PreferencesStore,
    private val urlLauncherBridge: UrlLauncherBridge,
    private val shareBridge: ShareBridge,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WorkbenchViewModel::class.java)) {
            return WorkbenchViewModel(
                nativeBridge = nativeBridge,
                storageManager = storageManager,
                clipboardBridge = clipboardBridge,
                printBridge = printBridge,
                preferencesStore = preferencesStore,
                urlLauncherBridge = urlLauncherBridge,
                shareBridge = shareBridge,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
