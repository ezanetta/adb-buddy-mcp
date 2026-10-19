package io.github.ezanetta.adbbuddy.parse

data class CrashReport(val kind: Kind, val timestamp: String, val lines: List<String>) {
    enum class Kind(val label: String) {
        JAVA_CRASH("Java crash (FATAL EXCEPTION)"),
        NATIVE_CRASH("Native crash (tombstone)"),
        ANR("ANR"),
    }

    val text: String get() = lines.joinToString("\n")
}

/**
 * Finds the most recent crash blocks for a package in threadtime-formatted logcat output.
 * A block is the run of lines from the same pid, tid and tag that follows its header line; the tid matters
 * because system_server logs everything (including unrelated ActivityManager lines) from one pid.
 */
object CrashLogExtractor {
    private const val MAX_BLOCK_LINES = 120
    private const val MAX_INTERLEAVED_LINES = 20

    private val javaCrashHeader = { line: LogLine ->
        line.tag == "AndroidRuntime" && line.message.startsWith("FATAL EXCEPTION")
    }
    private val nativeCrashHeader = { line: LogLine ->
        line.tag == "DEBUG" && line.message.startsWith("*** *** ***")
    }
    private val anrHeader = { line: LogLine ->
        line.tag == "ActivityManager" && line.message.startsWith("ANR in ")
    }

    fun lastJavaCrash(log: String, packageName: String): CrashReport? = blocks(LogcatParser.parse(log), javaCrashHeader)
        .lastOrNull { block -> block.any { processMatches(it.message.substringAfter("Process: ", ""), packageName) } }
        ?.toReport(CrashReport.Kind.JAVA_CRASH)

    fun lastNativeCrash(log: String, packageName: String): CrashReport? =
        blocks(LogcatParser.parse(log), nativeCrashHeader)
            .lastOrNull { block ->
                // pid: 4321, tid: 4321, name: main  >>> com.example.app <<<
                block.any { processMatches(it.message.substringAfter(">>> ", "").substringBefore(" <<<"), packageName) }
            }
            ?.toReport(CrashReport.Kind.NATIVE_CRASH)

    fun lastAnr(log: String, packageName: String): CrashReport? = blocks(LogcatParser.parse(log), anrHeader)
        .lastOrNull { block ->
            processMatches(block.first().message.removePrefix("ANR in ").substringBefore(' '), packageName)
        }
        ?.toReport(CrashReport.Kind.ANR)

    private fun List<LogLine>.toReport(kind: CrashReport.Kind) = CrashReport(
        kind,
        first().timestamp,
        map {
            it.message
        },
    )

    /** Matches `com.app` and its secondary processes such as `com.app:remote`. */
    private fun processMatches(processName: String, packageName: String): Boolean {
        val name = processName.substringBefore(',').trim()
        return name == packageName || name.startsWith("$packageName:")
    }

    private fun blocks(lines: List<LogLine>, startsWith: (LogLine) -> Boolean): List<List<LogLine>> {
        val result = mutableListOf<List<LogLine>>()
        var i = 0
        while (i < lines.size) {
            val head = lines[i]
            if (!startsWith(head)) {
                i++
                continue
            }
            val block = mutableListOf(head)
            var j = i + 1
            // Other processes interleave their output, so tolerate a run of foreign lines before ending the block.
            var foreignRun = 0
            while (j < lines.size && block.size < MAX_BLOCK_LINES && foreignRun <= MAX_INTERLEAVED_LINES) {
                val line = lines[j]
                if (startsWith(line)) break
                if (line.pid == head.pid && line.tid == head.tid && line.tag == head.tag) {
                    block += line
                    foreignRun = 0
                } else {
                    foreignRun++
                }
                j++
            }
            result += block
            i = j
        }
        return result
    }
}
