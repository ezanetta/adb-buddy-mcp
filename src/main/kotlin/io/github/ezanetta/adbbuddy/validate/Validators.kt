package io.github.ezanetta.adbbuddy.validate

import io.github.ezanetta.adbbuddy.adb.AdbException
import java.io.File

/**
 * Input validation for anything that ends up in an adb command line. Arguments are
 * shell-quoted anyway; these checks exist so bad input fails early with a clear message.
 */
object Validators {
    private val packageName = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val logTag = Regex("^[\\w.:/$-][\\w .:/$-]{0,126}$")

    fun packageName(value: String): String {
        if (value.length > 255 || !packageName.matches(value)) {
            throw AdbException.InvalidArgument(
                "Invalid package name '$value'. Expected something like com.example.app.",
            )
        }
        return value
    }

    fun logTag(value: String): String {
        if (!logTag.matches(value)) {
            throw AdbException.InvalidArgument(
                "Invalid log tag '$value'. Use letters, digits, spaces and . _ : / \$ - (max 127 chars).",
            )
        }
        return value
    }

    const val MAX_INPUT_TEXT = 1000

    /**
     * Encodes text for `adb shell input text`. The device shell already receives it single-quoted,
     * so the only transformation left is `input`'s own: a space must be sent as `%s`.
     * `input text` can only type printable ASCII, so anything else is rejected up front.
     */
    fun inputText(value: String): String {
        if (value.isEmpty()) throw AdbException.InvalidArgument("`text` must not be empty.")
        if (value.length > MAX_INPUT_TEXT) {
            throw AdbException.InvalidArgument("`text` is limited to $MAX_INPUT_TEXT characters.")
        }
        value.firstOrNull { it !in ' '..'~' }?.let { bad ->
            val hint = if (bad == '\n' || bad == '\r') " Use press_key ENTER for new lines." else ""
            throw AdbException.InvalidArgument(
                "`text` contains an unsupported character (U+%04X). adb input text only types printable ASCII.$hint"
                    .format(bad.code),
            )
        }
        if ("%s" in value) {
            throw AdbException.InvalidArgument(
                "`text` cannot contain the sequence %s: adb input text turns it into a space.",
            )
        }
        return value.replace(" ", "%s")
    }

    /** Hardware keys the model may press, mapped to Android key event names. */
    val keys: Map<String, String> = linkedMapOf(
        "BACK" to "KEYCODE_BACK",
        "HOME" to "KEYCODE_HOME",
        "ENTER" to "KEYCODE_ENTER",
        "APP_SWITCH" to "KEYCODE_APP_SWITCH",
        "VOLUME_UP" to "KEYCODE_VOLUME_UP",
        "VOLUME_DOWN" to "KEYCODE_VOLUME_DOWN",
    )

    fun keyCode(value: String): String = keys[value.trim().uppercase().removePrefix("KEYCODE_")]
        ?: throw AdbException.InvalidArgument("Unsupported key '$value'. Allowed: ${keys.keys.joinToString()}.")

    fun coordinates(x: Int, y: Int, width: Int, height: Int, label: String = "Point") {
        if (x !in 0 until width || y !in 0 until height) {
            throw AdbException.InvalidArgument(
                "$label ($x,$y) is outside the screen (${width}x$height, valid x 0..${width - 1}, y 0..${height - 1}).",
            )
        }
    }

    /** Resolves [path] (absolute, relative to the working directory, or `~/...`) to a readable .apk file. */
    fun apkFile(path: String, workingDir: File = File(System.getProperty("user.dir"))): File {
        val expanded = if (path.startsWith("~/")) System.getProperty("user.home") + path.substring(1) else path
        val file = File(expanded).let { if (it.isAbsolute) it else File(workingDir, expanded) }.normalize()
        when {
            !file.exists() -> throw AdbException.NotFound("No file at ${file.path}.")
            !file.isFile -> throw AdbException.InvalidArgument("${file.path} is not a file.")
            !file.name.endsWith(".apk", ignoreCase = true) ->
                throw AdbException.InvalidArgument("${file.name} is not an .apk file.")
            !file.canRead() -> throw AdbException.InvalidArgument("${file.path} is not readable.")
        }
        val magic = file.inputStream().use { it.readNBytes(4) }
        if (!magic.contentEquals(ZIP_MAGIC)) {
            throw AdbException.InvalidArgument("${file.name} is not a valid APK (not a zip archive).")
        }
        return file
    }

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
}
