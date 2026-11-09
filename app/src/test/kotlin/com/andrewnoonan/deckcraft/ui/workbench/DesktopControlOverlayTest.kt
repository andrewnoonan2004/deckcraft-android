package com.andrewnoonan.deckcraft.ui.workbench

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andrewnoonan.deckcraft.input.StickyModifierState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1920dp-h1080dp")
class DesktopControlOverlayTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun desktopControlOverlay_expandedAndCollapsed_dispatchesModifiersAndCommands() {
        var ctrlClicked = false
        var rightClickToggled = false
        var collapsedClicked = false
        val firedCommands = mutableListOf<String>()

        composeTestRule.setContent {
            DesktopControlOverlay(
                state = WorkbenchUiState(
                    windowTitle = "showcase.pptx — DeckCraft",
                    isDesktopBarExpanded = true,
                    stickyModifiers = StickyModifierState(ctrl = true),
                ),
                onToggleExpanded = { collapsedClicked = true },
                onToggleCtrl = { ctrlClicked = true },
                onToggleShift = {},
                onToggleAlt = {},
                onToggleRightClick = { rightClickToggled = true },
                onCommand = { firedCommands += it },
                onToggleKeyboard = {},
                onShareDocument = {},
                onAdjustScale = {},
            )
        }

        composeTestRule.onNodeWithTag("btn_mod_ctrl").performClick()
        assertTrue(ctrlClicked)

        composeTestRule.onNodeWithTag("btn_mod_right_click").performClick()
        assertTrue(rightClickToggled)

        composeTestRule.onNodeWithTag("btn_cmd_palette").performClick()
        composeTestRule.onNodeWithTag("btn_cmd_open").performClick()
        composeTestRule.onNodeWithTag("btn_cmd_save").performClick()
        assertEquals(listOf("app.command_palette", "file.open", "file.save"), firedCommands)

        composeTestRule.onNodeWithTag("btn_toggle_dock").performClick()
        assertTrue(collapsedClicked)
    }

    @Test
    fun desktopControlOverlay_collapsedDock_showsExpandLabel() {
        composeTestRule.setContent {
            DesktopControlOverlay(
                state = WorkbenchUiState(
                    isDesktopBarExpanded = false,
                ),
                onToggleExpanded = {},
                onToggleCtrl = {},
                onToggleShift = {},
                onToggleAlt = {},
                onToggleRightClick = {},
                onCommand = {},
                onToggleKeyboard = {},
                onShareDocument = {},
                onAdjustScale = {},
            )
        }

        composeTestRule.onNodeWithText("▴ Desktop").assertIsDisplayed()
    }

    @Test
    fun desktopControlOverlay_keyboardAndScaleActions_invokeCallbacks() {
        var keyboardToggled = false
        var scaleDelta = 0

        composeTestRule.setContent {
            DesktopControlOverlay(
                state = WorkbenchUiState(
                    isDesktopBarExpanded = true,
                    uiScalePercent = 100,
                ),
                onToggleExpanded = {},
                onToggleCtrl = {},
                onToggleShift = {},
                onToggleAlt = {},
                onToggleRightClick = {},
                onCommand = {},
                onToggleKeyboard = { keyboardToggled = true },
                onShareDocument = {},
                onAdjustScale = { scaleDelta += it },
            )
        }

        composeTestRule.onNodeWithTag("btn_keyboard").performClick()
        assertTrue(keyboardToggled)

        composeTestRule.onNodeWithTag("btn_scale_up").performClick()
        assertEquals(10, scaleDelta)
    }
}
