package com.quantumhorizon.quantumcrews

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

typealias Obj = Map<String, Any?>
fun fail(code: Int, message: String): Nothing = throw ResponseStatusException(HttpStatus.valueOf(code), message)
fun Obj.str(key: String): String = this[key] as? String ?: fail(400,"$key is required")
fun Obj.num(key: String): Int { val n=this[key] as? Number ?: fail(400,"$key is required");val i=n.toInt();if(n.toDouble()!=i.toDouble()) fail(400,"$key must be an integer");return i }
@Suppress("UNCHECKED_CAST") fun Obj.obj(key: String): Obj = this[key] as? Obj ?: fail(400,"$key must be an object")
@Suppress("UNCHECKED_CAST") fun Obj.list(key: String): List<Obj> = (this[key] as? List<*>)?.map { it as? Obj ?: fail(400,"Invalid $key") } ?: fail(400,"$key must be a list")
fun uuid(value: String): UUID = try { UUID.fromString(value) } catch (_: Exception) { fail(400,"Invalid identifier") }
fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
data class Actor(val userId: UUID, val orgId: UUID, val role: String, val tokenHash: String)

@Service
class P1Auth(val jdbc: JdbcTemplate, val tx: TransactionTemplate,
 @org.springframework.beans.factory.annotation.Value("\${quantum.allowed-origins}") private val origins: String,
 @org.springframework.beans.factory.annotation.Value("\${quantum.secure-cookies}") private val secureCookies: Boolean,
 @org.springframework.beans.factory.annotation.Value("\${quantum.cookie-name}") private val cookieName: String,
 @org.springframework.beans.factory.annotation.Value("\${quantum.test-mode}") private val testMode:Boolean,
 @org.springframework.beans.factory.annotation.Value("\${quantum.allow-live}") private val allowLive:Boolean) {
    private val random = SecureRandom()
    fun password(value: String, salt: ByteArray): String = Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(value.toCharArray(),salt,600000,256)).encoded)
    fun checkOrigin(req: HttpServletRequest) {
        val origin = req.getHeader("Origin")
        if ((origin != null && origin !in origins.split(",").map { it.trim() }) || req.getHeader("Sec-Fetch-Site") == "cross-site") fail(403,"Request origin is not allowed")
    }
    fun token(req: HttpServletRequest): String = req.cookies?.firstOrNull { it.name == cookieName }?.value ?: fail(401,"Sign in to continue")
    fun actor(req: HttpServletRequest, write: Boolean = false): Actor {
        if (write) checkOrigin(req)
        val rows = jdbc.queryForList("SELECT s.user_id,s.active_org_id,m.role FROM sessions s LEFT JOIN memberships m ON m.user_id=s.user_id AND m.org_id=s.active_org_id WHERE s.token_hash=? AND s.expires_at>now()",hash(token(req)))
        if(rows.isEmpty()) fail(401,"Session expired or organization access revoked")
        var r=rows[0]
        if(r["role"]==null) {
            val personal=jdbc.queryForObject("SELECT personal_org_id FROM app_users WHERE id=?",UUID::class.java,r["user_id"])!!
            tx.execute { jdbc.update("UPDATE sessions SET active_org_id=? WHERE token_hash=?",personal,hash(token(req)));jdbc.update("UPDATE app_users SET default_org_id=? WHERE id=?",personal,r["user_id"]) }
            r=mapOf("user_id" to r["user_id"],"active_org_id" to personal,"role" to "owner")
        }
        val actor=Actor(r["user_id"] as UUID,r["active_org_id"] as UUID,r["role"] as String,hash(token(req)))
        if(write) durableLimit(jdbc,"writes:${actor.userId}",120)
        if(write && actor.role=="viewer") fail(403,"This organization is read-only")
        return actor
    }
    fun <T> scoped(actor: Actor, block: () -> T): T = tx.execute { jdbc.queryForObject("SELECT set_config('app.org_id', ?, true)",String::class.java,actor.orgId.toString()); jdbc.queryForObject("SELECT set_config('app.user_id', ?, true)",String::class.java,actor.userId.toString()); block() }
    fun cookie(response: HttpServletResponse, value: String, maxAge: Int = 86400) { response.addHeader("Set-Cookie","$cookieName=$value; HttpOnly; SameSite=Strict; Path=/; Max-Age=$maxAge" + if(secureCookies) "; Secure" else "") }
    fun createSession(userId: UUID, response: HttpServletResponse) {
        val value=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val org=jdbc.queryForObject("SELECT default_org_id FROM app_users WHERE id=?",UUID::class.java,userId)!!
        val actual=if(jdbc.queryForObject("SELECT count(*) FROM memberships WHERE user_id=? AND org_id=?",Int::class.java,userId,org)==1) org else jdbc.queryForObject("SELECT personal_org_id FROM app_users WHERE id=?",UUID::class.java,userId)!!
        if(actual!=org) jdbc.update("UPDATE app_users SET default_org_id=? WHERE id=?",actual,userId)
        jdbc.update("INSERT INTO sessions VALUES(?,?,?,now()+interval '1 day')",hash(value),userId,actual)
        cookie(response,value)
    }
    fun me(actor: Actor): Obj {
        val user=jdbc.queryForMap("SELECT id,name,email,(email_verified_at IS NOT NULL) AS email_verified FROM app_users WHERE id=?",actor.userId)
        val orgs=jdbc.queryForList("SELECT o.id,o.name,m.role,(o.personal_owner_id IS NOT NULL) AS personal FROM memberships m JOIN organizations o ON o.id=m.org_id WHERE m.user_id=? ORDER BY personal DESC,o.name",actor.userId)
        return mapOf("user" to user,"organizations" to orgs,"activeOrganizationId" to actor.orgId,"capabilities" to mapOf("testMode" to testMode,"liveMode" to allowLive))
    }
}

