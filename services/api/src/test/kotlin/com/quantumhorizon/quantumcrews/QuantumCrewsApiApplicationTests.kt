package com.quantumhorizon.quantumcrews

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals

@SpringBootTest
class QuantumCrewsApiApplicationTests(@Autowired private val jdbc: JdbcTemplate) {
    @Test
    fun `real PostgreSQL has the foundation migration`() {
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM foundation_metadata", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='components'", Int::class.java))
    }
}
