package io.github.ezanetta.adbbuddy.parse

import io.github.ezanetta.adbbuddy.testutil.fixture
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LogcatTest {
    private val lines = LogcatParser.parse(fixture("logcat_main.txt"))

    @Test
    fun `parses threadtime lines and skips buffer markers and garbage`() {
        lines shouldHaveSize 8
        lines[0].tag shouldBe "Finsky:background"
        lines[0].message shouldBe "[55] RM: remove resource_request=c17d8109"
        lines[1].pid shouldBe 6123
        lines[1].level shouldBe 'D'
        lines[1].tag shouldBe "OkHttp"
    }

    @Test
    fun `filters by exact tag`() {
        LogcatFilter.filter(lines, tag = "OkHttp", minLevel = null, limit = 100)
            .map { it.message } shouldBe listOf(
            "--> GET https://api.example.com/profile",
            "<-- 500 Internal Server Error (120ms)",
            "verbose detail",
        )
    }

    @Test
    fun `filters by minimum level`() {
        LogcatFilter.filter(lines, tag = null, minLevel = LogLevel.WARN, limit = 100)
            .map { it.level } shouldBe listOf('W', 'E', 'F')
    }

    @Test
    fun `combines filters and keeps the newest lines`() {
        LogcatFilter.filter(lines, tag = "OkHttp", minLevel = LogLevel.DEBUG, limit = 1)
            .single().message shouldBe "<-- 500 Internal Server Error (120ms)"
    }

    @Test
    fun `level parsing accepts letters and names`() {
        LogLevel.parse("w") shouldBe LogLevel.WARN
        LogLevel.parse("Warning") shouldBe LogLevel.WARN
        LogLevel.parse("ERROR") shouldBe LogLevel.ERROR
        LogLevel.parse("X") shouldBe null
    }
}
