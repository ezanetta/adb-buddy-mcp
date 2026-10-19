package io.github.ezanetta.adbbuddy.parse

data class DisplaySize(val width: Int, val height: Int)

object DisplaySizeParser {
    private val current = Regex("""\bcur=(\d+)x(\d+)""")
    private val override = Regex("""Override size: (\d+)x(\d+)""")
    private val physical = Regex("""Physical size: (\d+)x(\d+)""")

    /** Current logical size from `dumpsys window displays`: follows rotation and `wm size` overrides. */
    fun fromDumpsys(output: String): DisplaySize? = current.find(output)?.toSize()

    /** Fallback from `wm size`: the override wins over the physical size. Not rotation aware. */
    fun fromWmSize(output: String): DisplaySize? = (override.find(output) ?: physical.find(output))?.toSize()

    private fun MatchResult.toSize() = DisplaySize(groupValues[1].toInt(), groupValues[2].toInt())
}
