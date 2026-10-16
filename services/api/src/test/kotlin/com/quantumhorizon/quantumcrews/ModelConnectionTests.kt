package com.quantumhorizon.quantumcrews

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.server.ResponseStatusException
import java.util.Base64
import java.util.UUID
import kotlin.test.*

class ConnectionCipherTests {
 private val cipher=ConnectionCipher(Base64.getEncoder().encodeToString(ByteArray(32){it.toByte()}))
 @Test fun `authenticated encryption binds identity and uses fresh nonces`() {
  val secret="synthetic-key-never-live";val sealed=cipher.seal(secret,"org:user:id:openai")
  assertFalse(sealed.contains(secret));assertEquals(secret,cipher.open(sealed,"org:user:id:openai"))
  assertNotEquals(sealed,cipher.seal(secret,"org:user:id:openai"))
  assertFailsWith<ResponseStatusException>{cipher.open(sealed,"other:user:id:openai")}
  val bytes=Base64.getDecoder().decode(sealed.substring(3));bytes[20]=(bytes[20].toInt() xor 1).toByte()
  assertFailsWith<ResponseStatusException>{cipher.open("v1:"+Base64.getEncoder().encodeToString(bytes),"org:user:id:openai")}
 }
 @Test fun `missing invalid or wrong master key fails closed`() {
  for(key in listOf("","invalid",Base64.getEncoder().encodeToString(ByteArray(16)))) assertFailsWith<ResponseStatusException>{ConnectionCipher(key).seal("synthetic-key","id")}
  assertFailsWith<ResponseStatusException>{ConnectionCipher(Base64.getEncoder().encodeToString(ByteArray(32){42})).open(cipher.seal("synthetic-key","id"),"id")}
 }
}

@SpringBootTest
class ModelConnectionIsolationTests(@Autowired val auth:P1Auth,@Autowired val jdbc:JdbcTemplate) {
 @Test fun `private connections are isolated even from other owners of the same organization`() {
  val user=UUID.randomUUID();val other=UUID.randomUUID();val org=UUID.randomUUID();val personal=UUID.randomUUID();val connection=UUID.randomUUID()
  auth.tx.execute {
   jdbc.update("INSERT INTO app_users(id,email,name,password_hash,personal_org_id,default_org_id) VALUES(?,?,?,?,?,?)",user,"$user@example.test","test","unused",org,org)
   jdbc.update("INSERT INTO organizations VALUES(?,'Personal',?)",org,user)
   jdbc.update("INSERT INTO memberships VALUES(?,?,'owner')",user,org)
   jdbc.update("INSERT INTO app_users(id,email,name,password_hash,personal_org_id,default_org_id) VALUES(?,?,?,?,?,?)",other,"$other@example.test","test","unused",personal,personal)
   jdbc.update("INSERT INTO organizations VALUES(?,'Personal',?)",personal,other)
   jdbc.update("INSERT INTO memberships VALUES(?,?,'owner')",other,personal)
   jdbc.update("INSERT INTO memberships VALUES(?,?,'owner')",other,org)
  }
  try {
   auth.scoped(Actor(user,org,"owner","")){jdbc.update("INSERT INTO model_connections(org_id,id,owner_id,name,provider,encrypted_key) VALUES(?,?,?,'Test','openai','ciphertext')",org,connection,user)}
   assertEquals(1,auth.scoped(Actor(user,org,"owner","")){jdbc.queryForObject("SELECT count(*) FROM model_connections WHERE id=?",Int::class.java,connection)})
   for(actor in listOf(Actor(other,org,"owner",""),Actor(user,personal,"owner",""))) auth.scoped(actor) {
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM model_connections WHERE id=?",Int::class.java,connection))
    assertEquals(0,jdbc.update("DELETE FROM model_connections WHERE id=?",connection))
   }
   assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM model_connections",Int::class.java))
   val response=MockHttpServletResponse()
   auth.createSession(user,response)
   val token=response.getHeader("Set-Cookie")!!.substringAfter('=').substringBefore(';')
   val request=MockHttpServletRequest()
   request.setCookies(jakarta.servlet.http.Cookie("qc_session",token))
   request.addHeader("Origin","http://127.0.0.1:3100")
   val cipher=ConnectionCipher(Base64.getEncoder().encodeToString(ByteArray(32){7}))
   val controller=ModelConnections(auth,cipher)
   val secret="synthetic-key-not-live"
   val created=controller.create(mapOf("name" to "Private key","provider" to "smart-router","apiKey" to secret),request)
   assertFalse(created.toString().contains(secret));assertFalse(controller.list(request).toString().contains("encrypted"))
   val savedId=created["id"] as UUID
   val stored=auth.scoped(Actor(user,org,"owner","")){jdbc.queryForObject("SELECT encrypted_key FROM model_connections WHERE id=?",String::class.java,savedId)!!}
   assertEquals(secret,cipher.open(stored,"$org:$user:$savedId:smart-router"))
   assertFailsWith<ResponseStatusException>{controller.create(mapOf("name" to "Bad","provider" to "custom","apiKey" to secret),request)}
   assertFailsWith<ResponseStatusException>{ModelConnections(auth,ConnectionCipher("")).create(mapOf("name" to "Disabled","provider" to "openai","apiKey" to secret),request)}
   controller.replace(savedId,mapOf("apiKey" to "replacement-synthetic-key"),request)
   val rotated=auth.scoped(Actor(user,org,"owner","")){jdbc.queryForObject("SELECT encrypted_key FROM model_connections WHERE id=?",String::class.java,savedId)!!}
   assertNotEquals(stored,rotated)
   assertEquals("replacement-synthetic-key",cipher.open(rotated,"$org:$user:$savedId:smart-router"))
   val otherResponse=MockHttpServletResponse();auth.createSession(other,otherResponse)
   val otherRequest=MockHttpServletRequest()
   otherRequest.setCookies(jakarta.servlet.http.Cookie("qc_session",otherResponse.getHeader("Set-Cookie")!!.substringAfter('=').substringBefore(';')))
   otherRequest.addHeader("Origin","http://127.0.0.1:3100")
   auth.jdbc.update("UPDATE sessions SET active_org_id=? WHERE user_id=?",org,other)
   assertFailsWith<ResponseStatusException>{controller.replace(savedId,mapOf("apiKey" to "forged-replacement-key"),otherRequest)}
   assertFalse(controller.list(otherRequest).any{it["id"]==savedId})
   controller.remove(savedId,request)
   assertFalse(controller.list(request).any{it["id"]==savedId})

  } finally {
   auth.scoped(Actor(user,org,"owner","")){jdbc.update("DELETE FROM model_connections WHERE id=?",connection)}
   auth.tx.execute {jdbc.update("DELETE FROM sessions WHERE user_id IN (?,?)",user,other);jdbc.update("DELETE FROM memberships WHERE user_id IN (?,?)",user,other);jdbc.update("DELETE FROM organizations WHERE id IN (?,?)",org,personal);jdbc.update("DELETE FROM app_users WHERE id IN (?,?)",user,other)}
  }
 }
}
