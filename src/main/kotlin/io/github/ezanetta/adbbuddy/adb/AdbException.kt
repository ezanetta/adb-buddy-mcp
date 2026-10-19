package io.github.ezanetta.adbbuddy.adb

import kotlin.time.Duration

/**
 * Every failure the server can report to a client. Tool handlers catch these and
 * turn them into `isError` results, so the message must be useful to a model.
 */
sealed class AdbException(message: String) : Exception(message) {
    class AdbNotFound(searched: List<String>) :
        AdbException(
            "adb executable not found. Looked in: ${searched.joinToString()}. " +
                "Install Android SDK platform-tools and set ANDROID_HOME, or put adb on PATH.",
        )

    class NoDevices :
        AdbException(
            "No devices connected. Start an emulator or connect a device with USB debugging enabled.",
        )

    class MultipleDevices(serials: List<String>) :
        AdbException(
            "More than one device is connected. Pass `serial` to choose one of: ${serials.joinToString()}.",
        )

    class DeviceNotFound(serial: String, available: List<String>) :
        AdbException(
            "Device '$serial' not found. Connected devices: " +
                (available.takeIf { it.isNotEmpty() }?.joinToString() ?: "none") + ".",
        )

    class DeviceNotReady(serial: String, state: String) :
        AdbException(
            "Device '$serial' is $state." + when (state) {
                "unauthorized" -> " Accept the USB debugging prompt on the device."
                "offline" -> " Reconnect it or restart the emulator."
                else -> ""
            },
        )

    class CommandFailed(description: String, exitCode: Int, detail: String) :
        AdbException(
            "`$description` failed (exit $exitCode)" + if (detail.isBlank()) "." else ": ${detail.trim().take(500)}",
        )

    class Timeout(description: String, timeout: Duration) :
        AdbException(
            "`$description` timed out after $timeout.",
        )

    class InvalidArgument(message: String) : AdbException(message)

    class NotFound(message: String) : AdbException(message)
}
