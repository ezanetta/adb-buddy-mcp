package io.github.ezanetta.adbbuddy.tools

import io.github.ezanetta.adbbuddy.adb.ProcessResult
import io.github.ezanetta.adbbuddy.image.PngScaler
import io.github.ezanetta.adbbuddy.testutil.FakeProcessRunner
import io.github.ezanetta.adbbuddy.testutil.ONE_EMULATOR
import io.github.ezanetta.adbbuddy.testutil.fixture
import io.github.ezanetta.adbbuddy.testutil.text
import io.github.ezanetta.adbbuddy.testutil.withMcpClient
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64

class ScreenToolsTest {
    private val runner = FakeProcessRunner().onDevices(ONE_EMULATOR)

    @Test
    fun `screenshot is downscaled and reports the scale`() = withMcpClient(runner) { client ->
        runner.onArgs("screencap", "-p") { ProcessResult(0, png(1080, 2424), "") }

        val result = client.callTool("take_screenshot", emptyMap())

        val image = result.content.filterIsInstance<ImageContent>().single()
        image.mimeType shouldBe "image/png"
        val decoded = ImageIO.read(ByteArrayInputStream(Base64.decode(image.data)))
        (decoded.width to decoded.height) shouldBe (570 to 1280)
        result.text shouldContain
            "Screenshot of emulator-5554: 570x1280 (device 1080x2424; multiply image coordinates by 1.8938"
        runner.calls.last() shouldBe listOf("-s", "emulator-5554", "exec-out", "screencap", "-p")
    }

    @Test
    fun `max_size 0 keeps the original`() = withMcpClient(runner) { client ->
        val original = png(300, 600)
        runner.onArgs("screencap", "-p") { ProcessResult(0, original, "") }

        val result = client.callTool("take_screenshot", mapOf("max_size" to 0))

        Base64.decode(result.content.filterIsInstance<ImageContent>().single().data).contentEquals(original) shouldBe
            true
        result.text shouldBe "Screenshot of emulator-5554: 300x600."
    }

    @Test
    fun `non png output from screencap is an error`() = withMcpClient(runner) { client ->
        runner.onArgs("screencap", "-p", stdout = "Error: could not take screenshot (secure layer)")

        val result = client.callTool("take_screenshot", emptyMap())

        result.isError shouldBe true
        result.text shouldContain "secure layer"
    }

    @Test
    fun `dump_ui_hierarchy dumps, reads and cleans up`() = withMcpClient(runner) { client ->
        runner.onArgs(
            "'dump'",
            "'/data/local/tmp/adb-buddy-ui.xml'",
            stdout = "UI hierchary dumped to: /data/local/tmp/adb-buddy-ui.xml\n",
        )
        runner.onArgs("cat", "/data/local/tmp/adb-buddy-ui.xml", stdout = fixture("ui_settings.xml"))
        runner.onArgs("'rm'", "'-f'", "'/data/local/tmp/adb-buddy-ui.xml'")

        val result = client.callTool("dump_ui_hierarchy", emptyMap())

        result.text shouldStartWith "Screen 1080x2424, rotation 0, app com.android.settings."
        result.text shouldContain "\"Network & internet | Mobile, Wi‑Fi, hotspot\" clickable (540,719)"
        runner.calls.last() shouldBe
            listOf("-s", "emulator-5554", "shell", "'rm'", "'-f'", "'/data/local/tmp/adb-buddy-ui.xml'")
    }

    @Test
    fun `uiautomator failures are explained`() = withMcpClient(runner) { client ->
        runner.onArgs("'dump'", "'/data/local/tmp/adb-buddy-ui.xml'", stdout = "ERROR: could not get idle state.\n")

        val result = client.callTool("dump_ui_hierarchy", emptyMap())

        result.isError shouldBe true
        result.text shouldContain "could not get idle state"
        result.text shouldContain "retry"
    }

    @Test
    fun `scaler leaves small images untouched`() {
        val small = png(100, 200)
        PngScaler.fit(small, 1280).png.contentEquals(small) shouldBe true
    }

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()
}
