package com.quantumhorizon.quantumcrews

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

@Service
class Registry(val auth: P1Auth, val json: ObjectMapper,
 @Value("\${quantum.runtime-url}") val runtimeUrl: String,
 @Value("\${quantum.runtime-token}") val runtimeToken: String,
 @Value("\${quantum.test-mode}") val testMode: Boolean,
 @Value("\${quantum.allow-live}") val allowLive: Boolean) {
 val jdbc=auth.jdbc
 val http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build()
 fun encode(value: Any?): String=json.writeValueAsString(value)
 fun decode(value: Any?): Obj=json.readValue(value.toString(),object:TypeReference<Map<String,Any?>>() {})
 fun mode(body: Obj): String { val mode=body.str("mode");if(mode=="test" && !testMode) fail(403,"Simulated mode is disabled");if(mode=="live" && !allowLive) fail(403,"Live calls require administrator enablement after approval");if(mode !in setOf("test","live")) fail(400,"Invalid execution mode");return mode }
 fun runtime(path: String, body: Obj, seconds: Long=100): Obj {
  if(runtimeToken.isBlank()) fail(503,"Runtime authentication is not configured")
  val request=HttpRequest.newBuilder(URI.create("$runtimeUrl/$path")).header("Authorization","Bearer $runtimeToken").header("Content-Type","application/json").timeout(Duration.ofSeconds(seconds)).POST(HttpRequest.BodyPublishers.ofString(encode(body))).build()
  val response=try {http.send(request,HttpResponse.BodyHandlers.ofString())} catch(_:Exception){fail(503,"Runtime did not complete the request")}
  if(response.statusCode()!=200) fail(502,"Runtime rejected or failed the request (${response.statusCode()})")
  return decode(response.body())
 }
 fun runtimeIdentity(): Obj {
  val request=HttpRequest.newBuilder(URI.create("$runtimeUrl/health")).timeout(Duration.ofSeconds(3)).GET().build()
  val response=try {http.send(request,HttpResponse.BodyHandlers.ofString())} catch(_:Exception){fail(503,"Runtime unavailable")}
  if(response.statusCode()!=200) fail(503,"Runtime unavailable")
  val data=decode(response.body());return mapOf("name" to "crewai","version" to data.str("version"),"lockSha256" to data.str("lockSha256"),"contractVersion" to 1)
 }
 fun version(id: UUID, revision: Int? = null): Obj {
  val rows=jdbc.queryForList("SELECT c.org_id,c.id,c.kind,v.revision,v.name,v.spec,v.created_at FROM components c JOIN component_versions v ON v.org_id=c.org_id AND v.component_id=c.id WHERE c.id=? AND v.revision=COALESCE(?,c.revision)",id,revision)
  if(rows.isEmpty()) fail(404,"Definition not found in this organization")
  val row=rows[0];return mapOf("organizationId" to row["org_id"],"schemaVersion" to 1,"id" to row["id"],"kind" to row["kind"],"revision" to row["revision"],"name" to row["name"],"spec" to decode(row["spec"]),"createdAt" to row["created_at"].toString())
 }
 fun validate(kind: String,name: String,spec: Obj) {
  if(name.trim().length !in 1..120) fail(400,"Name must be 1–120 characters")
  fun text(key:String,max:Int=12000) {if(spec.str(key).trim().length !in 1..max) fail(400,"$key is empty or too long")}
  fun fields(vararg keys:String) {if(spec.keys!=keys.toSet()) fail(400,"Unsupported fields for $kind")}
  when(kind) {
   "agent" -> {fields("role","instructions");text("role",200);text("instructions")}
   "task" -> {fields("instructions","agentId","agentRevision");text("instructions");if(version(uuid(spec.str("agentId")),spec.num("agentRevision"))["kind"]!="agent") fail(400,"Task must reference an agent")}
   "crew" -> {
    fields("agents","tasks");val agents=spec.list("agents");val tasks=spec.list("tasks")
    if(agents.size !in 1..4 || tasks.size !in 1..4) fail(400,"P1 teams need 1–4 members and tasks")
    if(agents.map{it.str("id")}.toSet().size!=agents.size || tasks.map{it.str("id")}.toSet().size!=tasks.size) fail(400,"Duplicate team references")
    agents.forEach {if(it.keys!=setOf("id","revision") || version(uuid(it.str("id")),it.num("revision"))["kind"]!="agent") fail(400,"Invalid team member")}
    tasks.forEach {ref ->
     if(ref.keys!=setOf("id","revision")) fail(400,"Invalid task reference")
     val task=version(uuid(ref.str("id")),ref.num("revision"));if(task["kind"]!="task") fail(400,"Invalid task")
     val ts=task.obj("spec");if(agents.none {it.str("id")==ts.str("agentId") && it.num("revision")==ts.num("agentRevision")}) fail(400,"Task's pinned member revision must belong to the team")
    }
   }
   else -> fail(400,"Unsupported component kind")
  }
 }
 fun save(actor: Actor,kind: String,name: String,spec: Obj,id: UUID?=null,base: Int=0): Obj {
  validate(kind,name,spec)
  val component=id?:UUID.randomUUID()
  val revision=if(id==null) {jdbc.update("INSERT INTO components VALUES(?,?,?,1)",actor.orgId,component,kind);1} else {
   val row=jdbc.queryForList("SELECT revision,kind FROM components WHERE id=? FOR UPDATE",id).firstOrNull()?:fail(404,"Definition not found")
   if(row["kind"]!=kind || row["revision"]!=base) fail(409,"Definition changed; reload and review the current revision")
   jdbc.update("UPDATE components SET revision=revision+1 WHERE id=?",id);base+1
  }
  jdbc.update("INSERT INTO component_versions(org_id,component_id,revision,name,spec,author_id,content_hash) VALUES(?,?,?,?,?::jsonb,?,?)",actor.orgId,component,revision,name.trim(),encode(spec),actor.userId,hash(encode(mapOf("name" to name.trim(),"spec" to spec))))
  return version(component,revision)
 }
 fun proposal(row: Map<String,Any?>): Obj=mapOf("id" to row["id"],"agentId" to row["agent_id"],"baseRevision" to row["base_revision"],"proposed" to decode(row["proposed"]),"patch" to json.readValue(row["patch"].toString(),List::class.java),"status" to row["status"],"simulated" to row["simulated"])
}

