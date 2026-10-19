package io.github.ezanetta.adbbuddy.parse

/** One line of `logcat -v threadtime` output. */
data class LogLine(
    val timestamp: String,
    val pid: Int,
    val tid: Int,
    val level: Char,
    val tag: String,
    val message: String,
    val raw: String,
)

enum class LogLevel(val letter: Char) {
    VERBOSE('V'),
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E'),
    FATAL('F'),
    ;

    companion object {
        /** Accepts a letter (`W`) or a name (`warn`, `WARNING`). */
        fun parse(value: String): LogLevel? {
            val upper = value.trim().uppercase()
            return entries.firstOrNull {
                it.name == upper ||
                    it.letter.toString() == upper ||
                    (upper == "WARNING" && it == WARN)
            }
        }

        fun ofLetter(letter: Char): LogLevel? = entries.firstOrNull { it.letter == letter }
    }
}

object LogcatParser {
    // 10-19 13:13:49.384  5313  5385 I Finsky:background: message
    private val threadtime =
        Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.*?)\s*: (.*)$""")

    fun parseLine(line: String): LogLine? = threadtime.matchEntire(line)?.let { m ->
        val (ts, pid, tid, level, tag, message) = m.destructured
        LogLine(ts, pid.toInt(), tid.toInt(), level[0], tag, message, line)
    }

    fun parse(output: String): List<LogLine> = output.lineSequence().mapNotNull(::parseLine).toList()
}

/** Host-side logcat filtering, so `lines` counts matching lines rather than raw buffer lines. */
object LogcatFilter {
    fun filter(lines: List<LogLine>, tag: String?, minLevel: LogLevel?, limit: Int): List<LogLine> = lines
        .asSequence()
        .filter { tag == null || it.tag == tag }
        .filter { line -> minLevel == null || (LogLevel.ofLetter(line.level)?.let { it >= minLevel } ?: true) }
        .toList()
        .takeLast(limit)
}
