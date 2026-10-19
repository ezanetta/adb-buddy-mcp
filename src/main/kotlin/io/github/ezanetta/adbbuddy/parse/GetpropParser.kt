package io.github.ezanetta.adbbuddy.parse

/** Parses `getprop` output lines of the form `[key]: [value]`. */
object GetpropParser {
    private val line = Regex("""^\[([^\]]+)]: \[(.*)]$""")

    fun parse(output: String): Map<String, String> = output.lineSequence()
        .mapNotNull { line.matchEntire(it.trimEnd()) }
        .associate { it.groupValues[1] to it.groupValues[2] }
}
