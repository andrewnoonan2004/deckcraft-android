package com.andrewnoonan.deckcraft.testing

import com.andrewnoonan.deckcraft.bridge.DeckCraftNativeBridge
import com.andrewnoonan.deckcraft.bridge.PlatformEffectsDto

/** Records every bridge call as a readable string so tests can assert on engine traffic. */
class FakeDeckCraftNativeBridge(
    private val handle: Long = 7L,
    var persistedJson: String = """{"theme":"Dark"}""",
) : DeckCraftNativeBridge {
    val calls = mutableListOf<String>()
    var createdWithPrefs: String? = null

    override fun createEngine(filesDir: String, cacheDir: String, initialPrefsJson: String?): Long {
        createdWithPrefs = initialPrefsJson
        calls += "createEngine"
        return handle
    }
    override fun destroyEngine(handle: Long) { calls += "destroyEngine($handle)" }
    override fun onSurfaceCreated(handle: Long) { calls += "onSurfaceCreated" }
    override fun onSurfaceChanged(handle: Long, widthPx: Int, heightPx: Int, pixelsPerPoint: Float) {
        calls += "onSurfaceChanged($widthPx,$heightPx)"
    }
    override fun onSurfaceDestroyed(handle: Long) { calls += "onSurfaceDestroyed" }
    override fun stepFrame(
        handle: Long, timeSecs: Double, widthPx: Int, heightPx: Int,
        pixelsPerPoint: Float, darkMode: Boolean, focused: Boolean,
    ): PlatformEffectsDto = PlatformEffectsDto()
    override fun consumeWakeRequested(handle: Long): Boolean = false
    override fun pushPointerMoved(handle: Long, xPt: Float, yPt: Float) { calls += "move($xPt,$yPt)" }
    override fun pushPointerGone(handle: Long) { calls += "gone" }
    override fun pushPointerButton(handle: Long, xPt: Float, yPt: Float, button: Int, pressed: Boolean, modifiers: Int) {
        calls += "button($button,$pressed,mods=$modifiers)"
    }
    override fun pushMouseWheel(handle: Long, deltaX: Float, deltaY: Float, isLineUnit: Boolean, modifiers: Int) {
        calls += "wheel($deltaX,$deltaY,mods=$modifiers)"
    }
    override fun pushTouch(handle: Long, deviceId: Int, pointerId: Int, phase: Int, xPt: Float, yPt: Float, force: Float) {
        calls += "touch($pointerId,$phase)"
    }
    override fun pushZoom(handle: Long, zoomDelta: Float) { calls += "zoom($zoomDelta)" }
    override fun pushKey(handle: Long, eguiKeyCode: Int, pressed: Boolean, repeat: Boolean, modifiers: Int) {
        calls += "key($eguiKeyCode,$pressed,mods=$modifiers)"
    }
    override fun pushText(handle: Long, text: String) { calls += "text($text)" }
    override fun syncSystemClipboardText(handle: Long, text: String?) { calls += "clipboard($text)" }
    override fun deliverPickedFiles(handle: Long, requestId: Long, paths: List<String>) {
        calls += "picked($requestId,$paths)"
    }
    override fun openExternalFile(handle: Long, displayName: String, localPath: String) {
        calls += "openExternal($displayName)"
    }
    override fun executeCommand(handle: Long, commandId: String) { calls += "command($commandId)" }
    override fun setUiScaleOverride(handle: Long, scale: Float) { calls += "scale($scale)" }
    override fun persistState(handle: Long): String { calls += "persist"; return persistedJson }
}
