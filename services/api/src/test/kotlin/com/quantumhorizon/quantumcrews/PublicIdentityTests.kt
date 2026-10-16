package com.quantumhorizon.quantumcrews

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.web.server.ResponseStatusException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID
import java.util.Base64
import kotlin.test.*

@SpringBootTest
class PublicIdentityTests(@Autowired val identity:PublicIdentity) {
 val auth get()=identity.auth
 @Test fun `verification reset and model access fail closed across identity and key changes`() {
  val user=UUID.randomUUID();val org=UUID.randomUUID();val a=Actor(user,org,"owner","")
  auth.tx.execute {
   auth.jdbc.update("INSERT INTO app_users(id,email,name,password_hash,personal_org_id,default_org_id) VALUES(?,?,?,?,?,?)",user,"$user@example.test","Synthetic","unused",org,org)
   auth.jdbc.update("INSERT INTO organizations VALUES(?,'Personal',?)",org,user)
   auth.jdbc.update("INSERT INTO memberships VALUES(?,?,'owner')",user,org)
  }
  try {
   assertFailsWith<ResponseStatusException>{identity.requireVerified(user)}
   val first=identity.issue(user,"verify");val second=identity.issue(user,"verify")
   assertFailsWith<ResponseStatusException>{identity.redeem(first,"verify") {}}
   assertFailsWith<ResponseStatusException>{identity.redeem(second,"reset") {}}
   identity.redeem(second,"verify"){auth.jdbc.update("UPDATE app_users SET email_verified_at=now() WHERE id=?",it)}
   identity.requireVerified(user)
   assertFailsWith<ResponseStatusException>{identity.redeem(second,"verify") {}}
   val race=identity.issue(user,"verify")
   val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
   try {
    val results=pool.invokeAll(List(2){java.util.concurrent.Callable {try{identity.redeem(race,"verify"){};true}catch(_:ResponseStatusException){false}}}).map{it.get()}
    assertEquals(1,results.count{it})
   } finally {pool.shutdownNow()}
   val expired=identity.issue(user,"reset");auth.jdbc.update("UPDATE identity_tokens SET expires_at=now()-interval '1 second' WHERE token_hash=?",hash(expired))
   assertFailsWith<ResponseStatusException>{identity.redeem(expired,"reset") {}}
   val response=MockHttpServletResponse();auth.createSession(user,response)
   val token=identity.issue(user,"reset")
   val req=MockHttpServletRequest();req.addHeader("Origin","http://127.0.0.1:3100")
   IdentityController(identity).reset(mapOf("token" to token,"password" to "synthetic-long-password"),req)
   assertEquals(0,auth.jdbc.queryForObject("SELECT count(*) FROM sessions WHERE user_id=?",Int::class.java,user))
   assertFailsWith<ResponseStatusException>{identity.redeem(token,"reset") {}}
   assertFalse(httpsOrigin("https://example.test/path"));assertFalse(httpsOrigin("https://user@example.test"));assertFalse(httpsOrigin("https://"))
  val cipher=ConnectionCipher(Base64.getEncoder().encodeToString(ByteArray(32){4}));val access=ModelAccess(auth,cipher,identity)
   val id=UUID.randomUUID();val encrypted=cipher.seal("synthetic-api-key","$org:$user:$id:openai")
   auth.scoped(a){auth.jdbc.update("INSERT INTO model_connections(org_id,id,owner_id,name,provider,encrypted_key) VALUES(?,?,?,'Synthetic','openai',?)",org,id,user,encrypted)}
   val config=access.configuration(a,mapOf("connectionId" to id.toString(),"plannerModel" to "test-planner","workerModel" to "test-worker"))
   auth.jdbc.update("UPDATE app_users SET email_verified_at=NULL WHERE id=?",user)
   assertFailsWith<ResponseStatusException>{access.configuration(a,mapOf("connectionId" to id.toString(),"plannerModel" to "test","workerModel" to "test"))}
   auth.jdbc.update("UPDATE app_users SET email_verified_at=now() WHERE id=?",user)
   assertFalse(config.toString().contains("synthetic-api-key"))
   assertEquals("synthetic-api-key",access.resolve(a,config)["apiKey"])
   auth.jdbc.update("UPDATE memberships SET role='viewer' WHERE user_id=? AND org_id=?",user,org)
   assertFailsWith<ResponseStatusException>{access.resolve(a,config)}
   auth.jdbc.update("UPDATE memberships SET role='owner' WHERE user_id=? AND org_id=?",user,org)
   auth.scoped(a){auth.jdbc.update("UPDATE model_connections SET key_revision=key_revision+1 WHERE id=?",id)}
   assertFailsWith<ResponseStatusException>{access.resolve(a,config)}
   auth.scoped(a){auth.jdbc.update("DELETE FROM model_connections WHERE id=?",id)}
   assertFailsWith<ResponseStatusException>{access.resolve(a,config)}
  } finally {
   auth.scoped(a){auth.jdbc.update("DELETE FROM model_connections WHERE owner_id=?",user)}
   auth.tx.execute{auth.jdbc.update("DELETE FROM identity_tokens WHERE user_id=?",user);auth.jdbc.update("DELETE FROM sessions WHERE user_id=?",user);auth.jdbc.update("DELETE FROM memberships WHERE user_id=?",user);auth.jdbc.update("DELETE FROM organizations WHERE id=?",org);auth.jdbc.update("DELETE FROM app_users WHERE id=?",user)}
  }
 }
 @Test fun `rate limits survive service reconstruction and missing mail cannot send`() {
  val bucket=UUID.randomUUID().toString();identity.limit(bucket,1)
  assertFailsWith<ResponseStatusException>{PublicIdentity(auth,identity.mail,false).limit(bucket,1)}
  auth.jdbc.update("DELETE FROM auth_limits WHERE bucket=?",hash(bucket))
  assertFalse(identity.mail.ready())
  assertFailsWith<ResponseStatusException>{identity.mail.send("synthetic@example.test","verify","not-a-real-token")}
 }
}

