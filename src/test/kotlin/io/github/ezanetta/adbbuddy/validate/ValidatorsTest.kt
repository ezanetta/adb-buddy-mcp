package io.github.ezanetta.adbbuddy.validate

import io.github.ezanetta.adbbuddy.adb.AdbException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ValidatorsTest {
    @ParameterizedTest
    @ValueSource(strings = ["com.example.app", "com.example.app_2", "a.b", "com.Example.App"])
    fun `accepts valid package names`(name: String) {
        Validators.packageName(name) shouldBe name
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "com", "com.", ".com.app", "1com.app", "com.1app", "com.app; reboot", "com.app\$(id)",
            "com.app && rm -rf /", "com.app'", "com app", "com.app\nreboot", "-p com.app",
        ],
    )
    fun `rejects invalid or malicious package names`(name: String) {
        shouldThrow<AdbException.InvalidArgument> { Validators.packageName(name) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["OkHttp", "Finsky:background", "my tag", "ActivityManager", "chromium/net"])
    fun `accepts log tags`(tag: String) {
        Validators.logTag(tag) shouldBe tag
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " leading", "tag;reboot", "tag`id`", "tag\nx", "*"])
    fun `rejects bad log tags`(tag: String) {
        shouldThrow<AdbException.InvalidArgument> { Validators.logTag(tag) }
    }
}
