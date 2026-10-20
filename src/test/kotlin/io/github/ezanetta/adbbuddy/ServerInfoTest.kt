package io.github.ezanetta.adbbuddy

import io.github.ezanetta.adbbuddy.testutil.FakeProcessRunner
import io.github.ezanetta.adbbuddy.testutil.withMcpClient
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

class ServerInfoTest {
    @Test
    fun `server info carries the build version`() = withMcpClient(FakeProcessRunner()) { client ->
        val info = client.serverVersion!!
        info.name shouldBe "adb-buddy"
        info.version shouldBe serverVersion
        // Fails if the resource was not expanded at build time ("${'$'}{version}") or is missing ("dev").
        info.version shouldMatch Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?""")
    }
}
