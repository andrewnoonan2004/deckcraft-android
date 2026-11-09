package com.andrewnoonan.deckcraft

import android.app.Application
import com.andrewnoonan.deckcraft.platform.CrashReporter

/**
 * Application class for DeckCraft.
 *
 * Installs the uncaught exception handler as early as possible so that any crash
 * (JVM or native panic) is recorded in filesDir and can be displayed to the user
 * on next launch.
 */
class DeckCraftApplication : Application() {

    lateinit var crashReporter: CrashReporter
        private set

    override fun onCreate() {
        super.onCreate()
        crashReporter = CrashReporter(filesDir)
        crashReporter.install()
    }
}
