package io.github.ezanetta.adbbuddy.tools

import io.github.ezanetta.adbbuddy.testutil.FakeProcessRunner
import io.github.ezanetta.adbbuddy.testutil.ONE_EMULATOR
import io.github.ezanetta.adbbuddy.testutil.fixture
import io.github.ezanetta.adbbuddy.testutil.ok
import io.github.ezanetta.adbbuddy.testutil.text
import io.github.ezanetta.adbbuddy.testutil.withMcpClient
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class InputToolsTest {
    private val runner = FakeProcessRunner()
        .onDevices(ONE_EMULATOR)
        .onArgs("'dumpsys'", "'window'", "'displays'", stdout = fixture("dumpsys_window_displays.txt"))
        .on({ "'input'" in it }) { ok() }

    private val lastInput get() = runner.calls.last { "'input'" in it }.drop(3)

    @Test
    fun `tap sends quoted coordinates`() = withMcpClient(runner) { client ->
        val result = client.callTool("tap", mapOf("x" to 540, "y" to 719))

        result.text shouldContain "Tapped (540,719)"
        lastInput shouldBe listOf("'input'", "'tap'", "'540'", "'719'")
    }

    @Test
    fun `tap outside the screen is rejected before touching the device`() = withMcpClient(runner) { client ->
        val result = client.callTool("tap", mapOf("x" to 540, "y" to 3000))

        result.isError shouldBe true
        result.text shouldContain "outside the screen (1080x2424"
        runner.calls.flatten() shouldNotContain "'input'"
    }

    @Test
    fun `tap rejects fractional and missing coordinates`() = withMcpClient(runner) { client ->
        client.callTool("tap", mapOf("x" to 1.5, "y" to 2)).text shouldContain "`x` must be an integer"
        client.callTool("tap", mapOf("x" to 1)).text shouldContain "Missing required argument `y`"
    }

    @Test
    fun `swipe validates both ends and defaults the duration`() = withMcpClient(runner) { client ->
        client.callTool("swipe", mapOf("from_x" to 540, "from_y" to 2000, "to_x" to 540, "to_y" to 500))
        lastInput shouldBe listOf("'input'", "'swipe'", "'540'", "'2000'", "'540'", "'500'", "'300'")

        client.callTool("swipe", mapOf("from_x" to 540, "from_y" to 2000, "to_x" to 5000, "to_y" to 500))
            .text shouldContain "End point (5000,500) is outside the screen"
    }

    @Test
    fun `input_text encodes spaces and keeps metacharacters inside one quoted argument`() =
        withMcpClient(runner) { client ->
            client.callTool("input_text", mapOf("text" to "a b'; reboot"))

            lastInput shouldBe listOf("'input'", "'text'", "'a%sb'\\'';%sreboot'")
        }

    @Test
    fun `press_key maps the allowlist and rejects other keys`() = withMcpClient(runner) { client ->
        client.callTool("press_key", mapOf("key" to "back"))
        lastInput shouldBe listOf("'input'", "'keyevent'", "'KEYCODE_BACK'")

        val result = client.callTool("press_key", mapOf("key" to "POWER"))
        result.isError shouldBe true
        result.text shouldContain "Unsupported key 'POWER'"
    }

    @Test
    fun `input tools are annotated as non read only, non destructive actions`() = withMcpClient(runner) { client ->
        client.listTools().tools.filter { it.name in setOf("tap", "swipe", "input_text", "press_key") }.forEach {
            it.annotations?.readOnlyHint shouldBe false
            it.annotations?.destructiveHint shouldBe false
        }
    }
}
