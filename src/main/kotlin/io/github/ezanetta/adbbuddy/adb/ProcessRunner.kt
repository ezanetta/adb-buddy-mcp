package io.github.ezanetta.adbbuddy.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Outcome of running an external process. [stdout] is capped; see [stdoutTruncated]. */
class ProcessResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: String,
    val timedOut: Boolean = false,
    val stdoutTruncated: Boolean = false,
) {
    val stdoutText: String get() = stdout.decodeToString()
}

/**
 * Runs a command given as an argument list. Implementations must never route
 * the arguments through a host shell.
 */
fun interface ProcessRunner {
    suspend fun run(command: List<String>, timeout: Duration, maxOutputBytes: Int): ProcessResult
}

/** [ProcessRunner] backed by [ProcessBuilder]. */
class JvmProcessRunner : ProcessRunner {
    override suspend fun run(command: List<String>, timeout: Duration, maxOutputBytes: Int): ProcessResult =
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder(command).start()
            // The child must never see our stdin: that stream belongs to the MCP transport.
            process.outputStream.close()
            try {
                coroutineScope {
                    val stdout = async { runInterruptible { process.inputStream.readCapped(maxOutputBytes) } }
                    val stderr = async { runInterruptible { process.errorStream.readCapped(STDERR_CAP_BYTES) } }
                    val finished =
                        runInterruptible { process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
                    if (!finished) {
                        process.destroyForcibly()
                    }
                    val out = stdout.await()
                    val err = stderr.await()
                    ProcessResult(
                        exitCode = if (finished) process.exitValue() else -1,
                        stdout = out.bytes,
                        stderr = err.bytes.decodeToString(),
                        timedOut = !finished,
                        stdoutTruncated = out.truncated,
                    )
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }

    private class CappedBytes(val bytes: ByteArray, val truncated: Boolean)

    /** Reads up to [cap] bytes, then keeps draining so the child never blocks on a full pipe. */
    private fun InputStream.readCapped(cap: Int): CappedBytes {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var truncated = false
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            val room = cap - buffer.size()
            if (room > 0) buffer.write(chunk, 0, minOf(n, room))
            if (n > room) truncated = true
        }
        return CappedBytes(buffer.toByteArray(), truncated)
    }

    private companion object {
        const val STDERR_CAP_BYTES = 64 * 1024
    }
}
