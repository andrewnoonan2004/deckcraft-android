package com.andrewnoonan.deckcraft

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.andrewnoonan.deckcraft.bridge.DeckCraftNativeBridge
import com.andrewnoonan.deckcraft.bridge.JniDeckCraftNativeBridge
import com.andrewnoonan.deckcraft.bridge.PickerRequestDto
import com.andrewnoonan.deckcraft.platform.AndroidClipboardBridge
import com.andrewnoonan.deckcraft.platform.AndroidPrintBridge
import com.andrewnoonan.deckcraft.platform.AndroidShareBridge
import com.andrewnoonan.deckcraft.platform.AndroidUrlLauncherBridge
import com.andrewnoonan.deckcraft.platform.CrashReporter
import com.andrewnoonan.deckcraft.platform.FilePreferencesStore
import com.andrewnoonan.deckcraft.storage.SafDocumentStorageManager
import com.andrewnoonan.deckcraft.storage.SafMimeTypeMapper
import com.andrewnoonan.deckcraft.ui.CrashReportScreen
import com.andrewnoonan.deckcraft.ui.gl.DeckCraftGlSurfaceView
import com.andrewnoonan.deckcraft.ui.theme.DeckCraftTheme
import com.andrewnoonan.deckcraft.ui.workbench.DesktopControlOverlay
import com.andrewnoonan.deckcraft.ui.workbench.WorkbenchViewModel
import com.andrewnoonan.deckcraft.ui.workbench.WorkbenchViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object WorkbenchDependencies {
    @Volatile
    var overrideBridgeForTesting: DeckCraftNativeBridge? = null

    @Volatile
    var disableGlThreadForTesting: Boolean = false

    fun obtainNativeBridge(): DeckCraftNativeBridge {
        return overrideBridgeForTesting ?: JniDeckCraftNativeBridge()
    }
}

private data class CreateDocInput(
    val mimeType: String,
    val suggestedName: String,
)

