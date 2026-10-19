package io.github.ezanetta.adbbuddy.parse

import io.github.ezanetta.adbbuddy.adb.AdbException
import io.github.ezanetta.adbbuddy.testutil.fixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class UiHierarchySimplifierTest {
    @Test
    fun `settings rows collapse into one labelled clickable line each`() {
        val snapshot = UiHierarchySimplifier.parse(fixture("ui_settings.xml"))
        val labels = snapshot.elements.map { it.label }

        labels shouldContain "Network & internet | Mobile, Wi‑Fi, hotspot"
        labels shouldContain "Search Settings"
        labels.none { it == "Network & internet" } shouldBe true

        val network = snapshot.elements.single { it.label.startsWith("Network & internet") }
        network.flags shouldBe listOf("clickable")
        (network.bounds.centerX to network.bounds.centerY) shouldBe (540 to 719)
    }

    @Test
    fun `scrollable lists keep their items separate`() {
        val elements = UiHierarchySimplifier.parse(fixture("ui_search_results.xml")).elements

        elements.single { it.className == "RecyclerView" }.label shouldBe ""
        elements.map { it.label } shouldContain "Erase all data (factory reset) | System > Reset options"
        elements.map { it.label } shouldContain "Apps"
        elements.single { it.className == "EditText" }.label shouldBe
            "it's a \"test\" \$(id); echo `whoami` & a|b <c> *.kt \\n ~100% done"
    }

    @Test
    fun `real dumps shrink a lot`() {
        listOf("ui_settings.xml", "ui_launcher.xml", "ui_search_results.xml").forEach { name ->
            val xml = fixture(name)
            val text = UiHierarchySimplifier.format(UiHierarchySimplifier.parse(xml))
            text.length shouldBeLessThan xml.length / 8
        }
    }

    @Test
    fun `reports screen size, rotation and app`() {
        val snapshot = UiHierarchySimplifier.parse(fixture("ui_login_form.xml"))
        snapshot.rotation shouldBe 1
        snapshot.packageName shouldBe "com.example.app"
        UiHierarchySimplifier.format(snapshot) shouldContain "Screen 2424x1080, rotation 90, app com.example.app."
    }

    @Test
    fun `formats flags, ids and descriptions compactly`() {
        val lines = UiHierarchySimplifier.format(UiHierarchySimplifier.parse(fixture("ui_login_form.xml"))).lines()

        lines.drop(1) shouldBe listOf(
            "[1] TextView \"Sign in\" id=title (300,85)",
            "[2] EditText \"ada@example.com\" id=email clickable long-clickable focused (600,250)",
            "[3] EditText desc=\"Password\" id=password clickable long-clickable password (600,370)",
            "[4] CheckBox \"Remember me\" id=remember clickable checked (350,480)",
            "[5] LinearLayout \"Continue\" id=submit_row clickable disabled (600,610)",
            "[6] ImageButton desc=\"Help\" id=help clickable (1040,610)",
            "[7] TextView \"Terms and conditions\" (400,750)",
        )
    }

    @Test
    fun `skips zero area nodes`() {
        UiHierarchySimplifier.format(UiHierarchySimplifier.parse(fixture("ui_login_form.xml"))) shouldNotContain
            "Hidden"
    }

    @Test
    fun `refuses DTDs so entities cannot be expanded`() {
        shouldThrow<AdbException.CommandFailed> { UiHierarchySimplifier.parse(fixture("ui_xxe.xml")) }
            .message shouldContain "DOCTYPE"
    }

    @Test
    fun `parses bounds`() {
        Bounds.parse("[0,373][1080,2361]") shouldBe Bounds(0, 373, 1080, 2361)
        Bounds.parse("[221,2372][609,2361]")!!.isEmpty shouldBe true
        Bounds.parse("garbage") shouldBe null
        UiHierarchySimplifier.parse(fixture("ui_login_form.xml")).elements shouldHaveSize 7
    }
}