@RestController
@RequestMapping("/api")
class AuthController(private val auth: P1Auth, private val identity:PublicIdentity) {
    private fun limit(req:HttpServletRequest,b:Obj) {identity.throttle(req,(b["email"] as? String)?.trim()?.lowercase()?.take(254)?:"", "signin")}
    @PostMapping("/auth/register") fun register(@RequestBody body: Obj,req: HttpServletRequest,res: HttpServletResponse): Obj {
        auth.checkOrigin(req);limit(req,body)
        if(!identity.testMode && !identity.mail.ready()) fail(503,"Account email delivery is not configured")
        val email=body.str("email").trim().lowercase();val name=body.str("name").trim();val pass=body.str("password")
        if(!email.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) || email.length>254 || name.length !in 1..80 || pass.length !in 12..256) fail(400,"Use a valid email, a name, and a password of 12–256 characters")
        val salt=ByteArray(16).also(SecureRandom()::nextBytes);val stored=Base64.getEncoder().encodeToString(salt)+":"+auth.password(pass,salt)
        val user=UUID.randomUUID();val org=UUID.randomUUID()
        try { auth.tx.execute {
            auth.jdbc.update("INSERT INTO app_users(id,email,name,password_hash,personal_org_id,default_org_id) VALUES(?,?,?,?,?,?)",user,email,name,stored,org,org)
            auth.jdbc.update("INSERT INTO organizations VALUES(?,'Personal',?)",org,user)
            auth.jdbc.update("INSERT INTO memberships VALUES(?,?,'owner')",user,org)
            if(identity.testMode) auth.createSession(user,res)
        } } catch (_: org.springframework.dao.DuplicateKeyException) {
            if(identity.testMode) fail(409,"Account already exists")
            return mapOf("message" to "Check your email to verify a new account, or sign in if you already have one")
        }
        if(!identity.testMode) {
            identity.enqueue(user,email,"verify")
            return mapOf("message" to "Check your email to verify a new account, or sign in if you already have one")
        }
        return auth.me(Actor(user,org,"owner",""))
    }
    @PostMapping("/auth/login") fun login(@RequestBody body: Obj,req: HttpServletRequest,res: HttpServletResponse): Obj {
        auth.checkOrigin(req);limit(req,body)
        val rows=auth.jdbc.queryForList("SELECT id,password_hash FROM app_users WHERE email=?",body.str("email").trim().lowercase())
        val pass=body.str("password");if(pass.length>256) fail(401,"Invalid email or password")
        val stored=rows.firstOrNull()?.get("password_hash") as? String ?: "AAAAAAAAAAAAAAAAAAAAAA==:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val parts=stored.split(':');val calculated=auth.password(pass,Base64.getDecoder().decode(parts[0]))
        if(rows.isEmpty() || !MessageDigest.isEqual(calculated.toByteArray(),parts[1].toByteArray())) fail(401,"Invalid email or password")
        val user=rows[0]["id"] as UUID;auth.createSession(user,res)
        val org=auth.jdbc.queryForObject("SELECT default_org_id FROM app_users WHERE id=?",UUID::class.java,user)!!
        return auth.me(Actor(user,org,"owner",""))
    }
    @GetMapping("/me") fun me(req: HttpServletRequest)=auth.me(auth.actor(req))
    @PostMapping("/auth/logout") fun logout(req: HttpServletRequest,res: HttpServletResponse): Obj { auth.checkOrigin(req);auth.jdbc.update("DELETE FROM sessions WHERE token_hash=?",hash(auth.token(req)));auth.cookie(res,"",0);return mapOf("ok" to true) }
    @PostMapping("/organizations") fun create(@RequestBody body: Obj,req: HttpServletRequest): Obj {
        val actor=auth.actor(req);auth.checkOrigin(req);val name=body.str("name").trim();if(name.length !in 1..80) fail(400,"Organization name must be 1–80 characters")
        val org=UUID.randomUUID();auth.tx.execute { auth.jdbc.update("INSERT INTO organizations VALUES(?,?,NULL)",org,name);auth.jdbc.update("INSERT INTO memberships VALUES(?,?,'owner')",actor.userId,org) }
        return mapOf("id" to org,"name" to name,"role" to "owner","personal" to false)
    }
    @PostMapping("/organizations/active") fun active(@RequestBody body: Obj,req: HttpServletRequest): Obj {
        val actor=auth.actor(req);auth.checkOrigin(req);val org=uuid(body.str("organizationId"))
        if(auth.jdbc.queryForObject("SELECT count(*) FROM memberships WHERE user_id=? AND org_id=?",Int::class.java,actor.userId,org)!=1) fail(403,"Organization access denied")
        auth.tx.execute { auth.jdbc.update("UPDATE sessions SET active_org_id=? WHERE token_hash=?",org,actor.tokenHash);auth.jdbc.update("UPDATE app_users SET default_org_id=? WHERE id=?",org,actor.userId) }
        return mapOf("ok" to true)
    }
    @PostMapping("/organizations/{orgId}/members") fun member(@PathVariable orgId: UUID,@RequestBody body: Obj,req: HttpServletRequest): Obj {
        if(!identity.testMode) fail(403,"Direct membership assignment is development-only; verified invitations are required")
        val actor=auth.actor(req,true);if(actor.orgId!=orgId || actor.role!="owner") fail(403,"Only an organization owner can manage members")
        if(auth.jdbc.queryForObject("SELECT personal_owner_id IS NOT NULL FROM organizations WHERE id=?",Boolean::class.java,orgId)==true) fail(400,"Personal organizations are private")
        val role=body.str("role");if(role !in setOf("member","viewer")) fail(400,"Use member or viewer")
        val user=auth.jdbc.queryForList("SELECT id FROM app_users WHERE email=?",body.str("email").trim().lowercase()).firstOrNull()?.get("id") ?: fail(404,"User not found")
        auth.jdbc.update("INSERT INTO memberships VALUES(?,?,?) ON CONFLICT(user_id,org_id) DO UPDATE SET role=excluded.role WHERE memberships.role<>'owner'",user,orgId,role)
        return mapOf("ok" to true)
    }
}

@RestControllerAdvice
class P1Errors {
    @ExceptionHandler(ResponseStatusException::class)
    fun expected(error: ResponseStatusException): org.springframework.http.ResponseEntity<Obj> = org.springframework.http.ResponseEntity.status(error.statusCode).body(mapOf("message" to error.reason))
    @ExceptionHandler(org.springframework.dao.DataAccessException::class)
    fun database(error: org.springframework.dao.DataAccessException): org.springframework.http.ResponseEntity<Obj> = org.springframework.http.ResponseEntity.status(409).body(mapOf("message" to "The change conflicts with current data"))
}
