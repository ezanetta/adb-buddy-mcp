package io.github.ezanetta.adbbuddy

import io.github.ezanetta.adbbuddy.adb.AdbClient
import io.github.ezanetta.adbbuddy.tools.registerDeviceTools
import io.github.ezanetta.adbbuddy.tools.registerLogTools
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.util.Properties

const val SERVER_NAME = "adb-buddy"

val serverVersion: String by lazy {
    Properties().apply {
        AdbClient::class.java.getResourceAsStream("/adb-buddy.properties")?.use { load(it) }
    }.getProperty("version", "dev")
}

private val INSTRUCTIONS = """
    adb-buddy inspects and drives Android devices and emulators over adb.
    Start with list_devices. When several devices are connected, pass `serial` to every tool.
    To see the screen, prefer dump_ui_hierarchy (cheap, gives tap coordinates) and use take_screenshot
    when visual detail matters. Tools that change device state are annotated as non read-only.
""".trimIndent()

fun createServer(adb: AdbClient): Server = Server(
    serverInfo = Implementation(name = SERVER_NAME, version = serverVersion),
    options = ServerOptions(
        capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = false),
            prompts = ServerCapabilities.Prompts(listChanged = false),
        ),
    ),
    instructions = INSTRUCTIONS,
) {
    registerDeviceTools(adb)
    registerLogTools(adb)
}
