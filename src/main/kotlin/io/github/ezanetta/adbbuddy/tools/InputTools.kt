package io.github.ezanetta.adbbuddy.tools

import io.github.ezanetta.adbbuddy.adb.AdbClient
import io.github.ezanetta.adbbuddy.validate.Validators
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val MAX_COORDINATE = 20_000
private const val DEFAULT_SWIPE_MS = 300
private const val MAX_SWIPE_MS = 10_000
private const val FOLLOW_UP = "Call dump_ui_hierarchy or take_screenshot to see the result."

fun Server.registerInputTools(adb: AdbClient) {
    adbTool(
        name = "tap",
        description = "Tap the screen at device pixel coordinates, e.g. the (x,y) center from dump_ui_hierarchy.",
        annotations = Annotations.action("Tap"),
        schema = schema {
            integer("x", "X coordinate in device pixels.", required = true, min = 0, max = MAX_COORDINATE)
            integer("y", "Y coordinate in device pixels.", required = true, min = 0, max = MAX_COORDINATE)
        },
    ) { args ->
        val serial = adb.resolveSerial(args.serial)
        val x = args.int("x", 0..MAX_COORDINATE)
        val y = args.int("y", 0..MAX_COORDINATE)
        val screen = adb.displaySize(serial)
        Validators.coordinates(x, y, screen.width, screen.height)
        adb.input(serial, listOf("tap", "$x", "$y"))
        textResult("Tapped ($x,$y) on $serial. $FOLLOW_UP")
    }

    adbTool(
        name = "swipe",
        description = "Swipe from one point to another in device pixels, e.g. to scroll a list " +
            "(swipe upwards to scroll down). Duration controls the speed; use a long one for a long press.",
        annotations = Annotations.action("Swipe"),
        schema = schema {
            integer("from_x", "Start X in device pixels.", required = true, min = 0, max = MAX_COORDINATE)
            integer("from_y", "Start Y in device pixels.", required = true, min = 0, max = MAX_COORDINATE)
            integer("to_x", "End X in device pixels.", required = true, min = 0, max = MAX_COORDINATE)
            integer("to_y", "End Y in device pixels.", required = true, min = 0, max = MAX_COORDINATE)
            integer(
                "duration_ms",
                "Gesture duration in milliseconds.",
                min = 1,
                max = MAX_SWIPE_MS,
                default = DEFAULT_SWIPE_MS,
            )
        },
    ) { args ->
        val serial = adb.resolveSerial(args.serial)
        val fromX = args.int("from_x", 0..MAX_COORDINATE)
        val fromY = args.int("from_y", 0..MAX_COORDINATE)
        val toX = args.int("to_x", 0..MAX_COORDINATE)
        val toY = args.int("to_y", 0..MAX_COORDINATE)
        val duration = args.optionalInt("duration_ms", 1..MAX_SWIPE_MS) ?: DEFAULT_SWIPE_MS
        val screen = adb.displaySize(serial)
        Validators.coordinates(fromX, fromY, screen.width, screen.height, label = "Start point")
        Validators.coordinates(toX, toY, screen.width, screen.height, label = "End point")
        adb.input(
            serial,
            listOf("swipe", "$fromX", "$fromY", "$toX", "$toY", "$duration"),
            timeout = duration.milliseconds + 10.seconds,
        )
        textResult("Swiped ($fromX,$fromY) -> ($toX,$toY) in ${duration}ms on $serial. $FOLLOW_UP")
    }

    adbTool(
        name = "input_text",
        description = "Type text into the focused field, as if from a keyboard. Tap the field first. " +
            "Printable ASCII only (max ${Validators.MAX_INPUT_TEXT} chars); use press_key ENTER to submit.",
        annotations = Annotations.action("Input text"),
        schema = schema {
            string("text", "Text to type.", required = true)
        },
    ) { args ->
        val serial = adb.resolveSerial(args.serial)
        val text = args.string("text")
        adb.input(serial, listOf("text", Validators.inputText(text)), timeout = 30.seconds)
        textResult("Typed ${text.length} character(s) on $serial. $FOLLOW_UP")
    }

    adbTool(
        name = "press_key",
        description = "Press a hardware or navigation key: ${Validators.keys.keys.joinToString()}.",
        annotations = Annotations.action("Press key"),
        schema = schema {
            string("key", "Key to press.", required = true, enum = Validators.keys.keys.toList())
        },
    ) { args ->
        val serial = adb.resolveSerial(args.serial)
        val key = args.string("key")
        adb.input(serial, listOf("keyevent", Validators.keyCode(key)))
        textResult("Pressed ${key.uppercase()} on $serial. $FOLLOW_UP")
    }
}
