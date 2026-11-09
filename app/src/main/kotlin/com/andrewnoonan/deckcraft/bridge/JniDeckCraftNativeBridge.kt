package com.andrewnoonan.deckcraft.bridge

class JniDeckCraftNativeBridge : DeckCraftNativeBridge {

    init {
        ensureLoaded()
    }

    override external fun createEngine(
        filesDir: String,
        cacheDir: String,
        initialPrefsJson: String?,
    ): Long

    override external fun destroyEngine(handle: Long)

    override external fun onSurfaceCreated(handle: Long)

    override external fun onSurfaceChanged(
        handle: Long,
        widthPx: Int,
        heightPx: Int,
        pixelsPerPoint: Float,
    )

    override external fun onSurfaceDestroyed(handle: Long)

    @JvmName("stepFrame")
    private external fun nativeStepFrame(
        handle: Long,
        timeSecs: Double,
        widthPx: Int,
        heightPx: Int,
        pixelsPerPoint: Float,
        darkMode: Boolean,
        focused: Boolean,
    ): String

    override fun stepFrame(
        handle: Long,
        timeSecs: Double,
        widthPx: Int,
        heightPx: Int,
        pixelsPerPoint: Float,
        darkMode: Boolean,
        focused: Boolean,
    ): PlatformEffectsDto {
        val rawJson = nativeStepFrame(
            handle = handle,
            timeSecs = timeSecs,
            widthPx = widthPx,
            heightPx = heightPx,
            pixelsPerPoint = pixelsPerPoint,
            darkMode = darkMode,
            focused = focused,
        )
        return PlatformEffectsParser.parse(rawJson)
    }

    override external fun consumeWakeRequested(handle: Long): Boolean

    override external fun pushPointerMoved(handle: Long, xPt: Float, yPt: Float)

    override external fun pushPointerGone(handle: Long)

    override external fun pushPointerButton(
        handle: Long,
        xPt: Float,
        yPt: Float,
        button: Int,
        pressed: Boolean,
        modifiers: Int,
    )

    override external fun pushMouseWheel(
        handle: Long,
        deltaX: Float,
        deltaY: Float,
        isLineUnit: Boolean,
        modifiers: Int,
    )

    override external fun pushTouch(
        handle: Long,
        deviceId: Int,
        pointerId: Int,
        phase: Int,
        xPt: Float,
        yPt: Float,
        force: Float,
    )

    override external fun pushZoom(handle: Long, zoomDelta: Float)

    override external fun pushKey(
        handle: Long,
        eguiKeyCode: Int,
        pressed: Boolean,
        repeat: Boolean,
        modifiers: Int,
    )

    override external fun pushText(handle: Long, text: String)

    override external fun syncSystemClipboardText(handle: Long, text: String?)

    private external fun deliverPickedFiles(handle: Long, requestId: Long, paths: Array<String>)

    override fun deliverPickedFiles(handle: Long, requestId: Long, paths: List<String>) {
        deliverPickedFiles(handle, requestId, paths.toTypedArray())
    }

    override external fun openExternalFile(handle: Long, displayName: String, localPath: String)

    override external fun executeCommand(handle: Long, commandId: String)

    override external fun setUiScaleOverride(handle: Long, scale: Float)

    override external fun persistState(handle: Long): String

    companion object {
        @Volatile
        private var loaded = false

        @Synchronized
        fun ensureLoaded() {
            if (!loaded) {
                System.loadLibrary("deckcraft_android")
                loaded = true
            }
        }
    }
}
