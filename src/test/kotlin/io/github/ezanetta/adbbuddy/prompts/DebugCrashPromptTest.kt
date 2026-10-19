package io.github.ezanetta.adbbuddy.prompts

import io.github.ezanetta.adbbuddy.testutil.FakeProcessRunner
import io.github.ezanetta.adbbuddy.testutil.withMcpClient
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.jupiter.api.Test

class DebugCrashPromptTest {
    private val GetPromptResult.text get() = (messages.single().content as TextContent).text

    private fun request(vararg args: Pair<String, String>) =
        GetPromptRequest(GetPromptRequestParams(name = "debug_crash", arguments = mapOf(*args)))

    @Test
    fun `is listed with a required package argument`() = withMcpClient(FakeProcessRunner()) { client ->
        val prompt = client.listPrompts().prompts.single()
        prompt.name shouldBe "debug_crash"
        prompt.arguments.orEmpty().associate { it.name to it.required } shouldBe
            mapOf("package" to true, "serial" to null)
    }

    @Test
    fun `guides the model through crash log, screenshot and hierarchy`() =
        withMcpClient(FakeProcessRunner()) { client ->
            val result = client.getPrompt(request("package" to "com.example.app"))

            val message = result.messages.single()
            message.role shouldBe Role.User
            val text = result.text
            text shouldContain "`get_crash_log` with package `com.example.app`"
            text shouldContain "`take_screenshot`"
            text shouldContain "`dump_ui_hierarchy`"
            text shouldContain "Probable cause"
            text shouldNotContain "serial"
        }

    @Test
    fun `mentions the serial when given`() = withMcpClient(FakeProcessRunner()) { client ->
        val result = client.getPrompt(request("package" to "com.example.app", "serial" to "emulator-5554"))
        result.text shouldContain "on device `emulator-5554`"
    }

    @Test
    fun `rejects invalid package names as invalid params`() = withMcpClient(FakeProcessRunner()) { client ->
        val error = shouldThrow<McpException> { client.getPrompt(request("package" to "x; reboot")) }
        error.code shouldBe RPCError.ErrorCode.INVALID_PARAMS
    }
}
