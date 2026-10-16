package com.quantumhorizon.quantumcrews

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@SpringBootTest
class P1IsolationTests(@Autowired private val jdbc: JdbcTemplate, @Autowired private val auth: P1Auth) {
 @Test fun `application role is not a superuser and cannot mutate history`() {
  assertEquals("qc_app",jdbc.queryForObject("SELECT current_user",String::class.java))
  assertFalse(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user",Boolean::class.java)!!)
  for(table in listOf("component_versions","approvals","run_snapshots","run_events")) {
   assertFalse(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,'UPDATE')",Boolean::class.java,table)!!)
   assertFalse(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,'DELETE')",Boolean::class.java,table)!!)
  }
 }
 @Test fun `an empty tenant queue can return null without crashing`() {
  kotlin.test.assertNull(auth.scoped(Actor(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),"owner","")){null})
 }
 @Test fun `missing tenant context sees no tenant records`() {
  for(table in listOf("components","component_versions","proposals","approvals","runs","run_snapshots","run_events")) assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM $table",Int::class.java))
 }
}
