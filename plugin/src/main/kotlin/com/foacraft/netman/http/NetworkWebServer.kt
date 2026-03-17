package com.foacraft.netman.http

import com.foacraft.netman.collector.TrafficCollector
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class NetworkWebServer(
    private val collector: TrafficCollector,
    val port: Int,
    private val htmlBytes: ByteArray,
    private val onStopRequest: () -> Unit
) {
    private var server: HttpServer? = null

    fun start() {
        val srv = HttpServer.create(InetSocketAddress(port), 0)

        srv.createContext("/api/stats") { ex ->
            val body = collector.toJson().toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        srv.createContext("/api/stop") { ex ->
            val body = "{\"ok\":true}".toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
            Thread {
                Thread.sleep(150)
                onStopRequest()
            }.also { it.isDaemon = true; it.start() }
        }

        srv.createContext("/") { ex ->
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.responseHeaders.add("Cache-Control", "no-store, no-cache, must-revalidate")
            ex.responseHeaders.add("Pragma", "no-cache")
            ex.sendResponseHeaders(200, htmlBytes.size.toLong())
            ex.responseBody.use { it.write(htmlBytes) }
        }

        srv.executor = null
        srv.start()
        server = srv
    }

    fun stop() {
        server?.stop(0)
        server = null
    }
}
