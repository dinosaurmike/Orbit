package com.example.companion2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CrashReportTest {
    @Test
    fun keepsTheVersionTimeAndWhereItStopped() {
        val error = IllegalStateException("lateinit property settings has not been initialized")
        val report = CrashReport.text(error, "0.1.1", 0L)
        assertTrue(report.startsWith("Orbit 0.1.1 stopped at "))
        assertTrue(report.contains("IllegalStateException: lateinit property settings"))
        assertTrue(report.contains("CrashReportTest"))  // the place in the code
    }

    @Test
    fun staysShort() {
        var error: Throwable = RuntimeException("start")
        repeat(200) { error = RuntimeException("wrapped $it", error) }
        assertTrue(CrashReport.text(error, "0.1.1", 0L).length <= 6000)
    }

    @Test
    fun savesReadsAndClears() {
        val dir = Files.createTempDirectory("orbit-crash").toFile()
        assertNull(CrashReport.read(dir))
        CrashReport.save(dir, RuntimeException("boom"), "0.1.1", 0L)
        assertTrue(CrashReport.read(dir)!!.contains("boom"))
        CrashReport.clear(dir)
        assertNull(CrashReport.read(dir))
        assertEquals(0, dir.listFiles()!!.size)
        dir.delete()
    }
}
