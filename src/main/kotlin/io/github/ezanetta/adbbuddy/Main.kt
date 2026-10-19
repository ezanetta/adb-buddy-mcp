package io.github.ezanetta.adbbuddy

import io.github.ezanetta.adbbuddy.adb.AdbClient
import io.github.ezanetta.adbbuddy.adb.AdbException
import io.github.ezanetta.adbbuddy.adb.AdbLocator
import io.github.ezanetta.adbbuddy.adb.JvmProcessRunner
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory
import java.io.FileDescriptor
import java.io.FileOutputStream
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger(SERVER_NAME)

fun main(args: Array<String>) {
    if (args.any { it == "--version" || it == "-v" }) {
        System.err.println("$SERVER_NAME $serverVersion")
        return
    }

    // stdout carries JSON-RPC only. Keep a handle to the real stream for the transport and point
    // System.out at stderr so a stray println from any library cannot corrupt the protocol.
    val protocolOut = FileOutputStream(FileDescriptor.out)
    System.setOut(System.err)

    val adbPath = try {
        AdbLocator().locate()
    } catch (e: AdbException.AdbNotFound) {
        logger.error(e.message)
        exitProcess(1)
    }
    logger.info("{} {} starting, using adb at {}", SERVER_NAME, serverVersion, adbPath)

    val server = createServer(AdbClient(adbPath, JvmProcessRunner()))
    val transport = StdioServerTransport(System.`in`.asSource().buffered(), protocolOut.asSink().buffered())

    runBlocking {
        val closed = CompletableDeferred<Unit>()
        val session = server.createSession(transport)
        session.onClose { closed.complete(Unit) }
        closed.await()
    }
    logger.info("client disconnected, shutting down")
}
