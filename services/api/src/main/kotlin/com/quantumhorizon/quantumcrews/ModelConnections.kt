package com.quantumhorizon.quantumcrews

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Keys are bound to their immutable connection identity, never returned by the API. */
@Service
class ConnectionCipher(@Value("\${quantum.credential-key:}") private val configuredKey: String) {
 private fun key(): SecretKeySpec {
  val bytes = try { Base64.getDecoder().decode(configuredKey) } catch (_: IllegalArgumentException) { fail(503,"Model connection storage is not configured") }
  if(bytes.size != 32) fail(503,"Model connection storage is not configured")
  return SecretKeySpec(bytes,"AES")
 }
 fun seal(secret: String, identity: String): String {
  val cipher=Cipher.getInstance("AES/GCM/NoPadding")
  val nonce=ByteArray(12).also(SecureRandom()::nextBytes)
  cipher.init(Cipher.ENCRYPT_MODE,key(),GCMParameterSpec(128,nonce))
  cipher.updateAAD(identity.toByteArray(Charsets.UTF_8))
  return "v1:"+Base64.getEncoder().encodeToString(nonce+cipher.doFinal(secret.toByteArray(Charsets.UTF_8)))
 }
 fun open(value: String, identity: String): String {
  val key=key()
  try {
   require(value.startsWith("v1:"))
   val bytes=Base64.getDecoder().decode(value.substring(3));require(bytes.size>=29)
   val cipher=Cipher.getInstance("AES/GCM/NoPadding")
   cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(0,12)))
   cipher.updateAAD(identity.toByteArray(Charsets.UTF_8))
   return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
  } catch (_: Exception) { fail(503,"Model connection could not be unlocked") }
 }
}

@RestController
@RequestMapping("/api/connections")
class ModelConnections(private val auth: P1Auth, private val cipher: ConnectionCipher) {
 @GetMapping fun list(req: HttpServletRequest): List<Obj> {
  val actor=auth.actor(req)
  return auth.scoped(actor) { auth.jdbc.queryForList("SELECT id,name,provider,created_at FROM model_connections ORDER BY created_at DESC,id").map {
   mapOf("id" to it["id"],"name" to it["name"],"provider" to it["provider"],"createdAt" to it["created_at"].toString())
  } }
 }
 @PostMapping fun create(@RequestBody body: Obj, req: HttpServletRequest): Obj {
  val actor=auth.actor(req,true)
  if(body.keys != setOf("name","provider","apiKey")) fail(400,"Provide name, provider, and apiKey only")
  val name=body.str("name").trim();val provider=body.str("provider");val secret=body.str("apiKey")
  if(name.length !in 1..80 || provider !in setOf("smart-router","openai","anthropic")) fail(400,"Choose a supported provider and a name of 1–80 characters")
  if(secret.length !in 8..4096 || secret.any { it.isWhitespace() || it.isISOControl() }) fail(400,"Provide a valid API key without whitespace")
  val id=UUID.randomUUID()
  val encrypted=cipher.seal(secret,"${actor.orgId}:${actor.userId}:$id:$provider")
  return auth.scoped(actor) {
   auth.jdbc.queryForObject("SELECT id FROM app_users WHERE id=? FOR UPDATE",UUID::class.java,actor.userId)
   if(auth.jdbc.queryForObject("SELECT count(*) FROM model_connections",Int::class.java)!!>=20) fail(409,"Remove an existing connection before adding another")
   auth.jdbc.update("INSERT INTO model_connections(org_id,id,owner_id,name,provider,encrypted_key) VALUES(?,?,?,?,?,?)",actor.orgId,id,actor.userId,name,provider,encrypted)
   mapOf("id" to id,"name" to name,"provider" to provider)
  }
 }
 @PutMapping("/{id}/key") fun replace(@PathVariable id: UUID, @RequestBody body: Obj, req: HttpServletRequest): Obj {
  val actor=auth.actor(req,true)
  if(body.keys != setOf("apiKey")) fail(400,"Provide apiKey only")
  val secret=body.str("apiKey")
  if(secret.length !in 8..4096 || secret.any { it.isWhitespace() || it.isISOControl() }) fail(400,"Provide a valid API key without whitespace")
  return auth.scoped(actor) {
   val provider=auth.jdbc.queryForList("SELECT provider FROM model_connections WHERE id=? FOR UPDATE",id).firstOrNull()?.get("provider") as? String ?: fail(404,"Connection not found")
   val encrypted=cipher.seal(secret,"${actor.orgId}:${actor.userId}:$id:$provider")
   auth.jdbc.update("UPDATE model_connections SET encrypted_key=? WHERE id=?",encrypted,id)
   mapOf("ok" to true)
  }
 }
 @DeleteMapping("/{id}") fun remove(@PathVariable id: UUID, req: HttpServletRequest): Obj {
  val actor=auth.actor(req,true)
  auth.scoped(actor) { auth.jdbc.update("DELETE FROM model_connections WHERE id=?",id) }
  return mapOf("ok" to true)
 }
}
