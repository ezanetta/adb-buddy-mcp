package io.github.ezanetta.adbbuddy.testutil

import io.github.ezanetta.adbbuddy.adb.AdbClient
import io.github.ezanetta.adbbuddy.createServer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking

/** Runs the real server against an in-memory MCP client, with adb replaced by [runner]. */
fun withMcpClient(runner: FakeProcessRunner, block: suspend (Client) -> Unit): Unit = runBlocking {
    val server = createServer(AdbClient("/sdk/platform-tools/adb", runner))
    val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
    server.createSession(serverTransport)
    val client = Client(Implementation("test-client", "1.0"))
    client.connect(clientTransport)
    try {
        block(client)
    } finally {
        client.close()
        server.close()
    }
}

val CallToolResult.text: String get() = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
