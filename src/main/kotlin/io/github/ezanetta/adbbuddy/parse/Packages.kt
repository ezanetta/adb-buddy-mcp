package io.github.ezanetta.adbbuddy.parse

object PackagesParser {
    /** Parses `pm list packages` output into sorted package names. */
    fun parse(output: String): List<String> = output.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").substringBefore(' ') }
        .filter { it.isNotEmpty() }
        .sorted()
        .toList()
}

data class Component(val packageName: String, val className: String) {
    /** `com.app/.Main` becomes `com.app/com.app.Main`. */
    val fullClassName: String get() = if (className.startsWith(".")) packageName + className else className
    val flattened: String get() = "$packageName/$className"

    companion object {
        private val pattern = Regex("""^([A-Za-z][\w.]*)/([\w.$]+)$""")

        fun parse(value: String): Component? =
            pattern.matchEntire(value.trim())?.let { Component(it.groupValues[1], it.groupValues[2]) }
    }
}

object ActivityParser {
    // topResumedActivity=ActivityRecord{247049992 u0 com.android.settings/.homepage.SettingsHomepageActivity t26}
    // mResumedActivity: ActivityRecord{e0b5f4c u0 com.example.app/com.example.app.ui.MainActivity t12}
    private val resumed = Regex(
        """(?:topResumedActivity=|mResumedActivity: |ResumedActivity: |mFocusedActivity: )""" +
            """ActivityRecord\{\S+ u\d+ (\S+/\S+)""",
    )

    /** The foreground activity from `dumpsys activity activities`, across Android versions. */
    fun resumedActivity(dumpsys: String): Component? =
        resumed.findAll(dumpsys).firstNotNullOfOrNull { Component.parse(it.groupValues[1]) }

    /** The last line of `cmd package resolve-activity --brief`, e.g. `com.app/.MainActivity`. */
    fun resolvedActivity(output: String): Component? =
        output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.let(Component::parse)
}
