package io.github.ezanetta.adbbuddy.tools

import io.github.ezanetta.adbbuddy.adb.AdbClient
import io.github.ezanetta.adbbuddy.adb.AdbException
import io.github.ezanetta.adbbuddy.parse.CrashLogExtractor
import io.github.ezanetta.adbbuddy.parse.CrashReport
import io.github.ezanetta.adbbuddy.parse.LogLevel
import io.github.ezanetta.adbbuddy.parse.LogcatFilter
import io.github.ezanetta.adbbuddy.parse.LogcatParser
import io.github.ezanetta.adbbuddy.validate.Validators
import io.modelcontextprotocol.kotlin.sdk.server.Server

private const val DEFAULT_LOG_LINES = 200
private const val MAX_LOG_LINES = 2000

/** Raw lines scanned when filtering host-side; logcat's `-t` applies before tag/level filters. */
private const val SCAN_WINDOW = 20_000

fun Server.registerLogTools(adb: AdbClient) {
    adbTool(
        name = "get_logcat",
        description = "Read recent logcat lines (threadtime format), newest last. Filter by exact tag, " +
            "minimum level and/or app package (matches the app's running process).",
        annotations = Annotations.readOnly("Get logcat"),
        schema = schema {
            string("tag", "Exact log tag, e.g. OkHttp or ActivityManager.")
            string(
                "level",
                "Minimum priority. Lines at this level and above are returned.",
                enum = listOf("V", "D", "I", "W", "E", "F"),
            )
            integer(
                "lines",
                "Number of matching lines to return.",
                min = 1,
                max = MAX_LOG_LINES,
                default = DEFAULT_LOG_LINES,
            )
            string("package", "Only lines from this app's process, e.g. com.example.app. The app must be running.")
        },
    ) { args ->
        val serial = adb.resolveSerial(args.serial)
        val tag = args.optionalString("tag")?.let(Validators::logTag)
        val level = args.optionalString("level")?.let {
            LogLevel.parse(it) ?: throw AdbException.InvalidArgument("`level` must be one of V, D, I, W, E, F.")
        }
        val limit = args.optionalInt("lines", 1..MAX_LOG_LINES) ?: DEFAULT_LOG_LINES
        val packageName = args.optionalString("package")?.let(Validators::packageName)
        val pid = packageName?.let {
            adb.pidOf(serial, it) ?: throw AdbException.NotFound(
                "$it is not running, so its logs cannot be filtered by process. Launch it with launch_app, " +
                    "or use get_crash_log if it crashed.",
            )
        }

        val filtering = tag != null || level != null
        val raw = adb.logcat(serial, pid = pid, tail = if (filtering) SCAN_WINDOW else limit)
        val lines = LogcatFilter.filter(LogcatParser.parse(raw), tag, level, limit)

        val filters = listOfNotNull(
            packageName?.let { "package=$it (pid $pid)" },
            tag?.let { "tag=$it" },
            level?.let { "level>=${it.letter}" },
        ).ifEmpty { listOf("no filters") }.joinToString(", ")
        val header = "logcat on $serial, $filters: ${lines.size} line(s)"
        if (lines.isEmpty()) {
            textResult("$header. No matching lines.")
        } else {
            textResult(header + "\n" + lines.joinToString("\n") { it.raw }, keep = Keep.TAIL)
        }
    }

    adbTool(
        name = "get_crash_log",
        description = "Get the most recent crash for an app: Java FATAL EXCEPTION stack trace, native crash " +
            "(tombstone summary) and/or ANR report, read from the device's crash, main and system log buffers.",
        annotations = Annotations.readOnly("Get crash log"),
        schema = schema {
            string("package", "App package name, e.g. com.example.app.", required = true)
        },
    ) { args ->
        val serial = adb.resolveSerial(args.serial)
        val packageName = Validators.packageName(args.string("package"))

        val crashBuffer = adb.logcat(serial, buffers = listOf("crash"))
        val systemBuffers = adb.logcat(serial, buffers = listOf("main", "system"), tail = SCAN_WINDOW)
        val reports = listOfNotNull(
            CrashLogExtractor.lastJavaCrash(crashBuffer, packageName),
            CrashLogExtractor.lastNativeCrash(crashBuffer, packageName),
            CrashLogExtractor.lastAnr(systemBuffers, packageName),
        ).sortedByDescending { it.timestamp }

        if (reports.isEmpty()) {
            textResult(
                "No crash or ANR found for $packageName on $serial. The log buffers may have been cleared or " +
                    "rotated; reproduce the crash and call this again, or check get_logcat with level E.",
            )
        } else {
            textResult(formatCrashReports(packageName, reports))
        }
    }
}

internal fun formatCrashReports(packageName: String, reports: List<CrashReport>): String = buildString {
    append("Most recent problems for ").append(packageName).append(", newest first:")
    reports.forEach { report ->
        appendLine().appendLine()
        append("## ").append(report.kind.label).append(" at ").appendLine(report.timestamp)
        append(report.text)
    }
}
