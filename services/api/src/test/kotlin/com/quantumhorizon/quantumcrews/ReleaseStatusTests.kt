package com.quantumhorizon.quantumcrews

import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import kotlin.test.*
class ReleaseStatusTests {
 @Test fun `development checkout never claims a deployed PR`() {
  assertEquals("development",ReleaseStatus(ObjectMapper(),"local").status()["status"])
 }
 @Test fun `latest merge is chosen by merge time rather than last comment`() {
  val older=mapOf("number" to 1,"merged_at" to "2026-10-15T00:00:00Z","updated_at" to "2026-10-17T00:00:00Z")
  val latest=mapOf("number" to 2,"merged_at" to "2026-10-16T00:00:00Z","updated_at" to "2026-10-16T00:00:00Z")
  assertEquals(2,ReleaseStatus.latestMerged(listOf(older,latest))?.get("number"))
  assertNull(ReleaseStatus.latestMerged(listOf(mapOf("merged_at" to null))))
  assertNull(ReleaseStatus.latestMerged(List(100){older}))
 }
}
