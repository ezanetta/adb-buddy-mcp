package io.github.ezanetta.adbbuddy.tools

import io.github.ezanetta.adbbuddy.adb.AdbException
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("adb-buddy.tools")

/** Largest text payload a tool returns before truncating. Roughly 12k tokens. */
const val MAX_TEXT_CHARS = 50_000

object Annotations {
    fun readOnly(title: String) = ToolAnnotations(
        title = title,
        readOnlyHint = true,
        idempotentHint = true,
        openWorldHint = false,
    )

    fun action(title: String, idempotent: Boolean = false) = ToolAnnotations(
        title = title,
        readOnlyHint = false,
        destructiveHint = false,
        idempotentHint = idempotent,
        openWorldHint = false,
    )

    fun destructive(title: String) = ToolAnnotations(
        title = title,
        readOnlyHint = false,
        destructiveHint = true,
        idempotentHint = false,
        openWorldHint = false,
    )
}

/**
 * Registers a tool whose handler may throw [AdbException]; failures become `isError`
 * results instead of protocol errors so the model can read and react to them.
 */
fun Server.adbTool(
    name: String,
    description: String,
    annotations: ToolAnnotations,
    schema: ToolSchema,
    handler: suspend (ToolArgs) -> CallToolResult,
) {
    addTool(
        name = name,
        description = description,
        inputSchema = schema,
        title = annotations.title,
        toolAnnotations = annotations,
    ) { request ->
        try {
            handler(ToolArgs(request))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbException) {
            logger.info("{} failed: {}", name, e.message)
            errorResult(e.message.orEmpty())
        } catch (e: Exception) {
            logger.error("{} crashed", name, e)
            errorResult("Unexpected error in $name: ${e::class.simpleName}: ${e.message}")
        }
    }
}

fun errorResult(message: String) = CallToolResult(content = listOf(TextContent(message)), isError = true)

fun textResult(text: String, keep: Keep = Keep.HEAD): CallToolResult =
    CallToolResult(content = listOf(TextContent(truncate(text, MAX_TEXT_CHARS, keep))))

fun contentResult(vararg content: ContentBlock) = CallToolResult(content = content.toList())

enum class Keep { HEAD, TAIL }

/** Trims [text] to [max] chars, keeping the start or the end, and says so in the output. */
fun truncate(text: String, max: Int, keep: Keep = Keep.HEAD): String {
    if (text.length <= max) return text
    val dropped = text.length - max
    return when (keep) {
        Keep.HEAD -> text.take(max) + "\n[truncated: $dropped more characters omitted]"
        Keep.TAIL -> "[truncated: $dropped earlier characters omitted]\n" + text.takeLast(max)
    }
}

/** Typed, validating access to tool arguments. */
class ToolArgs(request: CallToolRequest) {
    private val args: JsonObject = request.arguments ?: JsonObject(emptyMap())

    val serial: String? get() = optionalString("serial")

    fun optionalString(name: String): String? = when (val value = args[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content.takeIf { it.isNotEmpty() } else invalid(name, "a string")
        else -> invalid(name, "a string")
    }

    fun string(name: String): String = optionalString(name) ?: missing(name)

    fun optionalInt(name: String, range: IntRange): Int? {
        val value = args[name]
        if (value == null || value is JsonNull) return null
        val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            ?: (value as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            ?: invalid(name, "an integer")
        if (number % 1.0 != 0.0) invalid(name, "an integer")
        val int = number.toInt()
        if (int !in range) {
            throw AdbException.InvalidArgument("`$name` must be between ${range.first} and ${range.last}, got $int.")
        }
        return int
    }

    fun int(name: String, range: IntRange): Int = optionalInt(name, range) ?: missing(name)

    fun optionalBoolean(name: String): Boolean? = when (val value = args[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.booleanOrNull ?: invalid(name, "a boolean")
        else -> invalid(name, "a boolean")
    }

    private fun missing(name: String): Nothing =
        throw AdbException.InvalidArgument("Missing required argument `$name`.")

    private fun invalid(name: String, expected: String): Nothing =
        throw AdbException.InvalidArgument("`$name` must be $expected.")
}

/** Small DSL for JSON Schema tool inputs. Device-scoped tools also get the optional `serial` property. */
class SchemaBuilder(private val includeSerial: Boolean) {
    private val properties = mutableListOf<Pair<String, JsonObject>>()
    private val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        property(name, required) {
            put("type", "string")
            put("description", description)
            enum?.let { values -> putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } } }
        }

    fun integer(
        name: String,
        description: String,
        required: Boolean = false,
        min: Int? = null,
        max: Int? = null,
        default: Int? = null,
    ) = property(name, required) {
        put("type", "integer")
        put("description", description)
        min?.let { put("minimum", it) }
        max?.let { put("maximum", it) }
        default?.let { put("default", it) }
    }

    fun boolean(name: String, description: String, default: Boolean? = null) = property(name, false) {
        put("type", "boolean")
        put("description", description)
        default?.let { put("default", it) }
    }

    private fun property(name: String, isRequired: Boolean, body: JsonObjectBuilder.() -> Unit) {
        properties += name to buildJsonObject(body)
        if (isRequired) required += name
    }

    fun build(): ToolSchema = ToolSchema(
        properties = buildJsonObject {
            properties.forEach { (name, schema) -> put(name, schema) }
            if (includeSerial) {
                putJsonObject("serial") {
                    put("type", "string")
                    put(
                        "description",
                        "Device serial from list_devices. Optional when exactly one device is connected.",
                    )
                }
            }
        },
        required = required.takeIf { it.isNotEmpty() },
    )
}

fun schema(includeSerial: Boolean = true, body: SchemaBuilder.() -> Unit = {}): ToolSchema =
    SchemaBuilder(includeSerial).apply(body).build()
