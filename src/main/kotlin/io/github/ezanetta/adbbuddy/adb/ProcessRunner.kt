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

/** Which part of stdout to keep when it is longer than the cap. */
enum class OutputCap {
    /** Keep the first bytes: right for structured output that is parsed from the start. */
    KEEP_HEAD,

    /** Keep the last bytes: right for logs, where the newest lines matter most. */
    KEEP_TAIL,
}

/**
 * Runs a command given as an argument list. Implementations must never route
 * the arguments through a host shell.
 */
fun interface ProcessRunner {
    suspend fun run(command: List<String>, timeout: Duration, maxOutputBytes: Int, cap: OutputCap): ProcessResult
}

/** [ProcessRunner] backed by [ProcessBuilder]. */
class JvmProcessRunner : ProcessRunner {
    override suspend fun run(
        command: List<String>,
        timeout: Duration,
        maxOutputBytes: Int,
        cap: OutputCap,
    ): ProcessResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command).start()
        // The child must never see our stdin: that stream belongs to the MCP transport.
        process.outputStream.close()
        try {
            coroutineScope {
                val stdout = async { runInterruptible { process.inputStream.readCapped(maxOutputBytes, cap) } }
                val stderr =
                    async {
                        runInterruptible { process.errorStream.readCapped(STDERR_CAP_BYTES, OutputCap.KEEP_HEAD) }
                    }
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

    /**
     * Reads the whole stream, so the child never blocks on a full pipe, but keeps at most [cap] bytes:
     * the first ones or the last ones depending on [mode].
     */
    private fun InputStream.readCapped(cap: Int, mode: OutputCap): CappedBytes {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            total += n
            when (mode) {
                OutputCap.KEEP_HEAD -> {
                    val room = cap - buffer.size()
                    if (room > 0) buffer.write(chunk, 0, minOf(n, room))
                }
                OutputCap.KEEP_TAIL -> {
                    buffer.write(chunk, 0, n)
                    // Compact occasionally rather than on every chunk; memory stays under 2 * cap.
                    if (buffer.size() > 2 * cap) {
                        val bytes = buffer.toByteArray()
                        buffer.reset()
                        buffer.write(bytes, bytes.size - cap, cap)
                    }
                }
            }
        }
        val bytes = buffer.toByteArray()
        val kept = if (bytes.size > cap) bytes.copyOfRange(bytes.size - cap, bytes.size) else bytes
        return CappedBytes(kept, total > cap)
    }

    private companion object {
        const val STDERR_CAP_BYTES = 64 * 1024
    }
}
