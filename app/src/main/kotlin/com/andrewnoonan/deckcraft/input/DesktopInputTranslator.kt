package com.andrewnoonan.deckcraft.input

import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import com.andrewnoonan.deckcraft.bridge.DeckCraftNativeBridge
import kotlin.math.hypot

object EguiModifiers {
    const val ALT: Int = 1
    const val CTRL: Int = 2
    const val SHIFT: Int = 4
    const val MAC_CMD: Int = 8
    const val COMMAND: Int = 16
}

object EguiPointerButton {
    const val PRIMARY: Int = 0
    const val SECONDARY: Int = 1
    const val MIDDLE: Int = 2
    const val EXTRA1: Int = 3
    const val EXTRA2: Int = 4
}

object EguiTouchPhase {
    const val START: Int = 0
    const val MOVE: Int = 1
    const val END: Int = 2
    const val CANCEL: Int = 3
}

object EguiKeys {
    const val ARROW_DOWN = 1
    const val ARROW_LEFT = 2
    const val ARROW_RIGHT = 3
    const val ARROW_UP = 4
    const val ESCAPE = 5
    const val TAB = 6
    const val BACKSPACE = 7
    const val ENTER = 8
    const val SPACE = 9
    const val INSERT = 10
    const val DELETE = 11
    const val HOME = 12
    const val END = 13
    const val PAGE_UP = 14
    const val PAGE_DOWN = 15
    const val COPY = 16
    const val CUT = 17
    const val PASTE = 18
    const val MINUS = 19
    const val PLUS = 20
    const val EQUALS = 21
    const val OPEN_BRACKET = 22
    const val CLOSE_BRACKET = 23
    const val BACKSLASH = 24
    const val SEMICOLON = 25
    const val QUOTE = 26
    const val COMMA = 27
    const val PERIOD = 28
    const val SLASH = 29
    const val BACKTICK = 30
    const val NUM0 = 31
    const val NUM1 = 32
    const val NUM2 = 33
    const val NUM3 = 34
    const val NUM4 = 35
    const val NUM5 = 36
    const val NUM6 = 37
    const val NUM7 = 38
    const val NUM8 = 39
    const val NUM9 = 40
    const val A = 41
    const val B = 42
    const val C = 43
    const val D = 44
    const val E = 45
    const val F = 46
    const val G = 47
    const val H = 48
    const val I = 49
    const val J = 50
    const val K = 51
    const val L = 52
    const val M = 53
    const val N = 54
    const val O = 55
    const val P = 56
    const val Q = 57
    const val R = 58
    const val S = 59
    const val T = 60
    const val U = 61
    const val V = 62
    const val W = 63
    const val X = 64
    const val Y = 65
    const val Z = 66
    const val F1 = 67
    const val F2 = 68
    const val F3 = 69
    const val F4 = 70
    const val F5 = 71
    const val F6 = 72
    const val F7 = 73
    const val F8 = 74
    const val F9 = 75
    const val F10 = 76
    const val F11 = 77
    const val F12 = 78
}

data class StickyModifierState(
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val rightClickLatch: Boolean = false,
) {
    fun toBitmask(): Int {
        var mask = 0
        if (alt) mask = mask or EguiModifiers.ALT
        if (ctrl) mask = mask or EguiModifiers.CTRL or EguiModifiers.COMMAND
        if (shift) mask = mask or EguiModifiers.SHIFT
        return mask
    }
}

