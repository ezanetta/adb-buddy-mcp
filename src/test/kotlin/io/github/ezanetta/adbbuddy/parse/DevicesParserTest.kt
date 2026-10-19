package io.github.ezanetta.adbbuddy.parse

import io.github.ezanetta.adbbuddy.testutil.fixture
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DevicesParserTest {
    @Test
    fun `parses every state and skips daemon noise`() {
        val devices = DevicesParser.parse(fixture("devices_mixed.txt"))

        devices.map { it.serial to it.state } shouldBe listOf(
            "emulator-5554" to "device",
            "R5CR10ABCDE" to "unauthorized",
            "192.168.1.20:5555" to "offline",
            "0123456789ABCDEF" to "no permissions",
            "2B171FDH3002VX" to "device",
        )
    }

    @Test
    fun `reads key value properties and flags emulators`() {
        val devices = DevicesParser.parse(fixture("devices_mixed.txt"))

        devices[0].properties["model"] shouldBe "sdk_gphone64_arm64"
        devices[0].isEmulator shouldBe true
        devices[4].properties["model"] shouldBe "Pixel_8_Pro"
        devices[4].isEmulator shouldBe false
        devices.filter { it.isReady } shouldHaveSize 2
    }

    @Test
    fun `empty list`() {
        DevicesParser.parse(fixture("devices_empty.txt")).shouldBeEmpty()
    }
}
