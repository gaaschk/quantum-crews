package com.quantumhorizon.quantumcrews

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.server.ResponseStatusException
import java.net.InetSocketAddress
import kotlin.test.assertEquals

class FoundationControllerTests {
    @Test
    fun `reports ready only when database and runtime are ready`() {
        val jdbc = Mockito.mock(JdbcTemplate::class.java)
        Mockito.`when`(jdbc.queryForObject("SELECT count(*) FROM foundation_metadata", Int::class.java)).thenReturn(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val status = java.util.concurrent.atomic.AtomicInteger(200)
        server.createContext("/health") { exchange -> exchange.sendResponseHeaders(status.get(), -1); exchange.close() }
        server.start()
        try {
            val controller = FoundationController(jdbc, "http://127.0.0.1:${server.address.port}")
            assertEquals("ready", controller.foundation()["status"])
            status.set(503)
            val error = assertThrows<ResponseStatusException> { controller.foundation() }
            assertEquals(503, error.statusCode.value())
        } finally { server.stop(0) }
    }

    @Test
    fun `unavailable dependency returns service unavailable`() {
        val jdbc = Mockito.mock(JdbcTemplate::class.java)
        Mockito.`when`(jdbc.queryForObject("SELECT count(*) FROM foundation_metadata", Int::class.java)).thenReturn(0)
        val error = assertThrows<ResponseStatusException> { FoundationController(jdbc, "http://127.0.0.1:1").foundation() }
        assertEquals(503, error.statusCode.value())
    }
}
