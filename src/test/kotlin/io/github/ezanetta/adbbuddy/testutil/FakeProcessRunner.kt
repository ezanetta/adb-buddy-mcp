package io.github.ezanetta.adbbuddy.testutil

import io.github.ezanetta.adbbuddy.adb.ProcessResult
import io.github.ezanetta.adbbuddy.adb.ProcessRunner
import kotlin.time.Duration

/**
 * Scripted [ProcessRunner]. Responses are matched by the first rule whose predicate accepts the
 * argument list (without the adb path); every invocation is recorded in [calls].
 */
class FakeProcessRunner : ProcessRunner {
    private val rules = mutableListOf<Pair<(List<String>) -> Boolean, (List<String>) -> ProcessResult>>()
    val calls = mutableListOf<List<String>>()

    fun on(predicate: (List<String>) -> Boolean, response: (List<String>) -> ProcessResult) = apply {
        rules += predicate to response
    }

    /** Matches when the args end with [suffix], e.g. `onShell("'wm'", "'size'")`. */
    fun onArgs(vararg suffix: String, stdout: String = "", exitCode: Int = 0, stderr: String = "") = on(
        { it.takeLast(suffix.size) == suffix.toList() },
        { ProcessResult(exitCode, stdout.encodeToByteArray(), stderr) },
    )

    fun onArgs(vararg suffix: String, response: (List<String>) -> ProcessResult) =
        on({ it.takeLast(suffix.size) == suffix.toList() }, response)

    fun onDevices(output: String) = onArgs("devices", "-l", stdout = output)

    override suspend fun run(command: List<String>, timeout: Duration, maxOutputBytes: Int): ProcessResult {
        val args = command.drop(1)
        calls += args
        val rule = rules.lastOrNull { it.first(args) }
            ?: error("FakeProcessRunner: no rule for $args")
        return rule.second(args)
    }
}

fun ok(stdout: String = "") = ProcessResult(0, stdout.encodeToByteArray(), "")

fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

const val ONE_EMULATOR =
    "List of devices attached\nemulator-5554\tdevice product:sdk_gphone64_arm64 model:sdk_gphone64_arm64\n"
