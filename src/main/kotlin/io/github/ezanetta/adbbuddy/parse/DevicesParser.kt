package io.github.ezanetta.adbbuddy.parse

/** One line of `adb devices -l`. */
data class DeviceEntry(val serial: String, val state: String, val properties: Map<String, String> = emptyMap()) {
    val isReady: Boolean get() = state == "device"
    val isEmulator: Boolean get() = serial.startsWith("emulator-") || properties["product"]?.startsWith("sdk_") == true
}

object DevicesParser {
    private val whitespace = Regex("\\s+")
    private val property = Regex("([a-z_]+):(\\S+)")

    fun parse(output: String): List<DeviceEntry> = output.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
        .mapNotNull { line ->
            val parts = line.split(whitespace)
            if (parts.size < 2) return@mapNotNull null
            // "no permissions" is followed by a free-text explanation, so it gets special handling.
            val state = if (parts[1] == "no" && parts.getOrNull(2) == "permissions") "no permissions" else parts[1]
            val properties = parts.drop(2)
                .mapNotNull { property.matchEntire(it) }
                .associate { it.groupValues[1] to it.groupValues[2] }
            DeviceEntry(parts[0], state, properties)
        }
        .toList()
}
