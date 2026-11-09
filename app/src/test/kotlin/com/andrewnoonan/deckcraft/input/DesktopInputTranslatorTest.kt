package com.andrewnoonan.deckcraft.input

import android.app.Application
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andrewnoonan.deckcraft.testing.FakeDeckCraftNativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DesktopInputTranslatorTest {

    private val bridge = FakeDeckCraftNativeBridge()
    private var sticky = StickyModifierState()
    private var latchConsumed = 0
    private val translator = DesktopInputTranslator(
        bridge = bridge,
        handleProvider = { 7L },
        stickyModifiersProvider = { sticky },
        onConsumeOneShotLatch = { latchConsumed++ },
    )

    private fun mouseEvent(action: Int, x: Float, y: Float, buttonState: Int = 0, meta: Int = 0): MotionEvent {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y }
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(
            now, now, action, 1, arrayOf(props), arrayOf(coords),
            meta, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
    }

    @Test
    fun hoverMove_scalesToLogicalPoints() {
        assertTrue(translator.onGenericMotionEvent(mouseEvent(MotionEvent.ACTION_HOVER_MOVE, 400f, 300f), 2f))
        assertEquals(listOf("move(200.0,150.0)"), bridge.calls)
    }

    @Test
    fun hardwareRightClick_emitsSecondaryButtonPressAndRelease() {
        translator.onTouchEvent(mouseEvent(MotionEvent.ACTION_DOWN, 100f, 100f, MotionEvent.BUTTON_SECONDARY), 1f)
        translator.onTouchEvent(mouseEvent(MotionEvent.ACTION_UP, 100f, 100f), 1f)
        assertTrue(bridge.calls.contains("button(${EguiPointerButton.SECONDARY},true,mods=0)"))
        assertTrue(bridge.calls.contains("button(${EguiPointerButton.SECONDARY},false,mods=0)"))
    }

    @Test
    fun stickyRightClickLatchAndCtrl_produceSecondaryWithCommandModifiersAndConsumeLatch() {
        sticky = StickyModifierState(ctrl = true, rightClickLatch = true)
        translator.onTouchEvent(mouseEvent(MotionEvent.ACTION_DOWN, 10f, 10f, MotionEvent.BUTTON_PRIMARY), 1f)
        val expectedMods = EguiModifiers.CTRL or EguiModifiers.COMMAND
        assertTrue(bridge.calls.contains("button(${EguiPointerButton.SECONDARY},true,mods=$expectedMods)"))
        assertEquals(1, latchConsumed)
    }

    @Test
    fun ctrlK_emitsKeyWithoutTextAndPrintableShiftA_emitsText() {
        val now = SystemClock.uptimeMillis()
        translator.onKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_K, 0, KeyEvent.META_CTRL_ON))
        val cmd = EguiModifiers.CTRL or EguiModifiers.COMMAND
        assertEquals(listOf("key(${EguiKeys.K},true,mods=$cmd)"), bridge.calls)

        bridge.calls.clear()
        translator.onKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_SHIFT_ON))
        assertEquals("key(${EguiKeys.A},true,mods=${EguiModifiers.SHIFT})", bridge.calls[0])
        assertEquals("text(A)", bridge.calls[1])
    }

    @Test
    fun f5_mapsToEguiF5ForSlideShowShortcut() {
        assertEquals(EguiKeys.F5, DesktopInputTranslator.mapAndroidKeyCodeToEguiKey(KeyEvent.KEYCODE_F5))
    }

    @Test
    fun cursorIcons_mapToAndroidPointerIcons() {
        assertEquals(PointerIcon.TYPE_HAND, DesktopInputTranslator.mapCursorIconToPointerIconType("pointing_hand"))
        assertEquals(PointerIcon.TYPE_TEXT, DesktopInputTranslator.mapCursorIconToPointerIconType("text"))
        assertEquals(PointerIcon.TYPE_GRABBING, DesktopInputTranslator.mapCursorIconToPointerIconType("grabbing"))
        assertEquals(
            PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW,
            DesktopInputTranslator.mapCursorIconToPointerIconType("resize_horizontal"),
        )
    }
}
