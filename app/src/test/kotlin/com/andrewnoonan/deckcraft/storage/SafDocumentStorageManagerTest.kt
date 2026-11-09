package com.andrewnoonan.deckcraft.storage

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SafDocumentStorageManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val baseDir = File(context.cacheDir, "saf_${System.nanoTime()}").apply { mkdirs() }
    private val manager = SafDocumentStorageManager(context.contentResolver, baseDir)

    @Test
    fun sanitizeFileName_stripsPathsAndIllegalCharacters() {
        assertEquals("deck.pptx", SafDocumentStorageManager.sanitizeFileName("../../etc/deck.pptx"))
        assertEquals("Q1_2026_.pptx", SafDocumentStorageManager.sanitizeFileName("Q1:2026?.pptx"))
        assertEquals("Presentation.deckcraft", SafDocumentStorageManager.sanitizeFileName("   "))
        assertEquals("notes.deckcraft", SafDocumentStorageManager.sanitizeFileName("notes"))
    }

    @Test
    fun importThenSave_roundTripsBytesBackToOrigin() {
        val origin = File(context.cacheDir, "origin_${System.nanoTime()}.pptx")
        val initial = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)
        origin.writeBytes(initial)

        val imported = manager.importReadableUri(Uri.fromFile(origin))
        assertNotNull(imported)
        assertArrayEquals(initial, imported!!.localFile.readBytes())

        val updated = initial + byteArrayOf(9, 9, 9)
        imported.localFile.writeBytes(updated)
        assertTrue(manager.syncSavedPathToOrigin(imported.localFile.absolutePath))
        assertArrayEquals(updated, origin.readBytes())
    }

    @Test
    fun saveAsTarget_isStagedAndSyncedToChosenUri() {
        val target = File(context.cacheDir, "saveas_${System.nanoTime()}.deckcraft")
        val staged = manager.registerWritableTargetUri(Uri.fromFile(target), "Pitch.deckcraft")
        staged.writeBytes("deck-bytes".encodeToByteArray())
        assertTrue(manager.syncSavedPathToOrigin(staged.absolutePath))
        assertEquals("deck-bytes", target.readText())
    }

    @Test
    fun syncUnknownPath_returnsFalse() {
        val stray = File(baseDir, "stray.pptx").apply { writeText("x") }
        assertFalse(manager.syncSavedPathToOrigin(stray.absolutePath))
        assertFalse(manager.syncSavedPathToOrigin(""))
    }
}