private class DynamicCreateDocumentContract : ActivityResultContract<CreateDocInput, Uri?>() {
    override fun createIntent(context: Context, input: CreateDocInput): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.suggestedName)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return intent.takeIf { resultCode == android.app.Activity.RESULT_OK }?.data
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var nativeBridge: DeckCraftNativeBridge
    private lateinit var storageManager: SafDocumentStorageManager
    private lateinit var clipboardBridge: AndroidClipboardBridge
    private lateinit var viewModel: WorkbenchViewModel

    private var activeSafRequest: PickerRequestDto? = null
    private var glSurfaceViewRef by mutableStateOf<DeckCraftGlSurfaceView?>(null)

    private val openSingleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val req = activeSafRequest
        activeSafRequest = null
        if (req == null) return@registerForActivityResult
        if (uri == null) {
            viewModel.onFilesPicked(req.id, emptyList())
            return@registerForActivityResult
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val imported = storageManager.importReadableUri(uri)
            withContext(Dispatchers.Main) {
                val paths = imported?.let { listOf(it.localFile.absolutePath) } ?: emptyList()
                viewModel.onFilesPicked(req.id, paths)
            }
        }
    }

    private val openMultipleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val req = activeSafRequest
        activeSafRequest = null
        if (req == null) return@registerForActivityResult
        if (uris.isEmpty()) {
            viewModel.onFilesPicked(req.id, emptyList())
            return@registerForActivityResult
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val paths = uris.mapNotNull { uri -> storageManager.importReadableUri(uri)?.localFile?.absolutePath }
            withContext(Dispatchers.Main) {
                viewModel.onFilesPicked(req.id, paths)
            }
        }
    }

    private val createDocumentLauncher = registerForActivityResult(
        DynamicCreateDocumentContract(),
    ) { uri ->
        val req = activeSafRequest
        activeSafRequest = null
        if (req == null) return@registerForActivityResult
        if (uri == null) {
            viewModel.onFilesPicked(req.id, emptyList())
            return@registerForActivityResult
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val fallbackName = req.suggestedName?.ifBlank { null } ?: "Presentation.deckcraft"
            val destPath = runCatching {
                storageManager.registerWritableTargetUri(uri, fallbackName).absolutePath
            }.getOrNull()
            withContext(Dispatchers.Main) {
                val paths = destPath?.let { listOf(it) } ?: emptyList()
                viewModel.onFilesPicked(req.id, paths)
            }
        }
    }

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        val req = activeSafRequest
        activeSafRequest = null
        if (req == null) return@registerForActivityResult
        if (treeUri == null) {
            viewModel.onFilesPicked(req.id, emptyList())
            return@registerForActivityResult
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val folderPath: String? = null // DeckCraft engine never requests folder pickers
            withContext(Dispatchers.Main) {
                val paths = folderPath?.let { listOf(it) } ?: emptyList()
                viewModel.onFilesPicked(req.id, paths)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val appCtx = applicationContext
        val crashReporter = (application as? DeckCraftApplication)?.crashReporter
            ?: CrashReporter(appCtx.filesDir)
        val previousCrashReport = crashReporter.consumePendingReport()
        var startupError: String? = null

        try {
            nativeBridge = WorkbenchDependencies.obtainNativeBridge()
            storageManager = SafDocumentStorageManager(appCtx.contentResolver, appCtx.filesDir)
            clipboardBridge = AndroidClipboardBridge(appCtx)

            val factory = WorkbenchViewModelFactory(
                nativeBridge = nativeBridge,
                storageManager = storageManager,
                clipboardBridge = clipboardBridge,
                printBridge = AndroidPrintBridge { this },
                preferencesStore = FilePreferencesStore(appCtx.filesDir),
                urlLauncherBridge = AndroidUrlLauncherBridge(appCtx),
                shareBridge = AndroidShareBridge(appCtx),
            )
            viewModel = ViewModelProvider(this, factory)[WorkbenchViewModel::class.java]
            viewModel.initializeEngine(
                filesDir = appCtx.filesDir.absolutePath,
                cacheDir = appCtx.cacheDir.absolutePath,
            )
        } catch (t: Throwable) {
            val described = CrashReporter.describe(t)
            crashReporter.recordJavaCrash(Thread.currentThread(), t)
            startupError = described
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (::viewModel.isInitialized && viewModel.uiState.value.hasUnsavedChanges) {
                        viewModel.dispatchCommand("file.quit")
                        glSurfaceViewRef?.requestRender()
                    } else {
                        if (::viewModel.isInitialized) {
                            viewModel.persistAndSavePreferences()
                        }
                        finish()
                    }
                }
            },
        )

        if (savedInstanceState == null && ::viewModel.isInitialized) {
            handleIncomingDocumentIntent(intent)
        }

        setContent {
            DeckCraftTheme {
                var pendingReportToShow by remember {
                    mutableStateOf(startupError ?: previousCrashReport)
                }

                if (pendingReportToShow != null) {
                    val isFatal = startupError != null
                    CrashReportScreen(
                        title = if (isFatal) "DeckCraft Startup Error" else "Previous Crash Report",
                        report = pendingReportToShow!!,
                        onContinue = if (isFatal) null else { { pendingReportToShow = null } },
                    )
                } else if (::viewModel.isInitialized) {
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                    LaunchedEffect(uiState.pendingPickerRequest?.id) {
                        val req = uiState.pendingPickerRequest ?: return@LaunchedEffect
                        viewModel.clearPendingPickerRequest()
                        launchSafPicker(req)
                    }

                    LaunchedEffect(uiState.wantsSoftKeyboard) {
                        val view = glSurfaceViewRef ?: return@LaunchedEffect
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            ?: return@LaunchedEffect
                        if (uiState.wantsSoftKeyboard) {
                            view.requestFocus()
                            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                        } else {
                            imm.hideSoftInputFromWindow(view.windowToken, 0)
                        }
                    }

                    LaunchedEffect(uiState.closeRequested) {
                        if (uiState.closeRequested) {
                            viewModel.persistAndSavePreferences()
                            finish()
                        }
                    }

                    LaunchedEffect(uiState.windowTitle, uiState.hasUnsavedChanges) {
                        val base = uiState.windowTitle.ifBlank { getString(R.string.app_name) }
                        title = if (uiState.hasUnsavedChanges) "• $base" else base
                    }

                    val latestSticky = remember(uiState.stickyModifiers) { uiState.stickyModifiers }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .imePadding(),
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                DeckCraftGlSurfaceView(
                                    context = ctx,
                                    nativeBridge = nativeBridge,
                                    engineHandleProvider = { viewModel.engineHandle },
                                    stickyModifiersProvider = { viewModel.uiState.value.stickyModifiers },
                                    onConsumeRightClickMode = { viewModel.consumeRightClickMode() },
                                    onEffects = { effects -> viewModel.onFrameEffects(effects) },
                                    enableGlThread = !WorkbenchDependencies.disableGlThreadForTesting,
                                ).also { glView ->
                                    glSurfaceViewRef = glView
                                }
                            },
                            update = { _ ->
                                val _unused = latestSticky
                            },
                        )

                        DesktopControlOverlay(
                            state = uiState,
                            onToggleExpanded = viewModel::toggleDesktopBarExpanded,
                            onToggleCtrl = viewModel::toggleCtrl,
                            onToggleShift = viewModel::toggleShift,
                            onToggleAlt = viewModel::toggleAlt,
                            onToggleRightClick = viewModel::toggleRightClickNextTap,
                            onCommand = { cmd ->
                                viewModel.dispatchCommand(cmd)
                                glSurfaceViewRef?.requestRender()
                            },
                            onToggleKeyboard = viewModel::requestSoftKeyboardToggle,
                            onShareDocument = { viewModel.shareActiveDocument() },
                            onAdjustScale = { delta ->
                                viewModel.adjustUiScale(delta)
                                glSurfaceViewRef?.requestRender()
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 10.dp, start = 12.dp, end = 12.dp),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingDocumentIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        glSurfaceViewRef?.onResume()
        syncClipboardIntoEngine()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            syncClipboardIntoEngine()
            glSurfaceViewRef?.requestRender()
        }
    }

    override fun onPause() {
        glSurfaceViewRef?.onPause()
        super.onPause()
    }

    override fun onStop() {
        if (::viewModel.isInitialized) {
            viewModel.persistAndSavePreferences()
        }
        super.onStop()
    }

    private fun syncClipboardIntoEngine() {
        if (!::viewModel.isInitialized || !::clipboardBridge.isInitialized) return
        val handle = viewModel.engineHandle
        if (handle == 0L) return
        val text = clipboardBridge.getText() ?: return
        nativeBridge.syncSystemClipboardText(handle, text)
    }

    private fun launchSafPicker(req: PickerRequestDto) {
        activeSafRequest = req
        runCatching {
            when (req.mode) {
                "open_multiple" -> {
                    openMultipleLauncher.launch(SafMimeTypeMapper.toOpenMimeTypes(req.extensions))
                }
                "save_file" -> {
                    val suggested = SafDocumentStorageManager.sanitizeFileName(
                        req.suggestedName ?: "Presentation.deckcraft",
                    )
                    val mime = SafMimeTypeMapper.toCreateDocumentMimeType(suggested, req.extensions)
                    createDocumentLauncher.launch(CreateDocInput(mimeType = mime, suggestedName = suggested))
                }
                "pick_folder" -> {
                    openFolderLauncher.launch(null)
                }
                else -> {
                    openSingleLauncher.launch(SafMimeTypeMapper.toOpenMimeTypes(req.extensions))
                }
            }
        }.onFailure {
            activeSafRequest = null
            viewModel.onFilesPicked(req.id, emptyList())
        }
    }

    private fun handleIncomingDocumentIntent(incoming: Intent?) {
        if (incoming == null) return
        if (!::viewModel.isInitialized || !::storageManager.isInitialized) return
        val uris = extractDocumentUris(incoming)
        if (uris.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val importedList = uris.mapNotNull { uri ->
                storageManager.importReadableUri(uri)
            }
            withContext(Dispatchers.Main) {
                for (doc in importedList) {
                    viewModel.openExternalDocument(doc.displayName, doc.localFile.absolutePath)
                }
                try {
                    glSurfaceViewRef?.requestRender()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun extractDocumentUris(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW,
            Intent.ACTION_EDIT,
            -> {
                intent.data?.let { out += it }
            }

            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { out += it }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { out.addAll(it) }
            }
        }
        val clip = intent.clipData
        if (clip != null && (
                intent.action == Intent.ACTION_VIEW ||
                    intent.action == Intent.ACTION_EDIT ||
                    intent.action == Intent.ACTION_SEND ||
                    intent.action == Intent.ACTION_SEND_MULTIPLE
                )
        ) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { out += it }
            }
        }
        return out.toList()
    }
}