@RestController
@RequestMapping("/api")
class RegistryController(val reg: Registry) {
 val auth=reg.auth;val jdbc=reg.jdbc
 @GetMapping("/components") fun all(req:HttpServletRequest):List<Obj> {val a=auth.actor(req);return auth.scoped(a){jdbc.queryForList("SELECT id FROM components ORDER BY kind,id").map{reg.version(it["id"] as UUID)}}}
 @PostMapping("/components") fun create(@RequestBody b:Obj,req:HttpServletRequest):Obj {val a=auth.actor(req,true);if(b.keys!=setOf("kind","name","spec")) fail(400,"Unsupported definition fields");return auth.scoped(a){reg.save(a,b.str("kind"),b.str("name"),b.obj("spec"))}}
 @PutMapping("/components/{id}") fun update(@PathVariable id:UUID,@RequestBody b:Obj,req:HttpServletRequest):Obj {val a=auth.actor(req,true);if(b.keys!=setOf("baseRevision","name","spec")) fail(400,"Unsupported definition fields");return auth.scoped(a){reg.save(a,reg.version(id).str("kind"),b.str("name"),b.obj("spec"),id,b.num("baseRevision"))}}
 @GetMapping("/components/{id}/history") fun history(@PathVariable id:UUID,req:HttpServletRequest):List<Obj> {val a=auth.actor(req);return auth.scoped(a){reg.version(id);jdbc.queryForList("SELECT revision FROM component_versions WHERE component_id=? ORDER BY revision DESC",id).map{reg.version(id,it["revision"] as Int)}}}
 @PostMapping("/components/{id}/rollback") fun rollback(@PathVariable id:UUID,@RequestBody b:Obj,req:HttpServletRequest):Obj {val a=auth.actor(req,true);return auth.scoped(a){val old=reg.version(id,b.num("targetRevision"));reg.save(a,old.str("kind"),old.str("name"),old.obj("spec"),id,b.num("baseRevision"))}}
 @GetMapping("/proposals") fun proposals(req:HttpServletRequest):List<Obj> {val a=auth.actor(req);return auth.scoped(a){jdbc.queryForList("SELECT * FROM proposals ORDER BY created_at DESC").map(reg::proposal)}}
 @PostMapping("/proposals") fun propose(@RequestBody b:Obj,req:HttpServletRequest):Obj {
  val a=auth.actor(req,true);if(b.keys.any{it !in setOf("prompt","agentId","mode")}) fail(400,"Unsupported proposal fields")
  val prompt=b.str("prompt").trim();if(prompt.length !in 1..4000) fail(400,"Describe the proposed change in 1–4000 characters")
  val mode=reg.mode(b);val id=(b["agentId"] as? String)?.let(::uuid)
  val base=auth.scoped(a){id?.let{reg.version(it)}};if(base!=null && base["kind"]!="agent") fail(400,"P1 proposals support team members")
  val candidate=reg.runtime("propose",mapOf("prompt" to prompt,"base" to base,"mode" to mode),45)
  val proposed=mapOf("name" to candidate.str("name"),"spec" to mapOf("role" to candidate.str("role"),"instructions" to candidate.str("instructions")))
  val renewed=auth.actor(req,true);if(renewed.orgId!=a.orgId) fail(409,"Organization changed while generating the proposal")
  return auth.scoped(renewed) {
   reg.validate("agent",proposed.str("name"),proposed.obj("spec"))
   val revision=(base?.get("revision") as? Number)?.toInt()?:0
   if(id!=null && reg.version(id)["revision"]!=revision) fail(409,"Definition changed while generating proposal")
   val patch=if(base==null) listOf(mapOf("op" to "add","path" to "/name","value" to proposed["name"]),mapOf("op" to "add","path" to "/spec","value" to proposed["spec"])) else listOf("name","role","instructions").mapNotNull{ key ->
    val value=if(key=="name") proposed[key] else proposed.obj("spec")[key]
    val previous=if(key=="name") base?.get(key) else base?.obj("spec")?.get(key)
    if(previous==value) null else mapOf("op" to if(base==null) "add" else "replace","path" to if(key=="name") "/name" else "/spec/$key","value" to value)
   }
   val pid=UUID.randomUUID();jdbc.update("INSERT INTO proposals(org_id,id,agent_id,base_revision,proposed,patch,simulated,author_id) VALUES(?,?,?,?,?::jsonb,?::jsonb,?,?)",a.orgId,pid,id,revision,reg.encode(proposed),reg.encode(patch),mode=="test",a.userId)
   reg.proposal(jdbc.queryForMap("SELECT * FROM proposals WHERE id=?",pid))
  }
 }
 @PostMapping("/proposals/{id}/approve") fun approve(@PathVariable id:UUID,@RequestBody b:Obj,req:HttpServletRequest):Obj {
  val a=auth.actor(req,true);if(b.keys!=setOf("proposed","baseRevision")) fail(400,"Unsupported approval fields")
  val p=b.obj("proposed");if(p.keys!=setOf("name","spec")) fail(400,"Approval cannot change permissions or identity")
  return auth.scoped(a){
   val row=jdbc.queryForList("SELECT * FROM proposals WHERE id=? FOR UPDATE",id).firstOrNull()?:fail(404,"Proposal not found")
   if(row["status"]!="pending" || row["base_revision"]!=b.num("baseRevision")) fail(409,"Proposal is stale or already resolved")
   val saved=reg.save(a,"agent",p.str("name"),p.obj("spec"),row["agent_id"] as? UUID,row["base_revision"] as Int)
   jdbc.update("INSERT INTO approvals(org_id,id,proposal_id,user_id,content_hash,component_id,revision) VALUES(?,?,?,?,?,?,?)",a.orgId,UUID.randomUUID(),id,a.userId,hash(reg.encode(mapOf("name" to saved["name"],"spec" to saved["spec"]))),saved["id"],saved["revision"])
   jdbc.update("UPDATE proposals SET status='approved',proposed=?::jsonb WHERE id=?",reg.encode(p),id)
   saved
  }
 }
 @PostMapping("/proposals/{id}/reject") fun reject(@PathVariable id:UUID,req:HttpServletRequest):Obj {val a=auth.actor(req,true);return auth.scoped(a){if(jdbc.update("UPDATE proposals SET status='rejected' WHERE id=? AND status='pending'",id)!=1) fail(409,"Proposal already resolved or unavailable");mapOf("ok" to true)}}
}
