package io.github.ezanetta.adbbuddy.validate

import io.github.ezanetta.adbbuddy.adb.AdbException
import io.github.ezanetta.adbbuddy.parse.DisplaySize
import io.github.ezanetta.adbbuddy.parse.DisplaySizeParser
import io.github.ezanetta.adbbuddy.testutil.fixture
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class InputValidatorsTest {
    @Test
    fun `input text only rewrites spaces, shell metacharacters stay literal`() {
        Validators.inputText("hello world") shouldBe "hello%sworld"
        Validators.inputText("it's \"q\" \$(id);`x`&|<>*\\~") shouldBe "it's%s\"q\"%s\$(id);`x`&|<>*\\~"
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "line\nbreak", "tab\there", "café", "emoji 🙂", "100%s"])
    fun `input text rejects what adb cannot type`(text: String) {
        shouldThrow<AdbException.InvalidArgument> { Validators.inputText(text) }
    }

    @Test
    fun `input text explains newlines and length`() {
        shouldThrow<AdbException.InvalidArgument> { Validators.inputText("a\nb") }.message shouldContain
            "press_key ENTER"
        shouldThrow<AdbException.InvalidArgument> { Validators.inputText("a".repeat(1001)) }.message shouldContain
            "1000"
    }

    @Test
    fun `keys come from an allowlist`() {
        Validators.keyCode("BACK") shouldBe "KEYCODE_BACK"
        Validators.keyCode("volume_down") shouldBe "KEYCODE_VOLUME_DOWN"
        Validators.keyCode("KEYCODE_HOME") shouldBe "KEYCODE_HOME"
        shouldThrow<AdbException.InvalidArgument> { Validators.keyCode("POWER") }.message shouldContain "Allowed: BACK"
        shouldThrow<AdbException.InvalidArgument> { Validators.keyCode("26") }
    }

    @Test
    fun `coordinates must be on screen`() {
        shouldNotThrowAny { Validators.coordinates(0, 0, 1080, 2424) }
        shouldNotThrowAny { Validators.coordinates(1079, 2423, 1080, 2424) }
        shouldThrow<AdbException.InvalidArgument> { Validators.coordinates(1080, 10, 1080, 2424) }
            .message shouldBe "Point (1080,10) is outside the screen (1080x2424, valid x 0..1079, y 0..2423)."
        shouldThrow<AdbException.InvalidArgument> { Validators.coordinates(10, -1, 1080, 2424) }
    }

    @Test
    fun `display size prefers current size, then override, then physical`() {
        DisplaySizeParser.fromDumpsys(fixture("dumpsys_window_displays.txt")) shouldBe DisplaySize(1080, 2424)
        DisplaySizeParser.fromDumpsys("    init=1080x2424 420dpi cur=2424x1080 app=2424x1080") shouldBe
            DisplaySize(2424, 1080)
        DisplaySizeParser.fromWmSize("Physical size: 1080x2424\nOverride size: 1000x2000\n") shouldBe
            DisplaySize(1000, 2000)
        DisplaySizeParser.fromWmSize("Physical size: 1080x2424\n") shouldBe DisplaySize(1080, 2424)
        DisplaySizeParser.fromDumpsys("nothing here") shouldBe null
    }
}
