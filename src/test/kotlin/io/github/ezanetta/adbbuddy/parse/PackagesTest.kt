package io.github.ezanetta.adbbuddy.parse

import io.github.ezanetta.adbbuddy.testutil.fixture
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PackagesTest {
    @Test
    fun `parses and sorts pm list packages`() {
        PackagesParser.parse(fixture("pm_list_packages.txt")) shouldBe
            listOf("com.example.alpha", "com.example.zeta", "org.mozilla.firefox")
        PackagesParser.parse("package:com.a versionCode:3\n") shouldBe listOf("com.a")
    }

    @Test
    fun `finds the resumed activity on current Android`() {
        ActivityParser.resumedActivity(fixture("dumpsys_activities_api37.txt")) shouldBe
            Component("com.android.settings", ".homepage.SettingsHomepageActivity")
    }

    @Test
    fun `finds the resumed activity on Android 9`() {
        val component = ActivityParser.resumedActivity(fixture("dumpsys_activities_api28.txt"))!!
        component.packageName shouldBe "com.example.app"
        component.fullClassName shouldBe "com.example.app.ui.MainActivity"
    }

    @Test
    fun `nothing resumed`() {
        ActivityParser.resumedActivity("ACTIVITY MANAGER ACTIVITIES\n  mFocusedApp=null\n") shouldBe null
    }

    @Test
    fun `reads resolve-activity output`() {
        val output = "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n" +
            "com.android.settings/.Settings\n"
        ActivityParser.resolvedActivity(output) shouldBe Component("com.android.settings", ".Settings")
        ActivityParser.resolvedActivity(output)!!.fullClassName shouldBe "com.android.settings.Settings"
        ActivityParser.resolvedActivity("No activity found\n") shouldBe null
    }
}
