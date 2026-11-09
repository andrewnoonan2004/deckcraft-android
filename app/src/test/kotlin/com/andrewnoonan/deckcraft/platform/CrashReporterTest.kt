package com.andrewnoonan.deckcraft.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashReporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun noPendingReportWhenNothingCrashed() {
        assertNull(CrashReporter(tmp.root).consumePendingReport())
    }

    @Test
    fun javaCrashIsRecordedThenConsumedOnce() {
        val reporter = CrashReporter(tmp.root)
        reporter.recordJavaCrash(Thread(null, {}, "GLThread 42"), IllegalStateException("surface lost"))
        val report = reporter.consumePendingReport()
        assertNotNull(report)
        assertTrue(report!!.contains("GLThread 42"))
        assertTrue(report.contains("IllegalStateException: surface lost"))
        assertNull(reporter.consumePendingReport())
    }

    @Test
    fun nativePanicFileWrittenByRustIsIncluded() {
        File(tmp.root, CrashReporter.NATIVE_CRASH_FILE).writeText("panicked at src/lib.rs:1:1: boom\n")
        val report = CrashReporter(tmp.root).consumePendingReport()
        assertTrue(report!!.contains("boom"))
        assertFalse(File(tmp.root, CrashReporter.NATIVE_CRASH_FILE).exists())
    }

    @Test
    fun installedHandlerRecordsAndDelegatesToPrevious() {
        val reporter = CrashReporter(tmp.root)
        var delegated: Throwable? = null
        val handler = reporter.handlerChainingTo { _, e -> delegated = e }
        val boom = RuntimeException("boom")
        handler.uncaughtException(Thread.currentThread(), boom)
        assertSame(boom, delegated)
        assertTrue(reporter.consumePendingReport()!!.contains("RuntimeException: boom"))
    }

    @Test
    fun recordingNeverThrowsWhenDirectoryIsUnwritable() {
        val reporter = CrashReporter(tmp.newFile("plain-file"))
        reporter.recordJavaCrash(Thread.currentThread(), RuntimeException("x"))
        assertNull(reporter.consumePendingReport())
    }
}