class PublicReleaseGuardTests {
 @Test fun `public mode rejects incomplete configuration`() {
  val cipher=ConnectionCipher(Base64.getEncoder().encodeToString(ByteArray(32){1}))
  val mail=IdentityMail("synthetic","sender@example.test","https://quantum-crews.example.test",tools.jackson.databind.ObjectMapper())
  PublicReleaseGuard(mail,cipher,true,false,true,"https://quantum-crews.example.test").validate()
  for(guard in listOf(PublicReleaseGuard(mail,cipher,true,true,true,"https://quantum-crews.example.test"),PublicReleaseGuard(mail,cipher,true,false,false,"https://quantum-crews.example.test"),PublicReleaseGuard(mail,ConnectionCipher(""),true,false,true,"https://quantum-crews.example.test"),PublicReleaseGuard(mail,cipher,true,false,true,"http://localhost"))) assertFailsWith<IllegalStateException>{guard.validate()}
 }
}

class RequestBoundaryTests {
 @Test fun `oversized chunked requests are rejected before controller parsing`() {
  val request=MockHttpServletRequest("POST","/api/auth/register");request.setContent(ByteArray(131073))
  val response=MockHttpServletResponse();var reached=false
  RequestBoundary().doFilter(request,response,jakarta.servlet.FilterChain{_,_->reached=true})
  assertEquals(413,response.status);assertFalse(reached);assertEquals("no-store",response.getHeader("Cache-Control"))
 }
 @Test fun `bounded JSON body reaches the controller unchanged`() {
  val request=MockHttpServletRequest("POST","/api/auth/register");request.setContent("{\"email\":\"synthetic@example.test\"}".toByteArray())
  val response=MockHttpServletResponse();var body=""
  RequestBoundary().doFilter(request,response,jakarta.servlet.FilterChain{req,_->body=req.inputStream.readAllBytes().toString(Charsets.UTF_8)})
  assertEquals("{\"email\":\"synthetic@example.test\"}",body)
 }
}

@SpringBootTest
class PublicSignupTests(@Autowired val auth:P1Auth) {
 @Test fun `public signup is generic provisions Personal and sends a one time verification without exposing tokens`() {
  val sent=java.util.concurrent.CompletableFuture<Pair<String,String>>()
  val mail=object:IdentityMail("synthetic","sender@example.test","https://quantum-crews.example.test",tools.jackson.databind.ObjectMapper()) {
   override fun send(email:String,purpose:String,token:String){sent.complete(purpose to token)}
  }
  val identity=PublicIdentity(auth,mail,false);val controller=AuthController(auth,identity)
  val email="${UUID.randomUUID()}@example.test";val req=MockHttpServletRequest();req.addHeader("Origin","http://127.0.0.1:3100")
  var user:UUID?=null;var org:UUID?=null
  try {
   val body=mapOf("email" to email,"name" to "Synthetic signup","password" to "synthetic-long-password")
   val response=MockHttpServletResponse();val first=controller.register(body,req,response)
   val duplicate=controller.register(body,req,MockHttpServletResponse())
   assertEquals(first,duplicate);assertNull(response.getHeader("Set-Cookie"))
   val row=auth.jdbc.queryForMap("SELECT id,personal_org_id FROM app_users WHERE email=?",email)
   user=row["id"] as UUID;org=row["personal_org_id"] as UUID
   assertEquals(1,auth.jdbc.queryForObject("SELECT count(*) FROM memberships WHERE user_id=? AND org_id=? AND role='owner'",Int::class.java,user,org))
   val (purpose,token)=sent.get(5,java.util.concurrent.TimeUnit.SECONDS)
   assertEquals("verify",purpose);assertFalse(first.toString().contains(token))
   assertFailsWith<ResponseStatusException>{identity.requireVerified(user)}
   IdentityController(identity).verify(mapOf("token" to token),req)
   identity.requireVerified(user)
   assertFailsWith<ResponseStatusException>{IdentityController(identity).verify(mapOf("token" to token),req)}
   val known=IdentityController(identity).forgot(mapOf("email" to email),req)
   val unknown=IdentityController(identity).forgot(mapOf("email" to "${UUID.randomUUID()}@example.test"),req)
   assertEquals(known,unknown)
  } finally {
   identity.stopMail()
   if(user!=null) auth.tx.execute {auth.jdbc.update("DELETE FROM identity_tokens WHERE user_id=?",user);auth.jdbc.update("DELETE FROM sessions WHERE user_id=?",user);auth.jdbc.update("DELETE FROM memberships WHERE user_id=?",user);auth.jdbc.update("DELETE FROM organizations WHERE id=?",org);auth.jdbc.update("DELETE FROM app_users WHERE id=?",user)}
  }
 }
}
