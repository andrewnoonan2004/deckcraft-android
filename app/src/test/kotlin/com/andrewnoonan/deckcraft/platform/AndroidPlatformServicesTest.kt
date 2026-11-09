package com.andrewnoonan.deckcraft.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AndroidPlatformServicesTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun filePreferencesStore_persistsAcrossInstances() {
        val dir = File(app.cacheDir, "prefs_${System.nanoTime()}")
        val store1 = FilePreferencesStore(dir)
        assertNull(store1.loadEnginePrefs())
        assertTrue(store1.isDesktopBarExpanded())
        store1.saveEnginePrefs("""{"theme":"Dark"}""")
        store1.setDesktopBarExpanded(false)

        val store2 = FilePreferencesStore(dir)
        assertEquals("""{"theme":"Dark"}""", store2.loadEnginePrefs())
        assertFalse(store2.isDesktopBarExpanded())
    }

    @Test
    fun clipboardBridge_roundTripsText() {
        val clipboard = AndroidClipboardBridge(app)
        clipboard.setText("Slide 3 — Roadmap")
        assertEquals("Slide 3 — Roadmap", clipboard.getText())
    }

    @Test
    fun urlLauncher_allowsOnlyHttps() {
        val launcher = AndroidUrlLauncherBridge(app)
        assertFalse(launcher.openHttpsUrl("http://example.com"))
        assertFalse(launcher.openHttpsUrl("javascript:alert(1)"))
        assertTrue(launcher.openHttpsUrl("https://getartcraft.com"))
        assertEquals("https://getartcraft.com", shadowOf(app).nextStartedActivity.dataString)
    }

    @Test
    fun shareBridge_rejectsMissingFile() {
        assertFalse(AndroidShareBridge(app).sharePresentationFile("/nonexistent/deck.pptx", "Deck"))
    }
}
