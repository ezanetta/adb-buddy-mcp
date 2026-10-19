package io.github.ezanetta.adbbuddy.tools

import io.github.ezanetta.adbbuddy.adb.AdbClient
import io.github.ezanetta.adbbuddy.image.PngScaler
import io.github.ezanetta.adbbuddy.parse.UiHierarchySimplifier
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.util.Locale
import kotlin.io.encoding.Base64

private const val DEFAULT_MAX_SIDE = 1280

fun Server.registerScreenTools(adb: AdbClient) {
    adbTool(
        name = "take_screenshot",
        description = "Capture the device screen as a PNG image. By default the image is downscaled so its " +
            "longest side is $DEFAULT_MAX_SIDE px; the reply states the scale so you can convert image " +
            "coordinates back to device coordinates for tap. Prefer dump_ui_hierarchy to find tap targets.",
        annotations = Annotations.readOnly("Take screenshot"),
        schema = schema {
            integer(
                "max_size",
                "Longest side in pixels after downscaling. 0 keeps the full device resolution.",
                min = 0,
                max = 4096,
                default = DEFAULT_MAX_SIDE,
            )
        },
    ) { args ->
        val serial = adb.resolveSerial(args.serial)
        val maxSide = args.optionalInt("max_size", 0..4096) ?: DEFAULT_MAX_SIDE
        val image = PngScaler.fit(adb.screenshot(serial), maxSide)
        val note = buildString {
            append("Screenshot of ").append(serial).append(": ").append(image.width).append('x').append(image.height)
            if (image.width != image.originalWidth) {
                append(" (device ").append(image.originalWidth).append('x').append(image.originalHeight)
                val ratio = String.format(Locale.ROOT, "%.4f", image.deviceToImageRatio)
                append("; multiply image coordinates by ").append(ratio).append(" to get device coordinates for tap)")
            }
            append('.')
        }
        contentResult(
            ImageContent(data = Base64.encode(image.png), mimeType = "image/png"),
            TextContent(note),
        )
    }

    adbTool(
        name = "dump_ui_hierarchy",
        description = "List the visible UI elements on screen (via uiautomator): text, content description, " +
            "resource id, state flags and center coordinates ready for tap. Much cheaper than a screenshot.",
        annotations = Annotations.readOnly("Dump UI hierarchy"),
        schema = schema(),
    ) { args ->
        val serial = adb.resolveSerial(args.serial)
        textResult(UiHierarchySimplifier.format(UiHierarchySimplifier.parse(adb.dumpUi(serial))))
    }
}
