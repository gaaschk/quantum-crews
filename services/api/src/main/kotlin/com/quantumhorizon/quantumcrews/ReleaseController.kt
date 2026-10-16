package com.quantumhorizon.quantumcrews

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.Duration

@Service
class ReleaseStatus(private val json:ObjectMapper,
 @Value("\${QUANTUM_SOURCE_REVISION:local}") private val revision:String,
 @Value("\${quantum.runtime-url}") private val runtimeUrl:String="") {
 private val http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
 private var cached:Obj=emptyMap()
 private var expires=Instant.EPOCH
 private val repository="https://github.com/gaaschk/quantum-crews"
 private fun github(route:String):String {
  val request=HttpRequest.newBuilder(URI.create("https://api.github.com/repos/gaaschk/quantum-crews$route"))
   .header("Accept","application/vnd.github+json").header("User-Agent","quantum-crews-release-status")
   .timeout(Duration.ofSeconds(3)).GET().build()
  val response=http.send(request,HttpResponse.BodyHandlers.ofString())
  check(response.statusCode()==200)
  return response.body()
 }
 @Synchronized fun status():Obj {
  if(!revision.matches(Regex("[0-9a-f]{40}"))) return mapOf("installedRevision" to null,"status" to "development")
  if(Instant.now().isBefore(expires)) return cached
  val base=mapOf("installedRevision" to revision,"commitUrl" to "$repository/commit/$revision","checkedAt" to Instant.now().toString())
  cached=try {
   val runtimeRequest=HttpRequest.newBuilder(URI.create("$runtimeUrl/health")).timeout(Duration.ofSeconds(2)).GET().build()
   val runtimeResponse=http.send(runtimeRequest,HttpResponse.BodyHandlers.ofString())
   check(runtimeResponse.statusCode()==200)
   val runtime=json.readValue(runtimeResponse.body(),object:TypeReference<Obj>() {})
   check(runtime["revision"]==revision)
   val rows=json.readValue(github("/pulls?state=closed&base=main&sort=updated&direction=desc&per_page=100"),object:TypeReference<List<Obj>>() {})
   val latest=latestMerged(rows) ?: return (base+mapOf("status" to "check_unavailable")).also{cached=it;expires=Instant.now().plusSeconds(300)}
   val merged=latest.str("merge_commit_sha")
   check(merged.matches(Regex("[0-9a-f]{40}")))
   val included=if(merged==revision) true else {
    val comparison=json.readValue(github("/compare/$merged...$revision"),object:TypeReference<Obj>() {})
    comparison["status"] in setOf("identical","ahead")
   }
   base+mapOf("status" to if(included) "deployed" else "not_deployed","latestPrNumber" to latest.num("number"),"latestPrUrl" to "$repository/pull/${latest.num("number")}","mergeRevision" to merged)
  } catch(e:Exception) {if(e is InterruptedException) Thread.currentThread().interrupt();base+mapOf("status" to "check_unavailable")}
  expires=Instant.now().plusSeconds(300)
  return cached
 }
 companion object {
  fun latestMerged(rows:List<Obj>):Obj? {
   val latest=rows.filter{it["merged_at"] is String}.maxByOrNull{Instant.parse(it.str("merged_at"))}?:return null
   // Unseen older-updated rows cannot contain a newer merge. Otherwise report unknown.
   if(rows.size==100 && Instant.parse(latest.str("merged_at")).isBefore(Instant.parse(rows.last().str("updated_at")))) return null
   return latest
  }
 }
}

@RestController
class ReleaseController(private val release:ReleaseStatus) {
 @GetMapping("/api/release") fun release()=release.status()
}
