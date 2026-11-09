package com.andrewnoonan.deckcraft.ui.workbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Material 3 Expressive floating desktop control overlay for DeckCraft.
 *
 * Provides sticky modifier chips (Ctrl, Shift, Alt, Right-Click), presentation shortcuts
 * (Palette, Open, Save, Save As, New Slide, Present, Undo, Redo, Find, Print),
 * zoom/scale controls, and bridge triggers with expressive tonal styling.
 */
@Composable
fun DesktopControlOverlay(
    state: WorkbenchUiState,
    onToggleExpanded: () -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleShift: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleRightClick: () -> Unit,
    onCommand: (String) -> Unit,
    onToggleKeyboard: () -> Unit,
    onShareDocument: () -> Unit,
    onAdjustScale: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isDesktopBarVisible) {
        return
    }

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    Surface(
        modifier = modifier
            .offset { IntOffset(dragOffsetX.roundToInt(), dragOffsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    dragOffsetX += dragAmount.x
                    dragOffsetY += dragAmount.y
                }
            },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        shadowElevation = 6.dp,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DockPillButton(
                label = if (state.isDesktopBarExpanded) "▾ Dock" else "▴ Desktop",
                active = !state.isDesktopBarExpanded && state.stickyModifiers.let { it.ctrl || it.shift || it.alt || it.rightClickLatch },
                tag = "btn_toggle_dock",
                onClick = onToggleExpanded,
            )

            if (state.isDesktopBarExpanded) {
                DockDivider()

                // Sticky Desktop Modifiers (Expressive Primary Accent)
                DockPillButton(
                    label = "Ctrl",
                    active = state.stickyModifiers.ctrl,
                    tag = "btn_mod_ctrl",
                    onClick = onToggleCtrl,
                )
                DockPillButton(
                    label = "Shift",
                    active = state.stickyModifiers.shift,
                    tag = "btn_mod_shift",
                    onClick = onToggleShift,
                )
                DockPillButton(
                    label = "Alt",
                    active = state.stickyModifiers.alt,
                    tag = "btn_mod_alt",
                    onClick = onToggleAlt,
                )
                DockPillButton(
                    label = "Right-Click",
                    active = state.stickyModifiers.rightClickLatch,
                    tag = "btn_mod_right_click",
                    onClick = onToggleRightClick,
                )

                DockDivider()

                // Core Presentation Commands
                DockPillButton(
                    label = "Ctrl+K",
                    active = false,
                    tag = "btn_cmd_palette",
                    onClick = { onCommand("app.command_palette") },
                )
                DockPillButton(
                    label = "Open",
                    active = false,
                    tag = "btn_cmd_open",
                    onClick = { onCommand("file.open") },
                )
                DockPillButton(
                    label = if (state.hasUnsavedChanges) "Save •" else "Save",
                    active = state.hasUnsavedChanges,
                    accentColor = if (state.hasUnsavedChanges) MaterialTheme.colorScheme.tertiaryContainer else null,
                    tag = "btn_cmd_save",
                    onClick = { onCommand("file.save") },
                )
                DockPillButton(
                    label = "Save As",
                    active = false,
                    tag = "btn_cmd_save_as",
                    onClick = { onCommand("file.save_as") },
                )
                DockPillButton(
                    label = "+ Slide",
                    active = false,
                    tag = "btn_cmd_new_slide",
                    onClick = { onCommand("slide.new") },
                )
                DockPillButton(
                    label = "Present",
                    active = false,
                    tag = "btn_cmd_present",
                    onClick = { onCommand("show.start") },
                )
                DockPillButton(
                    label = "Undo",
                    active = false,
                    tag = "btn_cmd_undo",
                    onClick = { onCommand("edit.undo") },
                )
                DockPillButton(
                    label = "Redo",
                    active = false,
                    tag = "btn_cmd_redo",
                    onClick = { onCommand("edit.redo") },
                )
                DockPillButton(
                    label = "Find",
                    active = false,
                    tag = "btn_cmd_find",
                    onClick = { onCommand("edit.find") },
                )
                DockPillButton(
                    label = "Print",
                    active = false,
                    tag = "btn_cmd_print",
                    onClick = { onCommand("app.print") },
                )

                DockDivider()

                // Android Host Bridges & UI Scale
                if (state.activeDocumentPath != null) {
                    DockPillButton(
                        label = "Share",
                        active = false,
                        tag = "btn_share",
                        onClick = onShareDocument,
                    )
                }
                DockPillButton(
                    label = "Keyboard",
                    active = state.wantsSoftKeyboard,
                    tag = "btn_keyboard",
                    onClick = onToggleKeyboard,
                )
                DockPillButton(
                    label = "−",
                    active = false,
                    tag = "btn_scale_down",
                    onClick = { onAdjustScale(-10) },
                )
                Text(
                    text = "${state.uiScalePercent}%",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                DockPillButton(
                    label = "+",
                    active = false,
                    tag = "btn_scale_up",
                    onClick = { onAdjustScale(10) },
                )
            }
        }
    }
}

@Composable
private fun DockPillButton(
    label: String,
    active: Boolean,
    tag: String,
    accentColor: Color? = null,
    onClick: () -> Unit,
) {
    val bg = when {
        accentColor != null -> accentColor
        active -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
    }
    val contentColor = when {
        accentColor != null -> MaterialTheme.colorScheme.onTertiaryContainer
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val borderColor = when {
        accentColor != null -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
        active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    }

    Box(
        modifier = Modifier
            .testTag(tag)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = if (active || accentColor != null) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun DockDivider() {
    Spacer(
        modifier = Modifier
            .width(1.dp)
            .height(18.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}
