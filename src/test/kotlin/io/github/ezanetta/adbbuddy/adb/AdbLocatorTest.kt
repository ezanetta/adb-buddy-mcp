package io.github.ezanetta.adbbuddy.adb

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

class AdbLocatorTest {
    private fun locator(env: Map<String, String>, existing: Set<String>) =
        AdbLocator(env = env, isWindows = false, isExecutable = { it.path in existing })

    @Test
    fun `prefers ANDROID_HOME`() {
        val adb = locator(
            env = mapOf("ANDROID_HOME" to "/sdk", "ANDROID_SDK_ROOT" to "/old", "PATH" to "/usr/bin"),
            existing = setOf("/sdk/platform-tools/adb", "/old/platform-tools/adb", "/usr/bin/adb"),
        ).locate()
        adb shouldBe "/sdk/platform-tools/adb"
    }

    @Test
    fun `falls back to ANDROID_SDK_ROOT then PATH`() {
        locator(mapOf("ANDROID_HOME" to "/missing", "ANDROID_SDK_ROOT" to "/old"), setOf("/old/platform-tools/adb"))
            .locate() shouldBe "/old/platform-tools/adb"
        locator(mapOf("PATH" to listOf("/a", "/b").joinToString(File.pathSeparator)), setOf("/b/adb"))
            .locate() shouldBe "/b/adb"
    }

    @Test
    fun `clear error when missing`() {
        shouldThrow<AdbException.AdbNotFound> { locator(emptyMap(), emptySet()).locate() }
            .message shouldContain "ANDROID_HOME"
    }
}
