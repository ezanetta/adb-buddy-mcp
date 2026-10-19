package io.github.ezanetta.adbbuddy.validate

import io.github.ezanetta.adbbuddy.adb.AdbException

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
}
