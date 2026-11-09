package com.andrewnoonan.deckcraft.ui.gl

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.text.InputType
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.ScaleGestureDetector
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.andrewnoonan.deckcraft.bridge.DeckCraftNativeBridge
import com.andrewnoonan.deckcraft.bridge.PlatformEffectsDto
import com.andrewnoonan.deckcraft.input.DesktopInputTranslator
import com.andrewnoonan.deckcraft.input.EguiKeys
import com.andrewnoonan.deckcraft.input.StickyModifierState
import com.andrewnoonan.deckcraft.platform.CrashReporter
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3.0 surface hosting the DeckCraft egui workbench.
 *
 * Mouse hover / buttons / wheel, stylus, multi-touch and hardware keyboard events are forwarded
 * to the Rust engine through [DesktopInputTranslator]; pinch gestures become egui zoom deltas and
 * soft-keyboard text arrives through a custom [InputConnection].
 */
@SuppressLint("ViewConstructor")
class DeckCraftGlSurfaceView(
    context: Context,
    private val nativeBridge: DeckCraftNativeBridge,
    private val engineHandleProvider: () -> Long,
    private val stickyModifiersProvider: () -> StickyModifierState,
    private val onConsumeRightClickMode: () -> Unit,
    private val onEffects: (PlatformEffectsDto) -> Unit,
    private val enableGlThread: Boolean = true,
) : GLSurfaceView(context), Choreographer.FrameCallback {

    private val startTimeNanos = SystemClock.elapsedRealtimeNanos()
    private val lastRepaintAfterMs = AtomicLong(0L)
    private val nextDelayedRepaintAtUptimeMs = AtomicLong(0L)

    @Volatile
    private var surfaceWidthPx: Int = 1

    @Volatile
    private var surfaceHeightPx: Int = 1

    private var frameLoopActive = false
    private var lastCursorIcon: String = "default"

    private val translator = DesktopInputTranslator(
        bridge = nativeBridge,
        handleProvider = engineHandleProvider,
        stickyModifiersProvider = stickyModifiersProvider,
        onRequestWake = { requestRender() },
        onConsumeOneShotLatch = onConsumeRightClickMode,
    )

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val handle = engineHandleProvider()
                if (handle != 0L && factor.isFinite() && factor > 0.01f && factor != 1.0f) {
                    nativeBridge.pushZoom(handle, factor)
                    requestRender()
                }
                return true
            }
        },
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false

        if (enableGlThread) {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            preserveEGLContextOnPause = true
            setRenderer(WorkbenchRenderer())
            renderMode = RENDERMODE_WHEN_DIRTY
        }
    }

    override fun requestRender() {
        if (!enableGlThread) return
        try {
            super.requestRender()
        } catch (_: Throwable) {
            // Ignored if GL thread has stopped
        }
    }

    override fun queueEvent(r: Runnable?) {
        if (!enableGlThread || r == null) return
        try {
            super.queueEvent(r)
        } catch (_: Throwable) {
            // Ignored if GL thread has stopped
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startChoreographerLoop()
    }

    override fun onDetachedFromWindow() {
        stopChoreographerLoop()
        val h = engineHandleProvider()
        if (enableGlThread && h != 0L) {
            queueEvent { nativeBridge.onSurfaceDestroyed(h) }
        }
        super.onDetachedFromWindow()
    }

    override fun onResume() {
        if (enableGlThread) {
            super.onResume()
            startChoreographerLoop()
            requestRender()
        }
    }

    override fun onPause() {
        stopChoreographerLoop()
        if (enableGlThread) {
            super.onPause()
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!frameLoopActive) return
        val handle = engineHandleProvider()
        if (handle != 0L && enableGlThread) {
            val repaintMs = lastRepaintAfterMs.get()
            val dueDelayed = SystemClock.uptimeMillis() >= nextDelayedRepaintAtUptimeMs.get()
            val woken = nativeBridge.consumeWakeRequested(handle)
            if (repaintMs == 0L || dueDelayed || woken) {
                if (dueDelayed && repaintMs > 0L) {
                    nextDelayedRepaintAtUptimeMs.set(Long.MAX_VALUE)
                }
                requestRender()
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun startChoreographerLoop() {
        if (enableGlThread && !frameLoopActive) {
            frameLoopActive = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun stopChoreographerLoop() {
        if (frameLoopActive) {
            frameLoopActive = false
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (translator.onGenericMotionEvent(event, currentPixelsPerPoint())) return true
        return super.onGenericMotionEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasFocus()) requestFocus()
        val isFinger = event.pointerCount > 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        if (isFinger) scaleGestureDetector.onTouchEvent(event)
        if (translator.onTouchEvent(event, currentPixelsPerPoint())) return true
        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        if (translator.onKeyEvent(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event)
        if (translator.onKeyEvent(event)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        if (translator.onKeyEvent(event)) return true
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val str = text?.toString().orEmpty()
                val handle = engineHandleProvider()
                if (str.isNotEmpty() && handle != 0L) {
                    nativeBridge.pushText(handle, str)
                    requestRender()
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val handle = engineHandleProvider()
                if (handle == 0L) return true
                val mods = stickyModifiersProvider().toBitmask()
                repeat(beforeLength.coerceAtLeast(0)) {
                    nativeBridge.pushKey(handle, EguiKeys.BACKSPACE, true, false, mods)
                    nativeBridge.pushKey(handle, EguiKeys.BACKSPACE, false, false, mods)
                }
                repeat(afterLength.coerceAtLeast(0)) {
                    nativeBridge.pushKey(handle, EguiKeys.DELETE, true, false, mods)
                    nativeBridge.pushKey(handle, EguiKeys.DELETE, false, false, mods)
                }
                requestRender()
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                return translator.onKeyEvent(event) || super.sendKeyEvent(event)
            }
        }
    }

    private fun currentPixelsPerPoint(): Float {
        val d = resources.displayMetrics.density
        return if (d.isFinite() && d > 0.05f) d else 1.0f
    }

    private fun applyEffectsOnMainThread(effects: PlatformEffectsDto) {
        if (effects.cursorIcon != lastCursorIcon) {
            lastCursorIcon = effects.cursorIcon
            pointerIcon = PointerIcon.getSystemIcon(
                context,
                DesktopInputTranslator.mapCursorIconToPointerIconType(effects.cursorIcon),
            )
        }
        onEffects(effects)
    }

    private inner class WorkbenchRenderer : Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val handle = engineHandleProvider()
            if (handle != 0L) {
                try {
                    nativeBridge.onSurfaceCreated(handle)
                } catch (t: Throwable) {
                    CrashReporter(context.filesDir).recordJavaCrash(Thread.currentThread(), t)
                }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidthPx = width.coerceAtLeast(1)
            surfaceHeightPx = height.coerceAtLeast(1)
            val handle = engineHandleProvider()
            if (handle != 0L) {
                try {
                    nativeBridge.onSurfaceChanged(handle, surfaceWidthPx, surfaceHeightPx, currentPixelsPerPoint())
                } catch (t: Throwable) {
                    CrashReporter(context.filesDir).recordJavaCrash(Thread.currentThread(), t)
                }
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            val handle = engineHandleProvider()
            if (handle == 0L) return
            try {
                val elapsedSecs = (SystemClock.elapsedRealtimeNanos() - startTimeNanos) / 1_000_000_000.0
                val darkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
                val effects = nativeBridge.stepFrame(
                    handle = handle,
                    timeSecs = elapsedSecs,
                    widthPx = surfaceWidthPx,
                    heightPx = surfaceHeightPx,
                    pixelsPerPoint = currentPixelsPerPoint(),
                    darkMode = darkMode,
                    focused = hasWindowFocus(),
                )
                lastRepaintAfterMs.set(effects.repaintAfterMs)
                when {
                    effects.repaintAfterMs > 0L ->
                        nextDelayedRepaintAtUptimeMs.set(SystemClock.uptimeMillis() + effects.repaintAfterMs)
                    effects.repaintAfterMs < 0L -> nextDelayedRepaintAtUptimeMs.set(Long.MAX_VALUE)
                    else -> nextDelayedRepaintAtUptimeMs.set(0L)
                }
                post { applyEffectsOnMainThread(effects) }
            } catch (t: Throwable) {
                CrashReporter(context.filesDir).recordJavaCrash(Thread.currentThread(), t)
            }
        }
    }
}
