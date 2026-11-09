package com.andrewnoonan.deckcraft.platform

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Persists crashes to the app's private files directory so they can be shown on the next launch.
 *
 * The Rust bridge writes native panics to [NATIVE_CRASH_FILE] (see `rust-bridge/src/lib.rs`);
 * uncaught JVM exceptions are written to [JAVA_CRASH_FILE] by the handler from [install].
 * Recording never throws: it runs while the process is already dying.
 */
class CrashReporter(private val dir: File) {

    fun recordJavaCrash(thread: Thread, error: Throwable) {
        runCatching {
            File(dir, JAVA_CRASH_FILE).writeText(
                "Uncaught exception on thread '${thread.name}':\n${describe(error)}",
            )
        }
    }

    /** Returns the pending crash text (native first, then JVM) and deletes it, or null if none. */
    fun consumePendingReport(): String? {
        val parts = listOf(NATIVE_CRASH_FILE, JAVA_CRASH_FILE).mapNotNull { name ->
            val file = File(dir, name)
            val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
            runCatching { file.delete() }
            text?.takeIf { it.isNotBlank() }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(separator = "\n\n")
    }

    fun handlerChainingTo(previous: Thread.UncaughtExceptionHandler?): Thread.UncaughtExceptionHandler =
        Thread.UncaughtExceptionHandler { thread, error ->
            recordJavaCrash(thread, error)
            previous?.uncaughtException(thread, error)
        }

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(
            handlerChainingTo(Thread.getDefaultUncaughtExceptionHandler()),
        )
    }

    companion object {
        /** Must match `NATIVE_CRASH_FILE_NAME` in the Rust bridge. */
        const val NATIVE_CRASH_FILE = "native_crash.txt"
        const val JAVA_CRASH_FILE = "java_crash.txt"

        fun describe(error: Throwable): String {
            val out = StringWriter()
            PrintWriter(out).use { error.printStackTrace(it) }
            return out.toString()
        }
    }
}
