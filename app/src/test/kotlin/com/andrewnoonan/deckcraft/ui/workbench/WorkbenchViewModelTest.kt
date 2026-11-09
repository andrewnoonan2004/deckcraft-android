package com.andrewnoonan.deckcraft.ui.workbench

import com.andrewnoonan.deckcraft.bridge.PickerRequestDto
import com.andrewnoonan.deckcraft.bridge.PlatformEffectsDto
import com.andrewnoonan.deckcraft.bridge.PrintJobRequestDto
import com.andrewnoonan.deckcraft.bridge.SavedFileEventDto
import com.andrewnoonan.deckcraft.platform.ClipboardBridge
import com.andrewnoonan.deckcraft.platform.PreferencesStore
import com.andrewnoonan.deckcraft.platform.PrintBridge
import com.andrewnoonan.deckcraft.platform.ShareBridge
import com.andrewnoonan.deckcraft.platform.UrlLauncherBridge
import com.andrewnoonan.deckcraft.storage.DocumentStorageManager
import com.andrewnoonan.deckcraft.testing.FakeDeckCraftNativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkbenchViewModelTest {

    private class FakeStorage : DocumentStorageManager {
        val synced = mutableListOf<String>()
        override fun syncSavedPathToOrigin(savedPath: String): Boolean { synced += savedPath; return true }
    }
    private class FakeClipboard : ClipboardBridge {
        var currentClip: String? = null
        override fun setText(text: String) { currentClip = text }
        override fun getText(): String? = currentClip
    }
    private class FakePrint : PrintBridge {
        val jobs = mutableListOf<Pair<String, String>>()
        override fun printPdfFile(jobName: String, pdfPath: String): Boolean { jobs += jobName to pdfPath; return true }
    }
    private class FakePrefs : PreferencesStore {
        var engine: String? = """{"recent":[]}"""
        var expanded = true
        override fun loadEnginePrefs() = engine
        override fun saveEnginePrefs(json: String) { engine = json }
        override fun isDesktopBarExpanded() = expanded
        override fun setDesktopBarExpanded(expanded: Boolean) { this.expanded = expanded }
    }
    private class FakeUrl : UrlLauncherBridge {
        val opened = mutableListOf<String>()
        override fun openHttpsUrl(url: String): Boolean { opened += url; return true }
    }
    private class FakeShare : ShareBridge {
        val shared = mutableListOf<String>()
        override fun sharePresentationFile(localPath: String, title: String): Boolean { shared += localPath; return true }
    }

    private lateinit var bridge: FakeDeckCraftNativeBridge
    private lateinit var storage: FakeStorage
    private lateinit var clipboard: FakeClipboard
    private lateinit var print: FakePrint
    private lateinit var prefs: FakePrefs
    private lateinit var url: FakeUrl
    private lateinit var share: FakeShare
    private lateinit var vm: WorkbenchViewModel

    @Before
    fun setUp() {
        bridge = FakeDeckCraftNativeBridge()
        storage = FakeStorage(); clipboard = FakeClipboard(); print = FakePrint()
        prefs = FakePrefs(); url = FakeUrl(); share = FakeShare()
        vm = WorkbenchViewModel(bridge, storage, clipboard, print, prefs, url, share)
        vm.initializeEngine("/files", "/cache")
    }

    @Test
    fun initializeEngine_passesSavedPrefsAndIsIdempotent() {
        assertEquals("""{"recent":[]}""", bridge.createdWithPrefs)
        assertEquals(7L, vm.initializeEngine("/files", "/cache"))
        assertEquals(1, bridge.calls.count { it == "createEngine" })
    }

    @Test
    fun stickyModifiers_toggleAndRightClickLatchIsConsumedOnce() {
        vm.toggleCtrl(); vm.toggleShift(); vm.toggleAlt(); vm.toggleRightClickNextTap()
        val s = vm.uiState.value.stickyModifiers
        assertTrue(s.ctrl && s.shift && s.alt && s.rightClickLatch)
        vm.consumeRightClickMode()
        assertFalse(vm.uiState.value.stickyModifiers.rightClickLatch)
        assertTrue(vm.uiState.value.stickyModifiers.ctrl)
        vm.clearStickyModifiers()
        assertFalse(vm.uiState.value.stickyModifiers.ctrl)
    }

    @Test
    fun adjustUiScale_clampsBetween75And200AndForwardsToEngine() {
        repeat(20) { vm.adjustUiScale(10) }
        assertEquals(200, vm.uiState.value.uiScalePercent)
        repeat(30) { vm.adjustUiScale(-10) }
        assertEquals(75, vm.uiState.value.uiScalePercent)
        assertEquals("scale(0.75)", bridge.calls.last())
    }

    @Test
    fun dispatchCommand_forwardsNonBlankCommands() {
        vm.dispatchCommand("show.start")
        vm.dispatchCommand("  ")
        assertEquals(listOf("command(show.start)"), bridge.calls.filter { it.startsWith("command") })
    }

    @Test
    fun onFrameEffects_routesClipboardUrlSavePrintAndUpdatesState() {
        vm.onFrameEffects(
            PlatformEffectsDto(
                copyText = "Title slide",
                openUrl = "https://getartcraft.com",
                windowTitle = "pitch.pptx — DeckCraft",
                activePath = "/files/saf_workspace/doc_1/pitch.pptx",
                hasUnsavedChanges = true,
                wantsIme = true,
                savedFile = SavedFileEventDto("/files/saf_workspace/doc_1/pitch.pptx"),
                printJob = PrintJobRequestDto("pitch.pptx", "/cache/print.pdf"),
                pickerRequest = PickerRequestDto(id = 3, mode = "open_file"),
            ),
        )
        assertEquals("Title slide", clipboard.currentClip)
        assertEquals(listOf("https://getartcraft.com"), url.opened)
        assertEquals(listOf("/files/saf_workspace/doc_1/pitch.pptx"), storage.synced)
        assertEquals(listOf("pitch.pptx" to "/cache/print.pdf"), print.jobs)
        val s = vm.uiState.value
        assertEquals("pitch.pptx — DeckCraft", s.windowTitle)
        assertTrue(s.hasUnsavedChanges)
        assertTrue(s.wantsSoftKeyboard)
        assertEquals(3L, s.pendingPickerRequest!!.id)
    }

    @Test
    fun onFrameEffects_blocksNonHttpsUrls() {
        vm.onFrameEffects(PlatformEffectsDto(openUrl = "http://insecure.example"))
        assertTrue(url.opened.isEmpty())
    }

    @Test
    fun onFilesPicked_clearsPendingRequestAndDeliversPaths() {
        vm.onFrameEffects(PlatformEffectsDto(pickerRequest = PickerRequestDto(id = 9, mode = "open_file")))
        vm.onFilesPicked(9, listOf("/files/a.pptx"))
        assertNull(vm.uiState.value.pendingPickerRequest)
        assertEquals("picked(9, [/files/a.pptx])".replace(", ", ","), bridge.calls.last().replace(", ", ","))
    }

    @Test
    fun shareActiveDocument_requiresActivePath() {
        assertFalse(vm.shareActiveDocument())
        vm.onFrameEffects(PlatformEffectsDto(activePath = "/files/deck.pptx", windowTitle = "deck"))
        assertTrue(vm.shareActiveDocument())
        assertEquals(listOf("/files/deck.pptx"), share.shared)
    }

    @Test
    fun desktopBarExpanded_isPersisted() {
        vm.toggleDesktopBarExpanded()
        assertFalse(prefs.expanded)
        assertFalse(vm.uiState.value.isDesktopBarExpanded)
    }

    @Test
    fun shutdownEngine_persistsPrefsThenDestroys() {
        bridge.persistedJson = """{"theme":"Light"}"""
        vm.shutdownEngine()
        assertEquals("""{"theme":"Light"}""", prefs.engine)
        assertEquals("destroyEngine(7)", bridge.calls.last())
        assertEquals(0L, vm.engineHandle)
    }
}
