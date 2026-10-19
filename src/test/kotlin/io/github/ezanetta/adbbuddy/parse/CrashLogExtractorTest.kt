package io.github.ezanetta.adbbuddy.parse

import io.github.ezanetta.adbbuddy.testutil.fixture
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class CrashLogExtractorTest {
    private val crash = fixture("logcat_crash_buffer.txt")
    private val system = fixture("logcat_system_anr.txt")

    @Test
    fun `returns the latest java crash for the package, including secondary processes`() {
        val report = CrashLogExtractor.lastJavaCrash(crash, "com.example.app").shouldNotBeNull()
        report.timestamp shouldBe "10-19 11:31:02.000"
        report.text shouldContain "Process: com.example.app:remote"
    }

    @Test
    fun `keeps the whole block and drops interleaved lines from other processes`() {
        val report = CrashLogExtractor.lastJavaCrash(
            crash.replace("com.example.app:remote", "com.example.other"),
            "com.example.app",
        )
            .shouldNotBeNull()
        report.timestamp shouldBe "10-19 11:30:45.512"
        report.lines.first() shouldBe "FATAL EXCEPTION: main"
        report.text shouldContain "ProfileFragment.onDataLoaded(ProfileFragment.kt:88)"
        report.text shouldContain "Caused by: java.lang.RuntimeException: inner"
        report.text shouldNotContain "unrelated interleaved line"
        report.text shouldNotContain "old crash"
    }

    @Test
    fun `does not confuse packages that share a prefix`() {
        CrashLogExtractor.lastJavaCrash(crash, "com.example").shouldBeNull()
        CrashLogExtractor.lastJavaCrash(crash, "com.other.app").shouldNotBeNull().text shouldContain
            "SocketTimeoutException"
    }

    @Test
    fun `extracts native tombstone summary`() {
        val report = CrashLogExtractor.lastNativeCrash(crash, "com.example.native").shouldNotBeNull()
        report.text shouldContain "signal 11 (SIGSEGV)"
        report.text shouldContain "libnative.so (crash_now+20)"
        CrashLogExtractor.lastNativeCrash(crash, "com.example.app").shouldBeNull()
    }

    @Test
    fun `extracts ANR report`() {
        val report = CrashLogExtractor.lastAnr(system, "com.example.app").shouldNotBeNull()
        report.lines.first() shouldBe "ANR in com.example.app (com.example.app/.MainActivity)"
        report.text shouldContain "Reason: Input dispatching timed out"
        report.text shouldContain "CPU usage from 0ms"
        report.text shouldNotContain "has died"
    }
}
