package com.quantumhorizon.quantumcrews

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@RestController
class FoundationController(private val jdbc: JdbcTemplate, @Value("\${quantum.runtime-url}") private val runtimeUrl: String) {
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build()

    @GetMapping("/api/foundation")
    fun foundation(): Map<String, String> {
        try {
            check(jdbc.queryForObject("SELECT count(*) FROM foundation_metadata", Int::class.java) == 1)
            val request = HttpRequest.newBuilder(URI.create("$runtimeUrl/health")).timeout(Duration.ofSeconds(2)).GET().build()
            check(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200)
        } catch (exception: Exception) {
            if (exception is InterruptedException) Thread.currentThread().interrupt()
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Foundation dependency unavailable")
        }
        return mapOf("status" to "ready", "database" to "ready", "runtime" to "ready")
    }
}
