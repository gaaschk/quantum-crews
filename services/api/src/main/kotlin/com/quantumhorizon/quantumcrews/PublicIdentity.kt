package com.quantumhorizon.quantumcrews

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

fun durableLimit(jdbc:org.springframework.jdbc.core.JdbcTemplate,bucket:String,max:Int) {
 val count=jdbc.queryForObject("""INSERT INTO auth_limits VALUES(?,now(),1) ON CONFLICT(bucket) DO UPDATE SET
  attempts=CASE WHEN auth_limits.started_at<now()-interval '15 minutes' THEN 1 ELSE auth_limits.attempts+1 END,
  started_at=CASE WHEN auth_limits.started_at<now()-interval '15 minutes' THEN now() ELSE auth_limits.started_at END RETURNING attempts""",Int::class.java,hash(bucket))!!
 if(count>max) fail(429,"Too many attempts; try again later")
}

fun httpsOrigin(value:String):Boolean = try {
 val uri=URI.create(value)
 uri.scheme=="https" && uri.host!=null && uri.userInfo==null && uri.query==null && uri.fragment==null && uri.path.isNullOrEmpty() && uri.port in setOf(-1,443)
} catch(_:Exception){false}

@Service
class IdentityMail(@Value("\${quantum.mail-key:}") private val apiKey:String,
 @Value("\${quantum.mail-from:}") private val sender:String,
 @Value("\${quantum.public-origin:http://127.0.0.1:3100}") private val origin:String,
 private val json:ObjectMapper) {
 fun ready()=apiKey.isNotBlank() && sender.isNotBlank() && httpsOrigin(origin)
 fun send(email:String,purpose:String,token:String) {
  if(!ready()) fail(503,"Account email delivery is not configured")
  val link="$origin/account/$purpose#token=$token"
  val request=HttpRequest.newBuilder(URI.create("https://api.resend.com/emails"))
   .header("Authorization","Bearer $apiKey").header("Content-Type","application/json")
   .timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(mapOf(
    "from" to sender,"to" to listOf(email),"subject" to if(purpose=="verify") "Verify your Quantum Crews email" else "Reset your Quantum Crews password",
    "text" to "Open this link to ${if(purpose=="verify") "verify your email" else "reset your password"}:\n$link\nThis link expires in 30 minutes. If you did not request it, ignore this email.")))).build()
  val response=try{HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(request,HttpResponse.BodyHandlers.discarding())}catch(_:Exception){fail(503,"Account email delivery is temporarily unavailable")}
  if(response.statusCode() !in 200..299) fail(503,"Account email delivery is temporarily unavailable")
 }
}

@Service
class PublicIdentity(val auth:P1Auth,val mail:IdentityMail,
 @Value("\${quantum.test-mode}") val testMode:Boolean) {
 @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000)
 fun cleanup() {
  auth.jdbc.update("DELETE FROM auth_limits WHERE started_at<now()-interval '1 day'")
  auth.jdbc.update("DELETE FROM identity_tokens WHERE expires_at<now()-interval '1 day'")
  auth.jdbc.update("DELETE FROM sessions WHERE expires_at<now()")
 }
 fun limit(bucket:String,max:Int) = durableLimit(auth.jdbc,bucket,max)
 fun throttle(req:HttpServletRequest,email:String,action:String) {
  limit("ip:${req.remoteAddr}:$action",300)
  limit("email:$email:$action",10)
 }
 fun requireVerified(user:UUID) {
  if(auth.jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM app_users WHERE id=?",Boolean::class.java,user)!=true) fail(403,"Verify your email before using models")
 }
 fun issue(user:UUID,purpose:String):String {
  val token=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
  auth.tx.execute {
   auth.jdbc.queryForObject("SELECT id FROM app_users WHERE id=? FOR UPDATE",UUID::class.java,user)
   auth.jdbc.update("DELETE FROM identity_tokens WHERE user_id=? AND purpose=?",user,purpose)
   auth.jdbc.update("INSERT INTO identity_tokens(token_hash,user_id,purpose,expires_at) VALUES(?,?,?,now()+interval '30 minutes')",hash(token),user,purpose)
  }
  return token
 }
 private val mailTasks=java.util.concurrent.ThreadPoolExecutor(2,2,0,java.util.concurrent.TimeUnit.SECONDS,
  java.util.concurrent.ArrayBlockingQueue<Runnable>(100),java.util.concurrent.ThreadFactory { task -> Thread(task,"identity-mail").apply{isDaemon=true} })
 @jakarta.annotation.PreDestroy fun stopMail(){mailTasks.shutdownNow();mailTasks.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS)}
 fun enqueue(user:UUID,email:String,purpose:String) {
  try{mailTasks.execute{try{deliver(user,email,purpose)}catch(_:Exception){ /* User may request a fresh email; no tokens or identities in logs. */ }}}
  catch(_:java.util.concurrent.RejectedExecutionException){ /* Return the same response; resend is rate limited and recoverable. */ }
 }
 fun deliver(user:UUID,email:String,purpose:String) {
  val token=issue(user,purpose)
  try{mail.send(email,purpose,token)}catch(e:Exception){auth.jdbc.update("DELETE FROM identity_tokens WHERE token_hash=?",hash(token));throw e}
 }
 fun redeem(token:String,purpose:String,change:(UUID)->Unit) {
  if(token.length !in 40..100) fail(400,"Link is invalid or expired")
  auth.tx.execute {
   // Lock the user first, matching issue/reset lock ordering across concurrent requests.
   val user=auth.jdbc.queryForList("SELECT user_id FROM identity_tokens WHERE token_hash=? AND purpose=?",hash(token),purpose).firstOrNull()?.get("user_id") as? UUID ?: fail(400,"Link is invalid or expired")
   auth.jdbc.queryForObject("SELECT id FROM app_users WHERE id=? FOR UPDATE",UUID::class.java,user)
   val count=auth.jdbc.update("UPDATE identity_tokens SET used_at=now() WHERE token_hash=? AND purpose=? AND used_at IS NULL AND expires_at>now()",hash(token),purpose)
   if(count!=1) fail(400,"Link is invalid or expired")
   change(user)
  }
 }
}

