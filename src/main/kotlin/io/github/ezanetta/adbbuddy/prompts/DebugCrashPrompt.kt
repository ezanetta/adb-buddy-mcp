package io.github.ezanetta.adbbuddy.prompts

import io.github.ezanetta.adbbuddy.adb.AdbException
import io.github.ezanetta.adbbuddy.validate.Validators
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

fun Server.registerDebugCrashPrompt() {
    addPrompt(
        name = "debug_crash",
        description = "Investigate why an Android app crashed: collect the crash log, a screenshot and the UI " +
            "hierarchy, then explain the probable cause and suggest a fix.",
        arguments = listOf(
            PromptArgument(name = "package", description = "App package name, e.g. com.example.app", required = true),
            PromptArgument(name = "serial", description = "Device serial, if more than one is connected"),
        ),
    ) { request ->
        val args = request.arguments.orEmpty()
        val packageName = try {
            Validators.packageName(args["package"]?.trim().orEmpty())
        } catch (e: AdbException.InvalidArgument) {
            throw McpException(RPCError.ErrorCode.INVALID_PARAMS, e.message ?: "Invalid package name")
        }
        val serial = args["serial"]?.trim()?.takeIf { it.isNotEmpty() }
        GetPromptResult(
            description = "Debug a crash in $packageName",
            messages = listOf(PromptMessage(Role.User, TextContent(debugCrashInstructions(packageName, serial)))),
        )
    }
}

internal fun debugCrashInstructions(packageName: String, serial: String?): String {
    val device = serial?.let { " on device `$it` (pass `serial: \"$it\"` to every tool)" } ?: ""
    return """
        |The Android app `$packageName` crashed$device. Investigate it with the adb-buddy tools:
        |
        |1. Call `get_crash_log` with package `$packageName` to get the latest Java crash, native crash or ANR.
        |   If nothing is found, call `get_logcat` with level `E` and lines 300 and look for errors from the app.
        |2. Call `take_screenshot` to see what is on screen now (an error dialog, the launcher, or the app).
        |3. Call `dump_ui_hierarchy` to see the screen's elements and which app is in the foreground.
        |
        |Then explain, concisely:
        |- **What happened**: the exception or signal and the line in the app's own code where it was thrown
        |  (the first stack frame in `$packageName`, skipping framework frames).
        |- **Probable cause**: why that code failed, citing the evidence from the log.
        |- **Suggested fix**: a concrete code change, and how to confirm it, e.g. relaunch with `launch_app`
        |  and repeat the steps that triggered the crash.
        |
        |Do not change device state while gathering evidence: no `clear_app_data`, `install_apk` or input
        |actions unless I ask for them.
    """.trimMargin()
}
