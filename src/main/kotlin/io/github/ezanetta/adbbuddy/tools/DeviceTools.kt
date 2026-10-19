package io.github.ezanetta.adbbuddy.tools

import io.github.ezanetta.adbbuddy.adb.AdbClient
import io.github.ezanetta.adbbuddy.adb.AdbException
import io.github.ezanetta.adbbuddy.adb.DeviceInfo
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerDeviceTools(adb: AdbClient) {
    adbTool(
        name = "list_devices",
        description = "List connected Android devices and emulators with serial, model, Android version " +
            "and whether each is an emulator or a physical device. Use a serial from here as `serial` " +
            "in other tools when more than one device is connected.",
        annotations = Annotations.readOnly("List devices"),
        schema = schema(includeSerial = false),
    ) {
        val devices = adb.devices().map { entry ->
            if (entry.isReady) {
                try {
                    adb.deviceInfo(entry)
                } catch (e: AdbException) {
                    DeviceInfo(entry.serial, "device (getprop failed: ${e.message})", isEmulator = entry.isEmulator)
                }
            } else {
                DeviceInfo(entry.serial, entry.state, model = entry.properties["model"], isEmulator = entry.isEmulator)
            }
        }
        textResult(formatDevices(devices))
    }
}

internal fun formatDevices(devices: List<DeviceInfo>): String {
    if (devices.isEmpty()) {
        return "No devices connected. Start an emulator or connect a device with USB debugging enabled."
    }
    return buildString {
        appendLine("${devices.size} device(s):")
        devices.forEach { d ->
            append("- ").append(d.serial)
            append(" | ").append(listOfNotNull(d.manufacturer, d.model).joinToString(" ").ifEmpty { "unknown model" })
            if (d.androidVersion != null) {
                append(" | Android ").append(d.androidVersion)
                d.sdk?.let { append(" (API ").append(it).append(')') }
            }
            append(" | ").append(if (d.isEmulator) "emulator" else "physical")
            if (d.state != "device") append(" | state: ").append(d.state)
            appendLine()
        }
    }.trimEnd()
}
