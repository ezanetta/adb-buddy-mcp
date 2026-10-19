package io.github.ezanetta.adbbuddy.adb

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@DisabledOnOs(OS.WINDOWS)
class JvmProcessRunnerTest {
    private val runner = JvmProcessRunner()

    @Test
    fun `captures stdout, stderr and exit code`(): Unit = runBlocking {
        val result = runner.run(listOf("sh", "-c", "echo out; echo err >&2; exit 3"), 5.seconds, 1024)
        result.stdoutText shouldBe "out\n"
        result.stderr shouldBe "err\n"
        result.exitCode shouldBe 3
        result.timedOut shouldBe false
    }

    @Test
    fun `arguments are not interpreted by a shell`(): Unit = runBlocking {
        runner.run(listOf("echo", "\$(id)", ";", "ls"), 5.seconds, 1024).stdoutText shouldBe "\$(id) ; ls\n"
    }

    @Test
    fun `kills the process on timeout`(): Unit = runBlocking {
        val result = runner.run(listOf("sleep", "10"), 200.milliseconds, 1024)
        result.timedOut shouldBe true
    }

    @Test
    fun `caps stdout and flags truncation`(): Unit = runBlocking {
        val result = runner.run(listOf("sh", "-c", "head -c 100000 /dev/zero"), 5.seconds, 1000)
        result.stdout.size shouldBe 1000
        result.stdoutTruncated shouldBe true
    }
}
