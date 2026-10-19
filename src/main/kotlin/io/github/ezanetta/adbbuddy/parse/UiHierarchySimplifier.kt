package io.github.ezanetta.adbbuddy.parse

import io.github.ezanetta.adbbuddy.adb.AdbException
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    companion object {
        private val pattern = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

        fun parse(value: String): Bounds? = pattern.matchEntire(value)?.destructured?.let { (l, t, r, b) ->
            Bounds(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        }
    }
}

data class UiElement(
    val className: String,
    val label: String,
    val contentDesc: String,
    val resourceId: String,
    val bounds: Bounds,
    val flags: List<String>,
)

data class UiSnapshot(val rotation: Int, val packageName: String?, val screen: Bounds?, val elements: List<UiElement>)

/**
 * Turns a uiautomator XML dump into a short list of the elements a model can read or act on.
 *
 * - Layout-only containers are dropped.
 * - A clickable container without its own text takes its descendants' text as its label, and
 *   those plain-text descendants are not listed again (a Settings row becomes one line).
 * - Nodes with no visible area are skipped.
 */
object UiHierarchySimplifier {
    private const val MAX_LABEL_CHARS = 100

    fun parse(xml: String): UiSnapshot {
        val root = try {
            secureFactory().newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        } catch (e: Exception) {
            throw AdbException.CommandFailed("parse uiautomator dump", 0, e.message ?: "invalid XML")
        }
        val nodes = root.childElements("node")
        val elements = mutableListOf<UiElement>()
        nodes.forEach { collect(it, elements) }
        return UiSnapshot(
            rotation = root.getAttribute("rotation").toIntOrNull() ?: 0,
            packageName = nodes.firstOrNull()?.getAttribute("package")?.ifEmpty { null },
            screen = nodes.firstOrNull()?.let { Bounds.parse(it.getAttribute("bounds")) },
            elements = elements,
        )
    }

    fun format(snapshot: UiSnapshot): String = buildString {
        append("Screen")
        snapshot.screen?.let { append(" ").append(it.width).append('x').append(it.height) }
        append(", rotation ").append(snapshot.rotation * 90)
        snapshot.packageName?.let { append(", app ").append(it) }
        append(". ").append(snapshot.elements.size).append(" elements; (x,y) is the center to pass to tap.")
        snapshot.elements.forEachIndexed { i, e ->
            appendLine()
            append('[').append(i + 1).append("] ").append(e.className)
            if (e.label.isNotEmpty()) append(" \"").append(e.label).append('"')
            if (e.contentDesc.isNotEmpty() &&
                e.contentDesc != e.label
            ) {
                append(" desc=\"").append(e.contentDesc).append('"')
            }
            if (e.resourceId.isNotEmpty()) append(" id=").append(e.resourceId)
            e.flags.forEach { append(' ').append(it) }
            append(" (").append(e.bounds.centerX).append(',').append(e.bounds.centerY).append(')')
        }
    }

    private fun collect(node: Element, out: MutableList<UiElement>) {
        val bounds = Bounds.parse(node.getAttribute("bounds"))
        val children = node.childElements("node")
        if (bounds == null || bounds.isEmpty) {
            children.forEach { collect(it, out) }
            return
        }

        val text = node.getAttribute("text").clean()
        val desc = node.getAttribute("content-desc").clean()
        val className = node.getAttribute("class").substringAfterLast('.')
        val flags = flags(node)
        val interactive = flags.any { it in INTERACTIVE_FLAGS } || className.contains("EditText")

        if (interactive && text.isEmpty() && desc.isEmpty()) {
            // Label a bare clickable container with its children's text, then skip those text-only children.
            // Interactive descendants are still emitted, after this container.
            val position = out.size
            val inherited = mutableListOf<String>()
            children.forEach { absorbText(it, inherited, out) }
            out.add(position, element(node, className, inherited.joinToString(" | "), desc, bounds, flags))
            return
        }
        if (interactive || text.isNotEmpty() || desc.isNotEmpty()) {
            out += element(node, className, text, desc, bounds, flags)
        }
        children.forEach { collect(it, out) }
    }

    /** Gathers text from plain descendants; interactive descendants are still listed on their own. */
    private fun absorbText(node: Element, labels: MutableList<String>, out: MutableList<UiElement>) {
        val bounds = Bounds.parse(node.getAttribute("bounds"))
        val interactive = flags(node).any { it in INTERACTIVE_FLAGS }
        if (interactive) {
            collect(node, out)
            return
        }
        if (bounds != null && !bounds.isEmpty) {
            node.getAttribute("text").clean().ifEmpty { node.getAttribute("content-desc").clean() }
                .takeIf { it.isNotEmpty() }?.let(labels::add)
        }
        node.childElements("node").forEach { absorbText(it, labels, out) }
    }

    private fun element(
        node: Element,
        className: String,
        label: String,
        desc: String,
        bounds: Bounds,
        flags: List<String>,
    ) = UiElement(
        className = className,
        label = label.take(MAX_LABEL_CHARS),
        contentDesc = desc.take(MAX_LABEL_CHARS),
        resourceId = node.getAttribute("resource-id").substringAfter(":id/"),
        bounds = bounds,
        flags = flags,
    )

    private fun flags(node: Element): List<String> = buildList {
        if (node.attr("clickable")) add("clickable")
        if (node.attr("long-clickable")) add("long-clickable")
        if (node.attr("scrollable")) add("scrollable")
        if (node.attr("checkable")) add(if (node.attr("checked")) "checked" else "unchecked")
        if (node.attr("focused")) add("focused")
        if (node.attr("selected")) add("selected")
        if (node.attr("password")) add("password")
        if (node.getAttribute("enabled") == "false") add("disabled")
    }

    private val INTERACTIVE_FLAGS = setOf("clickable", "long-clickable", "scrollable", "checked", "unchecked")

    private fun Element.attr(name: String) = getAttribute(name) == "true"

    private fun String.clean() = replace(Regex("\\s+"), " ").trim()

    private fun Element.childElements(tag: String): List<Element> {
        val list = childNodes
        return (0 until list.length).mapNotNull { list.item(it) as? Element }.filter { it.tagName == tag }
    }

    /** uiautomator output is untrusted text: refuse DTDs and external entities (XXE). */
    private fun secureFactory() = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
}