@RestController
@RequestMapping("/api/auth")
class IdentityController(private val identity:PublicIdentity) {
 private val auth=identity.auth
 @PostMapping("/verify") fun verify(@RequestBody b:Obj,req:HttpServletRequest):Obj {
  auth.checkOrigin(req);identity.limit("verify:${req.remoteAddr}",60)
  identity.redeem(b.str("token"),"verify"){auth.jdbc.update("UPDATE app_users SET email_verified_at=now() WHERE id=?",it)}
  return mapOf("ok" to true)
 }
 @PostMapping("/resend-verification") fun resend(req:HttpServletRequest):Obj {
  auth.checkOrigin(req);val actor=auth.actor(req);identity.limit("resend:${actor.userId}",3)
  val row=auth.jdbc.queryForMap("SELECT email,email_verified_at FROM app_users WHERE id=?",actor.userId)
  if(row["email_verified_at"]==null) identity.enqueue(actor.userId,row["email"] as String,"verify")
  return mapOf("message" to "If verification is needed, an email has been sent")
 }
 @PostMapping("/forgot-password") fun forgot(@RequestBody b:Obj,req:HttpServletRequest):Obj {
  auth.checkOrigin(req);val email=b.str("email").trim().lowercase();if(email.length>254) fail(400,"Invalid email")
  identity.throttle(req,email,"recovery")
  if(!identity.mail.ready()) fail(503,"Account email delivery is not configured")
  val row=auth.jdbc.queryForList("SELECT id FROM app_users WHERE email=?",email).firstOrNull()
  if(row!=null) identity.enqueue(row["id"] as UUID,email,"reset")
  return mapOf("message" to "If an account matches, a recovery email will arrive shortly")
 }
 @PostMapping("/reset-password") fun reset(@RequestBody b:Obj,req:HttpServletRequest):Obj {
  auth.checkOrigin(req);identity.limit("reset:${req.remoteAddr}",60)
  val pass=b.str("password");if(pass.length !in 12..256) fail(400,"Use a password of 12–256 characters")
  val salt=ByteArray(16).also(SecureRandom()::nextBytes)
  val stored=Base64.getEncoder().encodeToString(salt)+":"+auth.password(pass,salt)
  identity.redeem(b.str("token"),"reset") {user->
   auth.jdbc.update("UPDATE app_users SET password_hash=? WHERE id=?",stored,user)
   auth.jdbc.update("DELETE FROM sessions WHERE user_id=?",user)
   auth.jdbc.update("DELETE FROM identity_tokens WHERE user_id=?",user)
  }
  return mapOf("ok" to true)
 }
}

/** Public routing is permitted only after operator configuration passes this gate. */
@Service
class PublicReleaseGuard(private val mail:IdentityMail,private val cipher:ConnectionCipher,
 @Value("\${quantum.public-mode}") private val publicMode:Boolean,
 @Value("\${quantum.test-mode}") private val testMode:Boolean,
 @Value("\${quantum.secure-cookies}") private val secureCookies:Boolean,
 @Value("\${quantum.allowed-origins}") private val origins:String) {
 @jakarta.annotation.PostConstruct fun validate() {
  if(publicMode && (testMode || !secureCookies || !mail.ready() || !cipher.ready() || origins.split(',').any{!httpsOrigin(it.trim())}))
   throw IllegalStateException("Public mode requires HTTPS origins, secure cookies, account mail, encrypted credentials, and simulation disabled")
 }
}
