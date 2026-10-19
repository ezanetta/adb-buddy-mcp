package io.github.ezanetta.adbbuddy.parse

import io.github.ezanetta.adbbuddy.testutil.fixture
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.maps.shouldNotContainKey
import org.junit.jupiter.api.Test

class GetpropParserTest {
    @Test
    fun `parses bracketed key value pairs and ignores malformed lines`() {
        val props = GetpropParser.parse(fixture("getprop_emulator.txt"))
        props.shouldContain("ro.product.model", "sdk_gphone16k_arm64")
        props.shouldContain("ro.build.version.release", "17")
        props.shouldContain("ro.kernel.qemu", "1")
        props.shouldNotContainKey("ro.multi.line")
    }
}
