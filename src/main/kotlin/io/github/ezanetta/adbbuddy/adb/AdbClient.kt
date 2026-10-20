package io.github.ezanetta.adbbuddy.adb

import io.github.ezanetta.adbbuddy.image.PngScaler
import io.github.ezanetta.adbbuddy.parse.ActivityParser
import io.github.ezanetta.adbbuddy.parse.Component
import io.github.ezanetta.adbbuddy.parse.DeviceEntry
import io.github.ezanetta.adbbuddy.parse.DevicesParser
import io.github.ezanetta.adbbuddy.parse.DisplaySize
import io.github.ezanetta.adbbuddy.parse.DisplaySizeParser
import io.github.ezanetta.adbbuddy.parse.GetpropParser
import io.github.ezanetta.adbbuddy.parse.PackagesParser
import java.io.File
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

    /**
     * Dumps logcat in threadtime format. [tail] limits raw lines; [pid] filters by process.
     * If the output is over the cap, the newest part is kept and [LogcatDump.truncated] is set.
     */
    suspend fun logcat(
        serial: String,
        buffers: List<String> = emptyList(),
        pid: Int? = null,
        tail: Int? = null,
    ): LogcatDump {
        val args = buildList {
            addAll(listOf("logcat", "-d", "-v", "threadtime"))
            buffers.forEach { addAll(listOf("-b", it)) }
            pid?.let { add("--pid=$it") }
            tail?.let { addAll(listOf("-t", it.toString())) }
        }
        val result = run(
            listOf("-s", serial, "shell") + args.map(::shellQuote),
            describe = "adb shell ${args.joinToString(" ")}",
            timeout = LOGCAT_TIMEOUT,
            maxOutputBytes = TEXT_CAP_BYTES,
            cap = OutputCap.KEEP_TAIL,
        )
        return LogcatDump(result.stdoutText, result.stdoutTruncated)
    }

    /** Raw PNG from `screencap -p`, read through exec-out so the bytes are not mangled. */
    suspend fun screenshot(serial: String): ByteArray {
        val result = execOut(serial, listOf("screencap", "-p"), timeout = SCREEN_TIMEOUT)
        if (!PngScaler.isPng(result.stdout)) {
            throw AdbException.CommandFailed(
                "adb exec-out screencap -p",
                0,
                result.stdoutText.take(200).ifBlank {
                    result.stderr
                },
            )
        }
        return result.stdout
    }

    /** uiautomator XML for the current screen. The temp file lives in /data/local/tmp and is removed afterwards. */
    suspend fun dumpUi(serial: String): String {
        val dump = shell(serial, listOf("uiautomator", "dump", UI_DUMP_PATH), timeout = SCREEN_TIMEOUT)
        if ("dumped to" !in dump) {
            throw AdbException.CommandFailed(
                "adb shell uiautomator dump",
                0,
                dump.ifBlank { "no output" } + " (the screen may be animating or showing secure content; retry)",
            )
        }
        return try {
            execOut(serial, listOf("cat", UI_DUMP_PATH), maxOutputBytes = TEXT_CAP_BYTES).stdoutText
        } finally {
            shell(serial, listOf("rm", "-f", UI_DUMP_PATH), checkExit = false)
        }
    }

    /** Current screen size in the current orientation. */
    suspend fun displaySize(serial: String): DisplaySize =
        DisplaySizeParser.fromDumpsys(shell(serial, listOf("dumpsys", "window", "displays")))
            ?: DisplaySizeParser.fromWmSize(shell(serial, listOf("wm", "size")))
            ?: throw AdbException.CommandFailed("adb shell wm size", 0, "could not determine the screen size")

    /** Runs `input <args>` on the device (tap, swipe, text, keyevent). */
    suspend fun input(serial: String, args: List<String>, timeout: Duration = defaultTimeout) {
        shell(serial, listOf("input") + args, timeout = timeout)
    }

    suspend fun thirdPartyPackages(serial: String): List<String> =
        PackagesParser.parse(shell(serial, listOf("pm", "list", "packages", "-3")))

    suspend fun resumedActivity(serial: String): Component? =
        ActivityParser.resumedActivity(shell(serial, listOf("dumpsys", "activity", "activities")))

    /** Starts the app's launcher activity and returns it. */
    suspend fun launchApp(serial: String, packageName: String): Component {
        requireInstalled(serial, packageName)
        val resolved = shell(
            serial,
            listOf(
                "cmd", "package", "resolve-activity", "--brief",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", packageName,
            ),
        )
        val component = ActivityParser.resolvedActivity(resolved)
            ?: throw AdbException.NotFound("$packageName has no launcher activity, so it cannot be launched directly.")
        // Older `am` versions exit 0 on failure, so the output is checked as well.
        val start = shellResult(serial, listOf("am", "start", "-n", component.flattened))
        val output = (start.stdoutText + "\n" + start.stderr).trim()
        if (start.exitCode != 0 || output.lineSequence().any { it.startsWith("Error") }) {
            throw AdbException.CommandFailed("adb shell am start -n ${component.flattened}", start.exitCode, output)
        }
        return component
    }

    /**
     * `adb install -r -t`: installs or replaces the app, keeping its data. `-t` allows test-only
     * APKs, which is what Android Studio's Run button produces.
     */
    suspend fun install(serial: String, apk: File) {
        val result = host(listOf("-s", serial, "install", "-r", "-t", apk.absolutePath), timeout = INSTALL_TIMEOUT)
        if ("Success" !in result.stdoutText) {
            throw AdbException.CommandFailed(
                "adb install -r -t ${apk.name}",
                result.exitCode,
                result.stdoutText + result.stderr,
            )
        }
    }

    suspend fun clearAppData(serial: String, packageName: String) {
        requireInstalled(serial, packageName)
        val result = shellResult(serial, listOf("pm", "clear", packageName))
        if (result.exitCode != 0 || "Success" !in result.stdoutText) {
            throw AdbException.CommandFailed(
                "adb shell pm clear $packageName",
                result.exitCode,
                (result.stdoutText + result.stderr).trim(),
            )
        }
    }

    private suspend fun requireInstalled(serial: String, packageName: String) {
        if ("package:" !in shell(serial, listOf("pm", "path", packageName), checkExit = false)) {
            throw AdbException.NotFound(
                "$packageName is not installed on $serial. Use list_packages to see installed apps.",
            )
        }
    }

    /** Like [shell] but returns the whole result without checking the exit code. */
    private suspend fun shellResult(serial: String, args: List<String>): ProcessResult = run(
        listOf("-s", serial, "shell") + args.map(::shellQuote),
        describe = "adb shell ${args.joinToString(" ")}",
        timeout = defaultTimeout,
        maxOutputBytes = TEXT_CAP_BYTES,
        checkExit = false,
    )

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

    /**
     * Runs `adb -s <serial> exec-out <args>` and returns raw stdout bytes (no CRLF mangling).
     *
     * exec-out does not parse arguments the way `sh` does, so quoting cannot be relied on. Only
     * plain tokens are accepted; this is for internal constants, never for model-supplied input.
     */
    suspend fun execOut(
        serial: String,
        args: List<String>,
        timeout: Duration = defaultTimeout,
        maxOutputBytes: Int = BINARY_CAP_BYTES,
    ): ProcessResult {
        require(args.all { plainToken.matches(it) }) { "exec-out only accepts plain tokens: $args" }
        return run(
            listOf("-s", serial, "exec-out") + args,
            describe = "adb exec-out ${args.joinToString(" ")}",
            timeout = timeout,
            maxOutputBytes = maxOutputBytes,
        )
    }

    /** Runs a host-side adb command such as `devices` or `-s <serial> install`. */
    suspend fun host(args: List<String>, timeout: Duration = defaultTimeout): ProcessResult =
        run(args, describe = "adb ${args.joinToString(" ")}", timeout = timeout, maxOutputBytes = TEXT_CAP_BYTES)

    private suspend fun run(
        args: List<String>,
        describe: String,
        timeout: Duration,
        maxOutputBytes: Int,
        checkExit: Boolean = true,
        cap: OutputCap = OutputCap.KEEP_HEAD,
    ): ProcessResult {
        val result = runner.run(listOf(adbPath) + args, timeout, maxOutputBytes, cap)
        if (result.timedOut) throw AdbException.Timeout(describe, timeout)
        if (checkExit && result.exitCode != 0) {
            throw AdbException.CommandFailed(describe, result.exitCode, result.stderr.ifBlank { result.stdoutText })
        }
        // Partial structured output (XML, dumpsys, a PNG) would parse into wrong answers, so it is an
        // error. Only callers that keep the tail, such as logcat, accept and report truncation.
        if (result.stdoutTruncated && cap == OutputCap.KEEP_HEAD) {
            throw AdbException.OutputTooLarge(describe, maxOutputBytes)
        }
        return result
    }

    companion object {
        private val plainToken = Regex("^[A-Za-z0-9_./:=@%+-]+$")

        const val TEXT_CAP_BYTES = 4 * 1024 * 1024
        const val BINARY_CAP_BYTES = 32 * 1024 * 1024
        val LOGCAT_TIMEOUT = 20.seconds
        val SCREEN_TIMEOUT = 30.seconds
        val INSTALL_TIMEOUT = 180.seconds
        const val UI_DUMP_PATH = "/data/local/tmp/adb-buddy-ui.xml"

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

/** logcat text; [truncated] means only the newest part of the output was kept. */
class LogcatDump(val text: String, val truncated: Boolean)
