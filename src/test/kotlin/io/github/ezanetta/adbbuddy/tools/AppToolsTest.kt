package io.github.ezanetta.adbbuddy.tools

import io.github.ezanetta.adbbuddy.adb.ProcessResult
import io.github.ezanetta.adbbuddy.testutil.FakeProcessRunner
import io.github.ezanetta.adbbuddy.testutil.ONE_EMULATOR
import io.github.ezanetta.adbbuddy.testutil.fixture
import io.github.ezanetta.adbbuddy.testutil.text
import io.github.ezanetta.adbbuddy.testutil.withMcpClient
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AppToolsTest {
    private val runner = FakeProcessRunner()
        .onDevices(ONE_EMULATOR)
        .onArgs("'pm'", "'path'", "'com.example.app'", stdout = "package:/data/app/com.example.app-1/base.apk\n")
        .onArgs("'pm'", "'path'", "'com.missing.app'", exitCode = 1)

    @TempDir
    lateinit var tmp: File

    @Test
    fun `list_packages filters case-insensitively`() = withMcpClient(runner) { client ->
        runner.onArgs("'pm'", "'list'", "'packages'", "'-3'", stdout = fixture("pm_list_packages.txt"))

        client.callTool("list_packages", emptyMap()).text shouldBe
            "3 third-party packages on emulator-5554:\ncom.example.alpha\ncom.example.zeta\norg.mozilla.firefox"
        client.callTool("list_packages", mapOf("filter" to "MOZILLA")).text shouldBe
            "1 third-party packages matching 'MOZILLA' on emulator-5554:\norg.mozilla.firefox"
        client.callTool("list_packages", mapOf("filter" to "nope")).text shouldContain "No third-party packages"
    }

    @Test
    fun `get_current_activity expands the class name`() = withMcpClient(runner) { client ->
        runner.onArgs("'dumpsys'", "'activity'", "'activities'", stdout = fixture("dumpsys_activities_api37.txt"))

        client.callTool("get_current_activity", emptyMap()).text shouldBe "Foreground on emulator-5554: package " +
            "com.android.settings, activity com.android.settings.homepage.SettingsHomepageActivity"
    }

    @Test
    fun `launch_app resolves the launcher activity and starts it`() = withMcpClient(runner) { client ->
        runner.onArgs("'com.example.app'", stdout = "priority=0 isDefault=true\ncom.example.app/.MainActivity\n")
        runner.onArgs("'pm'", "'path'", "'com.example.app'", stdout = "package:/data/app/base.apk\n")
        runner.onArgs("'am'", "'start'", "'-n'", "'com.example.app/.MainActivity'", stdout = "Starting: Intent { }\n")

        val result = client.callTool("launch_app", mapOf("package" to "com.example.app"))

        result.isError shouldBe null
        result.text shouldContain "Launched com.example.app/.MainActivity"
    }

    @Test
    fun `launch_app reports missing apps and apps without a launcher`() = withMcpClient(runner) { client ->
        client.callTool("launch_app", mapOf("package" to "com.missing.app")).text shouldContain
            "com.missing.app is not installed"

        runner.onArgs("'com.example.app'", stdout = "No activity found\n")
        runner.onArgs("'pm'", "'path'", "'com.example.app'", stdout = "package:/data/app/base.apk\n")
        client.callTool("launch_app", mapOf("package" to "com.example.app")).text shouldContain "no launcher activity"
    }

    @Test
    fun `launch_app treats am errors as failures even with exit code 0`() = withMcpClient(runner) { client ->
        runner.onArgs("'com.example.app'", stdout = "com.example.app/.MainActivity\n")
        runner.onArgs("'pm'", "'path'", "'com.example.app'", stdout = "package:/data/app/base.apk\n")
        runner.onArgs("'am'", "'start'", "'-n'", "'com.example.app/.MainActivity'") {
            ProcessResult(0, "Starting: Intent\n".encodeToByteArray(), "Error: Activity class does not exist.")
        }

        val result = client.callTool("launch_app", mapOf("package" to "com.example.app"))

        result.isError shouldBe true
        result.text shouldContain "Activity class does not exist"
    }

    @Test
    fun `install_apk validates the file and runs adb install -r`() = withMcpClient(runner) { client ->
        val apk = File(tmp, "my app.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0)) }
        runner.onArgs("install", "-r", "-t", apk.absolutePath, stdout = "Performing Streamed Install\nSuccess\n")

        client.callTool("install_apk", mapOf("path" to apk.absolutePath)).text shouldBe
            "Installed my app.apk on emulator-5554."
        runner.calls.last() shouldBe listOf("-s", "emulator-5554", "install", "-r", "-t", apk.absolutePath)
    }

    @Test
    fun `install_apk rejects missing, non apk and non zip files without calling adb`() =
        withMcpClient(runner) { client ->
            val notApk = File(tmp, "notes.txt").apply { writeText("hi") }
            val fakeApk = File(tmp, "fake.apk").apply { writeText("not a zip") }

            client.callTool("install_apk", mapOf("path" to File(tmp, "missing.apk").path)).text shouldContain
                "No file at"
            client.callTool("install_apk", mapOf("path" to notApk.path)).text shouldContain "not an .apk file"
            client.callTool("install_apk", mapOf("path" to fakeApk.path)).text shouldContain "not a valid APK"
            client.callTool("install_apk", mapOf("path" to tmp.path)).text shouldContain "is not a file"
            runner.calls.map { it.getOrNull(2) } shouldNotContain "install"
        }

    @Test
    fun `install_apk surfaces adb failures`() = withMcpClient(runner) { client ->
        val apk = File(tmp, "app.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) }
        runner.onArgs(
            "install",
            "-r",
            "-t",
            apk.absolutePath,
            exitCode = 1,
            stderr = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]",
        )

        val result = client.callTool("install_apk", mapOf("path" to apk.path))

        result.isError shouldBe true
        result.text shouldContain "INSTALL_FAILED_UPDATE_INCOMPATIBLE"
    }

    @Test
    fun `clear_app_data clears installed apps only`() = withMcpClient(runner) { client ->
        runner.onArgs("'pm'", "'clear'", "'com.example.app'", stdout = "Success\n")

        client.callTool("clear_app_data", mapOf("package" to "com.example.app")).text shouldBe
            "Cleared all data for com.example.app on emulator-5554."
        client.callTool("clear_app_data", mapOf("package" to "com.missing.app")).isError shouldBe true
        runner.calls.flatten().count { it == "'clear'" } shouldBe 1
    }

    @Test
    fun `annotations mark destructive tools`() = withMcpClient(runner) { client ->
        val tools = client.listTools().tools.associateBy { it.name }
        tools.getValue("clear_app_data").annotations?.destructiveHint shouldBe true
        tools.getValue("install_apk").annotations?.destructiveHint shouldBe true
        tools.getValue("launch_app").annotations?.destructiveHint shouldBe false
        tools.getValue("list_packages").annotations?.readOnlyHint shouldBe true
        tools.getValue("get_current_activity").annotations?.readOnlyHint shouldBe true
    }
}
