package com.andrewnoonan.deckcraft.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformEffectsParserTest {

    @Test
    fun parse_validCompleteEffects_deserializesAllFields() {
        val raw = """
            {
              "cursorIcon": "pointing_hand",
              "wantsIme": true,
              "imeRect": {"x": 120.5, "y": 48.0, "width": 200.0, "height": 28.0},
              "repaintAfterMs": 16,
              "copyText": "Quarterly Review",
              "openUrl": "https://getartcraft.com/apps/deckcraft",
              "windowTitle": "pitch.pptx • — DeckCraft",
              "activePath": "/data/user/0/com.andrewnoonan.deckcraft/files/saf_workspace/doc_1/pitch.pptx",
              "hasUnsavedChanges": true,
              "closeRequested": false,
              "pickerRequest": {
                "id": 42, "mode": "save_file", "title": "Save As",
                "suggestedName": "pitch_v2.pptx", "extensions": ["pptx", "deckcraft"]
              },
              "savedFile": {"path": "/tmp/pitch.pptx"},
              "printJob": {"jobName": "pitch.pptx", "pdfPath": "/tmp/print.pdf"},
              "fullscreen": true,
              "inSlideshow": true,
              "someFutureField": 123
            }
        """.trimIndent()

        val effects = PlatformEffectsParser.parse(raw)
        assertEquals("pointing_hand", effects.cursorIcon)
        assertTrue(effects.wantsIme)
        assertEquals(120.5f, effects.imeRect!!.x, 0.01f)
        assertEquals(16L, effects.repaintAfterMs)
        assertEquals("Quarterly Review", effects.copyText)
        assertEquals("pitch.pptx • — DeckCraft", effects.windowTitle)
        assertTrue(effects.hasUnsavedChanges)
        assertFalse(effects.closeRequested)
        assertTrue(effects.fullscreen)
        assertTrue(effects.inSlideshow)
        val picker = effects.pickerRequest
        assertNotNull(picker)
        assertEquals(42L, picker!!.id)
        assertEquals("save_file", picker.mode)
        assertEquals(listOf("pptx", "deckcraft"), picker.extensions)
        assertEquals("/tmp/pitch.pptx", effects.savedFile!!.path)
        assertEquals("/tmp/print.pdf", effects.printJob!!.pdfPath)
    }

    @Test
    fun parse_malformedOrEmptyJson_returnsSafeDefaults() {
        val empty = PlatformEffectsParser.parse("")
        assertEquals("default", empty.cursorIcon)
        assertNull(empty.pickerRequest)
        assertFalse(empty.fullscreen)
        assertFalse(empty.inSlideshow)

        val broken = PlatformEffectsParser.parse("{not-valid-json")
        assertEquals("DeckCraft", broken.windowTitle)
        assertEquals(-1L, broken.repaintAfterMs)
    }

    @Test
    fun parse_blankSavedPathAndPrintPath_areDropped() {
        val effects = PlatformEffectsParser.parse(
            """{"savedFile":{"path":""},"printJob":{"jobName":"x","pdfPath":""},"windowTitle":""}""",
        )
        assertNull(effects.savedFile)
        assertNull(effects.printJob)
        assertEquals("DeckCraft", effects.windowTitle)
    }
}
