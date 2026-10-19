package io.github.ezanetta.adbbuddy.adb

import io.github.ezanetta.adbbuddy.parse.DeviceEntry
import io.github.ezanetta.adbbuddy.parse.DevicesParser
import io.github.ezanetta.adbbuddy.parse.GetpropParser
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Typed wrapper around the adb binary.
 *
 * Commands are always passed to [ProcessRunner] as argument lists. `adb shell` joins its
 * arguments into one string that the *device* shell parses, so every remote argument is
 * single-quoted with [shellQuote] before it leaves this class.
 */
class AdbClient(
    private val adbPath: String,
    private val runner: ProcessRunner,
    private val defaultTimeout: Duration = 10.seconds,
) {
    suspend fun devices(): List<DeviceEntry> = DevicesParser.parse(host(listOf("devices", "-l")).stdoutText)

    /** Returns a ready device serial, picking the only device when [requested] is null. */
    suspend fun resolveSerial(requested: String?): String {
        val devices = devices()
        if (requested != null) {
            val match = devices.firstOrNull { it.serial == requested }
                ?: throw AdbException.DeviceNotFound(requested, devices.map { it.serial })
            if (!match.isReady) throw AdbException.DeviceNotReady(match.serial, match.state)
            return match.serial
        }
        return when (devices.size) {
            0 -> throw AdbException.NoDevices()
            1 -> devices.single().also {
                if (!it.isReady) throw AdbException.DeviceNotReady(it.serial, it.state)
            }.serial
            else -> throw AdbException.MultipleDevices(devices.map { "${it.serial} (${it.state})" })
        }
    }

    /** Model, Android version and emulator flag for a ready device, from a single `getprop` call. */
    suspend fun deviceInfo(entry: DeviceEntry): DeviceInfo {
        val props = GetpropParser.parse(shell(entry.serial, listOf("getprop")))
        return DeviceInfo(
            serial = entry.serial,
            state = entry.state,
            model = props["ro.product.model"] ?: entry.properties["model"]?.replace('_', ' '),
            manufacturer = props["ro.product.manufacturer"],
            androidVersion = props["ro.build.version.release"],
            sdk = props["ro.build.version.sdk"]?.toIntOrNull(),
            isEmulator = entry.isEmulator || props["ro.kernel.qemu"] == "1" || props["ro.boot.qemu"] == "1",
        )
    }

    /** PID of the app's main process, or null when it is not running. */
    suspend fun pidOf(serial: String, packageName: String): Int? =
        shell(serial, listOf("pidof", packageName), checkExit = false)
            .trim()
            .split(' ')
            .firstNotNullOfOrNull { it.toIntOrNull() }

    /** Dumps logcat in threadtime format. [tail] limits raw lines; [pid] filters by process. */
    suspend fun logcat(
        serial: String,
        buffers: List<String> = emptyList(),
        pid: Int? = null,
        tail: Int? = null,
    ): String {
        val args = buildList {
            addAll(listOf("logcat", "-d", "-v", "threadtime"))
            buffers.forEach { addAll(listOf("-b", it)) }
            pid?.let { add("--pid=$it") }
            tail?.let { addAll(listOf("-t", it.toString())) }
        }
        return shell(serial, args, timeout = LOGCAT_TIMEOUT)
    }

    /** Runs `adb -s <serial> shell <args>` and returns stdout as text. */
    suspend fun shell(
        serial: String,
        args: List<String>,
        timeout: Duration = defaultTimeout,
        maxOutputBytes: Int = TEXT_CAP_BYTES,
        checkExit: Boolean = true,
    ): String = run(
        listOf("-s", serial, "shell") + args.map(::shellQuote),
        describe = "adb shell ${args.joinToString(" ")}",
        timeout = timeout,
        maxOutputBytes = maxOutputBytes,
        checkExit = checkExit,
    ).stdoutText

    /** Runs `adb -s <serial> exec-out <args>` and returns raw stdout bytes (no CRLF mangling). */
    suspend fun execOut(
        serial: String,
        args: List<String>,
        timeout: Duration = defaultTimeout,
        maxOutputBytes: Int = BINARY_CAP_BYTES,
    ): ProcessResult = run(
        listOf("-s", serial, "exec-out") + args.map(::shellQuote),
        describe = "adb exec-out ${args.joinToString(" ")}",
        timeout = timeout,
        maxOutputBytes = maxOutputBytes,
    )

    /** Runs a host-side adb command such as `devices` or `-s <serial> install`. */
    suspend fun host(args: List<String>, timeout: Duration = defaultTimeout): ProcessResult =
        run(args, describe = "adb ${args.joinToString(" ")}", timeout = timeout, maxOutputBytes = TEXT_CAP_BYTES)

    private suspend fun run(
        args: List<String>,
        describe: String,
        timeout: Duration,
        maxOutputBytes: Int,
        checkExit: Boolean = true,
    ): ProcessResult {
        val result = runner.run(listOf(adbPath) + args, timeout, maxOutputBytes)
        if (result.timedOut) throw AdbException.Timeout(describe, timeout)
        if (checkExit && result.exitCode != 0) {
            throw AdbException.CommandFailed(describe, result.exitCode, result.stderr.ifBlank { result.stdoutText })
        }
        return result
    }

    companion object {
        const val TEXT_CAP_BYTES = 4 * 1024 * 1024
        const val BINARY_CAP_BYTES = 32 * 1024 * 1024
        val LOGCAT_TIMEOUT = 20.seconds

        /** Quotes [arg] for a POSIX shell: wrap in single quotes, escape embedded single quotes. */
        fun shellQuote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"
    }
}

data class DeviceInfo(
    val serial: String,
    val state: String,
    val model: String? = null,
    val manufacturer: String? = null,
    val androidVersion: String? = null,
    val sdk: Int? = null,
    val isEmulator: Boolean = false,
)