class DesktopInputTranslator(
    private val bridge: DeckCraftNativeBridge,
    private val handleProvider: () -> Long,
    private val stickyModifiersProvider: () -> StickyModifierState = { StickyModifierState() },
    private val onRequestWake: () -> Unit = {},
    private val onConsumeOneShotLatch: () -> Unit = {},
) {
    private var activeMouseButtonsMask: Int = 0
    private var previousTouchButtonsMask: Int = 0
    private var twoFingerCandidateActive: Boolean = false
    private var twoFingerDownTimeMs: Long = 0L
    private var twoFingerAnchorX: Float = 0f
    private var twoFingerAnchorY: Float = 0f
    private var twoFingerMovedTooFar: Boolean = false

    fun onGenericMotionEvent(
        event: MotionEvent,
        pixelsPerPoint: Float,
    ): Boolean {
        val handle = handleProvider()
        if (handle <= 0L) return false
        val safePpp = pixelsPerPoint.coerceAtLeast(0.1f)
        val xPt = event.x / safePpp
        val yPt = event.y / safePpp
        val mods = combineModifiers(event.metaState)

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            -> {
                bridge.pushPointerMoved(handle, xPt, yPt)
                onRequestWake()
                return true
            }

            MotionEvent.ACTION_HOVER_EXIT -> {
                if (activeMouseButtonsMask == 0) {
                    bridge.pushPointerGone(handle)
                    onRequestWake()
                }
                return true
            }

            MotionEvent.ACTION_SCROLL -> {
                val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (vScroll != 0f || hScroll != 0f) {
                    bridge.pushPointerMoved(handle, xPt, yPt)
                    // Android AXIS_VSCROLL is positive when scrolling up; axis HScroll is positive right
                    val deltaX = -hScroll * 36f
                    val deltaY = vScroll * 36f
                    bridge.pushMouseWheel(
                        handle = handle,
                        deltaX = deltaX,
                        deltaY = deltaY,
                        isLineUnit = false,
                        modifiers = mods,
                    )
                    onRequestWake()
                    return true
                }
            }

            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE,
            -> {
                val actionBtn = event.actionButton
                val eguiBtn = mapAndroidButtonToEgui(actionBtn)
                if (eguiBtn != null) {
                    val pressed = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
                    activeMouseButtonsMask = event.buttonState
                    bridge.pushPointerMoved(handle, xPt, yPt)
                    bridge.pushPointerButton(
                        handle = handle,
                        xPt = xPt,
                        yPt = yPt,
                        button = eguiBtn,
                        pressed = pressed,
                        modifiers = mods,
                    )
                    onRequestWake()
                    return true
                }
            }
        }
        return false
    }

    fun onTouchEvent(
        event: MotionEvent,
        pixelsPerPoint: Float,
    ): Boolean {
        val handle = handleProvider()
        if (handle <= 0L) return false
        val safePpp = pixelsPerPoint.coerceAtLeast(0.1f)
        val mods = combineModifiers(event.metaState)
        val sticky = stickyModifiersProvider()

        val isMouseOrStylusButton = isMouseSource(event) ||
            (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS &&
                (event.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0)

        if (isMouseOrStylusButton) {
            return handleMouseOrStylusTouch(handle, event, safePpp, mods, sticky)
        }

        return handleFingerTouch(handle, event, safePpp, mods, sticky)
    }

    private fun handleMouseOrStylusTouch(
        handle: Long,
        event: MotionEvent,
        safePpp: Float,
        mods: Int,
        sticky: StickyModifierState,
    ): Boolean {
        val xPt = event.x / safePpp
        val yPt = event.y / safePpp

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val btnState = event.buttonState
                val eguiButton = when {
                    sticky.rightClickLatch -> {
                        onConsumeOneShotLatch()
                        EguiPointerButton.SECONDARY
                    }
                    (btnState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0 ->
                        EguiPointerButton.SECONDARY
                    (btnState and MotionEvent.BUTTON_TERTIARY) != 0 ->
                        EguiPointerButton.MIDDLE
                    (btnState and MotionEvent.BUTTON_BACK) != 0 ->
                        EguiPointerButton.EXTRA1
                    (btnState and MotionEvent.BUTTON_FORWARD) != 0 ->
                        EguiPointerButton.EXTRA2
                    else -> EguiPointerButton.PRIMARY
                }
                previousTouchButtonsMask = 1 shl eguiButton
                bridge.pushPointerMoved(handle, xPt, yPt)
                bridge.pushPointerButton(
                    handle = handle,
                    xPt = xPt,
                    yPt = yPt,
                    button = eguiButton,
                    pressed = true,
                    modifiers = mods,
                )
                onRequestWake()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                bridge.pushPointerMoved(handle, xPt, yPt)
                onRequestWake()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                bridge.pushPointerMoved(handle, xPt, yPt)
                for (btn in EguiPointerButton.PRIMARY..EguiPointerButton.EXTRA2) {
                    if ((previousTouchButtonsMask and (1 shl btn)) != 0) {
                        bridge.pushPointerButton(
                            handle = handle,
                            xPt = xPt,
                            yPt = yPt,
                            button = btn,
                            pressed = false,
                            modifiers = mods,
                        )
                    }
                }
                previousTouchButtonsMask = 0
                onRequestWake()
                return true
            }
        }
        return false
    }

    private fun handleFingerTouch(
        handle: Long,
        event: MotionEvent,
        safePpp: Float,
        mods: Int,
        sticky: StickyModifierState,
    ): Boolean {
        val x0Pt = event.x / safePpp
        val y0Pt = event.y / safePpp

        // Track two-finger tap for desktop right-click context menu on touchscreens
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                twoFingerCandidateActive = false
                twoFingerMovedTooFar = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2 && !twoFingerMovedTooFar) {
                    twoFingerCandidateActive = true
                    twoFingerDownTimeMs = event.eventTime
                    twoFingerAnchorX = (event.getX(0) + event.getX(1)) * 0.5f / safePpp
                    twoFingerAnchorY = (event.getY(0) + event.getY(1)) * 0.5f / safePpp
                } else if (event.pointerCount > 2) {
                    twoFingerCandidateActive = false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (twoFingerCandidateActive && event.pointerCount == 2) {
                    val cx = (event.getX(0) + event.getX(1)) * 0.5f / safePpp
                    val cy = (event.getY(0) + event.getY(1)) * 0.5f / safePpp
                    if (hypot(cx - twoFingerAnchorX, cy - twoFingerAnchorY) > 18f) {
                        twoFingerMovedTooFar = true
                        twoFingerCandidateActive = false
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (twoFingerCandidateActive && !twoFingerMovedTooFar &&
                    (event.eventTime - twoFingerDownTimeMs) <= 320L
                ) {
                    twoFingerCandidateActive = false
                    // Cancel primary drag and synthesize secondary right-click at midpoint
                    bridge.pushPointerButton(
                        handle = handle,
                        xPt = twoFingerAnchorX,
                        yPt = twoFingerAnchorY,
                        button = EguiPointerButton.PRIMARY,
                        pressed = false,
                        modifiers = mods,
                    )
                    bridge.pushPointerMoved(handle, twoFingerAnchorX, twoFingerAnchorY)
                    bridge.pushPointerButton(
                        handle = handle,
                        xPt = twoFingerAnchorX,
                        yPt = twoFingerAnchorY,
                        button = EguiPointerButton.SECONDARY,
                        pressed = true,
                        modifiers = mods,
                    )
                    bridge.pushPointerButton(
                        handle = handle,
                        xPt = twoFingerAnchorX,
                        yPt = twoFingerAnchorY,
                        button = EguiPointerButton.SECONDARY,
                        pressed = false,
                        modifiers = mods,
                    )
                    onRequestWake()
                    return true
                }
                twoFingerCandidateActive = false
            }
        }

        // Forward raw multi-touch phases to egui for pinch-zoom & touch awareness
        forwardRawTouches(handle, event, safePpp)

        // Single-finger pointer interaction (or right-click latch from floating dock)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val button = if (sticky.rightClickLatch) {
                    onConsumeOneShotLatch()
                    EguiPointerButton.SECONDARY
                } else {
                    EguiPointerButton.PRIMARY
                }
                previousTouchButtonsMask = 1 shl button
                bridge.pushPointerMoved(handle, x0Pt, y0Pt)
                bridge.pushPointerButton(
                    handle = handle,
                    xPt = x0Pt,
                    yPt = y0Pt,
                    button = button,
                    pressed = true,
                    modifiers = mods,
                )
                onRequestWake()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    bridge.pushPointerMoved(handle, x0Pt, y0Pt)
                    onRequestWake()
                }
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                bridge.pushPointerMoved(handle, x0Pt, y0Pt)
                for (btn in EguiPointerButton.PRIMARY..EguiPointerButton.SECONDARY) {
                    if ((previousTouchButtonsMask and (1 shl btn)) != 0) {
                        bridge.pushPointerButton(
                            handle = handle,
                            xPt = x0Pt,
                            yPt = y0Pt,
                            button = btn,
                            pressed = false,
                            modifiers = mods,
                        )
                    }
                }
                previousTouchButtonsMask = 0
                bridge.pushPointerGone(handle)
                onRequestWake()
                return true
            }
        }
        return true
    }

    private fun forwardRawTouches(
        handle: Long,
        event: MotionEvent,
        safePpp: Float,
    ) {
        val deviceId = event.deviceId
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            -> {
                val idx = event.actionIndex
                bridge.pushTouch(
                    handle = handle,
                    deviceId = deviceId,
                    pointerId = event.getPointerId(idx),
                    phase = EguiTouchPhase.START,
                    xPt = event.getX(idx) / safePpp,
                    yPt = event.getY(idx) / safePpp,
                    force = event.getPressure(idx),
                )
            }

            MotionEvent.ACTION_MOVE -> {
                for (idx in 0 until event.pointerCount) {
                    bridge.pushTouch(
                        handle = handle,
                        deviceId = deviceId,
                        pointerId = event.getPointerId(idx),
                        phase = EguiTouchPhase.MOVE,
                        xPt = event.getX(idx) / safePpp,
                        yPt = event.getY(idx) / safePpp,
                        force = event.getPressure(idx),
                    )
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                val idx = event.actionIndex
                bridge.pushTouch(
                    handle = handle,
                    deviceId = deviceId,
                    pointerId = event.getPointerId(idx),
                    phase = EguiTouchPhase.END,
                    xPt = event.getX(idx) / safePpp,
                    yPt = event.getY(idx) / safePpp,
                    force = event.getPressure(idx),
                )
            }

            MotionEvent.ACTION_CANCEL -> {
                for (idx in 0 until event.pointerCount) {
                    bridge.pushTouch(
                        handle = handle,
                        deviceId = deviceId,
                        pointerId = event.getPointerId(idx),
                        phase = EguiTouchPhase.CANCEL,
                        xPt = event.getX(idx) / safePpp,
                        yPt = event.getY(idx) / safePpp,
                        force = event.getPressure(idx),
                    )
                }
            }
        }
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        val handle = handleProvider()
        if (handle <= 0L) return false

        if (event.action == KeyEvent.ACTION_MULTIPLE && event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            val chars = event.characters
            if (!chars.isNullOrEmpty()) {
                bridge.pushText(handle, chars)
                onRequestWake()
                return true
            }
            return false
        }

        val pressed = event.action == KeyEvent.ACTION_DOWN
        if (!pressed && event.action != KeyEvent.ACTION_UP) {
            return false
        }

        // Let system volume and power keys pass through to Android OS
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE ||
            event.keyCode == KeyEvent.KEYCODE_POWER
        ) {
            return false
        }

        val mods = combineModifiers(event.metaState)
        val eguiKey = mapAndroidKeyCodeToEguiKey(event.keyCode)
        val repeat = event.repeatCount > 0

        if (eguiKey != null) {
            bridge.pushKey(
                handle = handle,
                eguiKeyCode = eguiKey,
                pressed = pressed,
                repeat = repeat,
                modifiers = mods,
            )
        }

        // Commit printable Unicode character on key down when Ctrl/Alt/Meta are not held
        val hasCommandOrAlt = (mods and (EguiModifiers.CTRL or EguiModifiers.COMMAND or EguiModifiers.ALT)) != 0
        if (pressed && !hasCommandOrAlt && !isControlCharacterKey(event.keyCode)) {
            val unicodeChar = event.getUnicodeChar(event.metaState)
            if (unicodeChar >= 0x20 && unicodeChar != 0x7F) {
                val text = String(Character.toChars(unicodeChar))
                bridge.pushText(handle, text)
            }
        }

        if (eguiKey != null || (pressed && !hasCommandOrAlt && event.unicodeChar >= 0x20)) {
            onRequestWake()
            return true
        }

        // Pure modifier key press/release still updates modifiers on next event
        if (isModifierKeyCode(event.keyCode)) {
            onRequestWake()
            return true
        }

        return false
    }

    private fun combineModifiers(metaState: Int): Int {
        var mask = stickyModifiersProvider().toBitmask()
        if ((metaState and KeyEvent.META_ALT_ON) != 0) {
            mask = mask or EguiModifiers.ALT
        }
        if ((metaState and KeyEvent.META_CTRL_ON) != 0) {
            mask = mask or EguiModifiers.CTRL or EguiModifiers.COMMAND
        }
        if ((metaState and KeyEvent.META_META_ON) != 0) {
            mask = mask or EguiModifiers.CTRL or EguiModifiers.COMMAND
        }
        if ((metaState and KeyEvent.META_SHIFT_ON) != 0) {
            mask = mask or EguiModifiers.SHIFT
        }
        return mask
    }

    private fun isMouseSource(event: MotionEvent): Boolean {
        val src = event.source
        if ((src and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (src and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
        ) {
            return true
        }
        if ((src and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) return true
        if (event.pointerCount > 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return true
        return false
    }

    companion object {
        fun mapAndroidButtonToEgui(actionButton: Int): Int? = when (actionButton) {
            MotionEvent.BUTTON_PRIMARY -> EguiPointerButton.PRIMARY
            MotionEvent.BUTTON_SECONDARY,
            MotionEvent.BUTTON_STYLUS_PRIMARY,
            -> EguiPointerButton.SECONDARY
            MotionEvent.BUTTON_TERTIARY -> EguiPointerButton.MIDDLE
            MotionEvent.BUTTON_BACK -> EguiPointerButton.EXTRA1
            MotionEvent.BUTTON_FORWARD -> EguiPointerButton.EXTRA2
            else -> null
        }

        fun mapAndroidKeyCodeToEguiKey(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> EguiKeys.ARROW_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> EguiKeys.ARROW_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> EguiKeys.ARROW_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> EguiKeys.ARROW_UP
            KeyEvent.KEYCODE_ESCAPE -> EguiKeys.ESCAPE
            KeyEvent.KEYCODE_TAB -> EguiKeys.TAB
            KeyEvent.KEYCODE_DEL -> EguiKeys.BACKSPACE
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> EguiKeys.ENTER
            KeyEvent.KEYCODE_SPACE -> EguiKeys.SPACE
            KeyEvent.KEYCODE_INSERT -> EguiKeys.INSERT
            KeyEvent.KEYCODE_FORWARD_DEL -> EguiKeys.DELETE
            KeyEvent.KEYCODE_MOVE_HOME -> EguiKeys.HOME
            KeyEvent.KEYCODE_MOVE_END -> EguiKeys.END
            KeyEvent.KEYCODE_PAGE_UP -> EguiKeys.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> EguiKeys.PAGE_DOWN
            KeyEvent.KEYCODE_COPY -> EguiKeys.COPY
            KeyEvent.KEYCODE_CUT -> EguiKeys.CUT
            KeyEvent.KEYCODE_PASTE -> EguiKeys.PASTE
            KeyEvent.KEYCODE_MINUS,
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT,
            -> EguiKeys.MINUS
            KeyEvent.KEYCODE_PLUS,
            KeyEvent.KEYCODE_NUMPAD_ADD,
            -> EguiKeys.PLUS
            KeyEvent.KEYCODE_EQUALS -> EguiKeys.EQUALS
            KeyEvent.KEYCODE_LEFT_BRACKET -> EguiKeys.OPEN_BRACKET
            KeyEvent.KEYCODE_RIGHT_BRACKET -> EguiKeys.CLOSE_BRACKET
            KeyEvent.KEYCODE_BACKSLASH -> EguiKeys.BACKSLASH
            KeyEvent.KEYCODE_SEMICOLON -> EguiKeys.SEMICOLON
            KeyEvent.KEYCODE_APOSTROPHE -> EguiKeys.QUOTE
            KeyEvent.KEYCODE_COMMA -> EguiKeys.COMMA
            KeyEvent.KEYCODE_PERIOD,
            KeyEvent.KEYCODE_NUMPAD_DOT,
            -> EguiKeys.PERIOD
            KeyEvent.KEYCODE_SLASH,
            KeyEvent.KEYCODE_NUMPAD_DIVIDE,
            -> EguiKeys.SLASH
            KeyEvent.KEYCODE_GRAVE -> EguiKeys.BACKTICK
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> EguiKeys.NUM0
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> EguiKeys.NUM1
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> EguiKeys.NUM2
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> EguiKeys.NUM3
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> EguiKeys.NUM4
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> EguiKeys.NUM5
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> EguiKeys.NUM6
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> EguiKeys.NUM7
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> EguiKeys.NUM8
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> EguiKeys.NUM9
            KeyEvent.KEYCODE_A -> EguiKeys.A
            KeyEvent.KEYCODE_B -> EguiKeys.B
            KeyEvent.KEYCODE_C -> EguiKeys.C
            KeyEvent.KEYCODE_D -> EguiKeys.D
            KeyEvent.KEYCODE_E -> EguiKeys.E
            KeyEvent.KEYCODE_F -> EguiKeys.F
            KeyEvent.KEYCODE_G -> EguiKeys.G
            KeyEvent.KEYCODE_H -> EguiKeys.H
            KeyEvent.KEYCODE_I -> EguiKeys.I
            KeyEvent.KEYCODE_J -> EguiKeys.J
            KeyEvent.KEYCODE_K -> EguiKeys.K
            KeyEvent.KEYCODE_L -> EguiKeys.L
            KeyEvent.KEYCODE_M -> EguiKeys.M
            KeyEvent.KEYCODE_N -> EguiKeys.N
            KeyEvent.KEYCODE_O -> EguiKeys.O
            KeyEvent.KEYCODE_P -> EguiKeys.P
            KeyEvent.KEYCODE_Q -> EguiKeys.Q
            KeyEvent.KEYCODE_R -> EguiKeys.R
            KeyEvent.KEYCODE_S -> EguiKeys.S
            KeyEvent.KEYCODE_T -> EguiKeys.T
            KeyEvent.KEYCODE_U -> EguiKeys.U
            KeyEvent.KEYCODE_V -> EguiKeys.V
            KeyEvent.KEYCODE_W -> EguiKeys.W
            KeyEvent.KEYCODE_X -> EguiKeys.X
            KeyEvent.KEYCODE_Y -> EguiKeys.Y
            KeyEvent.KEYCODE_Z -> EguiKeys.Z
            KeyEvent.KEYCODE_F1 -> EguiKeys.F1
            KeyEvent.KEYCODE_F2 -> EguiKeys.F2
            KeyEvent.KEYCODE_F3 -> EguiKeys.F3
            KeyEvent.KEYCODE_F4 -> EguiKeys.F4
            KeyEvent.KEYCODE_F5 -> EguiKeys.F5
            KeyEvent.KEYCODE_F6 -> EguiKeys.F6
            KeyEvent.KEYCODE_F7 -> EguiKeys.F7
            KeyEvent.KEYCODE_F8 -> EguiKeys.F8
            KeyEvent.KEYCODE_F9 -> EguiKeys.F9
            KeyEvent.KEYCODE_F10 -> EguiKeys.F10
            KeyEvent.KEYCODE_F11 -> EguiKeys.F11
            KeyEvent.KEYCODE_F12 -> EguiKeys.F12
            else -> null
        }

        private fun isControlCharacterKey(keyCode: Int): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_ESCAPE,
            -> true
            else -> false
        }

        private fun isModifierKeyCode(keyCode: Int): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.KEYCODE_META_RIGHT,
            -> true
            else -> false
        }

        fun mapCursorIconToPointerIconType(cursorIcon: String): Int = when (cursorIcon) {
            "none" -> PointerIcon.TYPE_NULL
            "context_menu" -> PointerIcon.TYPE_CONTEXT_MENU
            "help" -> PointerIcon.TYPE_HELP
            "pointing_hand" -> PointerIcon.TYPE_HAND
            "progress", "wait" -> PointerIcon.TYPE_WAIT
            "cell" -> PointerIcon.TYPE_CELL
            "crosshair" -> PointerIcon.TYPE_CROSSHAIR
            "text" -> PointerIcon.TYPE_TEXT
            "vertical_text" -> PointerIcon.TYPE_VERTICAL_TEXT
            "alias" -> PointerIcon.TYPE_ALIAS
            "copy" -> PointerIcon.TYPE_COPY
            "move", "all_scroll" -> PointerIcon.TYPE_ALL_SCROLL
            "no_drop", "not_allowed" -> PointerIcon.TYPE_NO_DROP
            "grab" -> PointerIcon.TYPE_GRAB
            "grabbing" -> PointerIcon.TYPE_GRABBING
            "resize_horizontal",
            "resize_east",
            "resize_west",
            "resize_column",
            -> PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
            "resize_vertical",
            "resize_north",
            "resize_south",
            "resize_row",
            -> PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
            "resize_ne_sw",
            "resize_north_east",
            "resize_south_west",
            -> PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW
            "resize_nw_se",
            "resize_north_west",
            "resize_south_east",
            -> PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW
            "zoom_in" -> PointerIcon.TYPE_ZOOM_IN
            "zoom_out" -> PointerIcon.TYPE_ZOOM_OUT
            else -> PointerIcon.TYPE_ARROW
        }
    }
}
