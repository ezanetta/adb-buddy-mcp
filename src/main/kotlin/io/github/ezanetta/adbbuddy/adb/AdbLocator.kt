package io.github.ezanetta.adbbuddy.adb

import java.io.File

/** Finds the adb executable: ANDROID_HOME, then ANDROID_SDK_ROOT, then PATH. */
class AdbLocator(
    private val env: Map<String, String> = System.getenv(),
    private val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    private val isExecutable: (File) -> Boolean = { it.isFile && it.canExecute() },
) {
    fun locate(): String {
        val exe = if (isWindows) "adb.exe" else "adb"
        val candidates = buildList {
            listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { key ->
                env[key]?.takeIf { it.isNotBlank() }?.let { add(File(File(it, "platform-tools"), exe)) }
            }
            env["PATH"].orEmpty().split(File.pathSeparatorChar)
                .filter { it.isNotBlank() }
                .forEach { add(File(it, exe)) }
        }
        return candidates.firstOrNull(isExecutable)?.path
            ?: throw AdbException.AdbNotFound(
                listOf("\$ANDROID_HOME/platform-tools", "\$ANDROID_SDK_ROOT/platform-tools", "PATH"),
            )
    }
}
