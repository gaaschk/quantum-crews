package com.quantumhorizon.quantumcrews

import jakarta.servlet.http.HttpServletRequest
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.util.UUID

@Service
@EnableScheduling
class RunService(val reg: Registry) {
 val auth=reg.auth;val jdbc=reg.jdbc
 fun event(org:UUID,id:UUID,type:String,detail:Obj=emptyMap()) {jdbc.update("INSERT INTO run_events(org_id,run_id,event_type,detail) VALUES(?,?,?,?::jsonb)",org,id,type,reg.encode(detail))}
 fun get(id:UUID):Obj {
  val row=jdbc.queryForList("SELECT r.*,s.snapshot FROM runs r JOIN run_snapshots s ON s.org_id=r.org_id AND s.run_id=r.id WHERE r.id=?",id).firstOrNull()?:fail(404,"Run not found")
  val events=jdbc.queryForList("SELECT id,event_type,detail,created_at FROM run_events WHERE run_id=? ORDER BY id",id).map{mapOf("id" to it["id"],"type" to it["event_type"],"detail" to reg.decode(it["detail"]),"createdAt" to it["created_at"].toString())}
  return mapOf("id" to row["id"],"status" to row["status"],"snapshot" to reg.decode(row["snapshot"]),"outputs" to reg.json.readValue(row["outputs"].toString(),List::class.java),"usage" to reg.decode(row["usage"]),"error" to row["error"],"simulated" to (row["mode"]=="test"),"events" to events,"createdAt" to row["created_at"].toString())
 }
 @Scheduled(fixedDelay=1000)
 fun work() {
  val orgs=jdbc.queryForList("SELECT id FROM organizations").map{it["id"] as UUID}
  for(org in orgs) {
   val system=Actor(UUID(0,0),org,"owner","")
   val job=auth.scoped(system) {
    val expired=jdbc.queryForList("UPDATE runs SET status='failed',error='Worker lease expired; execution outcome is uncertain. This run will not be retried automatically.' WHERE status='running' AND lease_until<now() RETURNING id")
    expired.forEach{event(org,it["id"] as UUID,"failed",mapOf("reason" to "lease_expired"))}
    val row=jdbc.queryForList("SELECT * FROM runs WHERE status='queued' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED").firstOrNull()
    if(row==null) null else {
     val id=row["id"] as UUID
     val permissions=jdbc.queryForList("SELECT role FROM memberships WHERE user_id=? AND org_id=?",row["requested_by"],org)
     if(permissions.isEmpty() || permissions[0]["role"]=="viewer") {jdbc.update("UPDATE runs SET status='failed',error='Execution permission was revoked' WHERE id=?",id);event(org,id,"failed");null}
     else {val attempt=UUID.randomUUID();jdbc.update("UPDATE runs SET status='running',attempt_id=?,lease_until=now()+interval '150 seconds' WHERE id=?",attempt,id);event(org,id,"running");mapOf("id" to id,"attempt" to attempt,"mode" to row["mode"],"requestedBy" to row["requested_by"],"snapshot" to reg.decode(jdbc.queryForObject("SELECT snapshot::text FROM run_snapshots WHERE run_id=?",String::class.java,id)!!))}
    }
   }
   if(job==null) continue
   val id=job["id"] as UUID;val attempt=job["attempt"] as UUID
   try {
    val snapshot=job.obj("snapshot")
    if(snapshot.obj("adapter")!=reg.runtimeIdentity()) fail(409,"Runtime changed since this run was queued")
    val runtimeSnapshot=mapOf("agents" to snapshot["agents"],"tasks" to snapshot.list("tasks").map { it.filterKeys {key -> key!="agentRevision"} },"input" to snapshot["input"])
    val credentials=if(job["mode"]=="live") reg.models.resolve(Actor(job["requestedBy"] as UUID,org,"member",""),snapshot.obj("modelConnection")) else null
    val result=reg.runtime("execute",mapOf("connection" to credentials,"runId" to id.toString(),"attemptId" to attempt.toString(),"snapshot" to runtimeSnapshot,"limits" to snapshot.obj("limits"),"mode" to job["mode"]),100)
    auth.scoped(system){
     val updated=jdbc.update("UPDATE runs SET status='succeeded',outputs=?::jsonb,usage=?::jsonb WHERE id=? AND attempt_id=? AND status='running'",reg.encode(result["outputs"]),reg.encode(result["usage"]),id,attempt)
     if(updated==1) {result.list("outputs").forEach{event(org,id,"task_completed",mapOf("taskId" to it["taskId"]))};event(org,id,"succeeded")}
    }
   } catch(e:Exception) {
    auth.scoped(system){if(jdbc.update("UPDATE runs SET status='failed',error=? WHERE id=? AND attempt_id=? AND status='running'",(e as? org.springframework.web.server.ResponseStatusException)?.reason?:"Execution failed",id,attempt)==1) event(org,id,"failed")}
   }
  }
 }
}

@RestController
@RequestMapping("/api/runs")
class RunsController(val runs:RunService) {
 val reg=runs.reg;val auth=reg.auth;val jdbc=reg.jdbc
 @PostMapping fun create(@RequestBody b:Obj,req:HttpServletRequest):Obj {
  val a=auth.actor(req,true);val mode=reg.mode(b);val modelConfiguration=if(mode=="live") reg.models.configuration(a,b) else null;val input=b.str("input");if(input.trim().isEmpty() || input.length>24000) fail(400,"Provide source notes of up to 24000 characters")
  return auth.scoped(a){
   jdbc.queryForObject("SELECT id FROM organizations WHERE id=? FOR UPDATE",UUID::class.java,a.orgId)
   if(jdbc.queryForObject("SELECT count(*) FROM runs WHERE status IN ('queued','running')",Int::class.java)!!>=10) fail(429,"Too many active runs; wait for a run to finish")
   val crew=reg.version(uuid(b.str("crewId")),b.num("crewRevision"));if(crew["kind"]!="crew") fail(400,"Choose a saved team")
   val spec=crew.obj("spec");reg.validate("crew",crew.str("name"),spec)
   val agents=spec.list("agents").map{reg.version(uuid(it.str("id")),it.num("revision"))}.map{mapOf("id" to it["id"].toString(),"revision" to it["revision"],"name" to it["name"],"role" to it.obj("spec")["role"],"instructions" to it.obj("spec")["instructions"])}
   val tasks=spec.list("tasks").map{reg.version(uuid(it.str("id")),it.num("revision"))}.map{mapOf("id" to it["id"].toString(),"revision" to it["revision"],"name" to it["name"],"instructions" to it.obj("spec")["instructions"],"agentId" to it.obj("spec")["agentId"],"agentRevision" to it.obj("spec")["agentRevision"])}
   val snapshot=mapOf("schemaVersion" to 1,"organizationId" to a.orgId.toString(),"crew" to crew,"agents" to agents,"tasks" to tasks,"input" to input,"limits" to mapOf("maxCalls" to 8,"maxTokens" to 12000,"timeoutSeconds" to 90),"policy" to mapOf("revision" to 1,"tools" to emptyList<String>(),"delegation" to false),"modelAliases" to mapOf("worker" to (modelConfiguration?.get("workerModel")?:"smart-worker"),"planner" to (modelConfiguration?.get("plannerModel")?:"smart-orchestrator")),"adapter" to reg.runtimeIdentity(),"approvalEvidence" to spec.list("agents").flatMap { ref -> jdbc.queryForList("SELECT proposal_id,user_id,content_hash,component_id,revision,created_at FROM approvals WHERE component_id=? AND revision=?",uuid(ref.str("id")),ref.num("revision")).map {it.mapValues { entry -> entry.value?.toString() }} },"mode" to mode,"modelConnection" to modelConfiguration)
   val id=UUID.randomUUID();jdbc.update("INSERT INTO runs(org_id,id,requested_by,mode) VALUES(?,?,?,?)",a.orgId,id,a.userId,mode)
   jdbc.update("INSERT INTO run_snapshots(org_id,run_id,snapshot,content_hash) VALUES(?,?,?::jsonb,?)",a.orgId,id,reg.encode(snapshot),hash(reg.encode(snapshot)));runs.event(a.orgId,id,"queued");runs.get(id)
  }
 }
 @GetMapping fun all(req:HttpServletRequest):List<Obj>{val a=auth.actor(req);return auth.scoped(a){jdbc.queryForList("SELECT id FROM runs ORDER BY created_at DESC LIMIT 100").map{runs.get(it["id"] as UUID)}}}
 @GetMapping("/{id}") fun get(@PathVariable id:UUID,req:HttpServletRequest):Obj{val a=auth.actor(req);return auth.scoped(a){runs.get(id)}}
 @PostMapping("/{id}/cancel") fun cancel(@PathVariable id:UUID,req:HttpServletRequest):Obj {
  val a=auth.actor(req,true)
  val attempt=auth.scoped(a){
   val row=jdbc.queryForList("SELECT attempt_id,status FROM runs WHERE id=? FOR UPDATE",id).firstOrNull()?:fail(404,"Run not found")
   if(row["status"] !in setOf("queued","running")) fail(409,"Run already finished")
   jdbc.update("UPDATE runs SET status='cancelled' WHERE id=?",id);runs.event(a.orgId,id,"cancelled");row["attempt_id"] as? UUID
  }
  if(attempt!=null) try {reg.runtime("cancel",mapOf("runId" to id.toString(),"attemptId" to attempt.toString()),5);auth.scoped(a){runs.event(a.orgId,id,"execution_stopped",mapOf("executionStopConfirmed" to true))}} catch(_:Exception) { auth.scoped(a){jdbc.update("UPDATE runs SET error='Cancellation recorded, but runtime stop could not be confirmed. The execution time limit still applies.' WHERE id=?",id);runs.event(a.orgId,id,"cancellation_unconfirmed",mapOf("executionStopConfirmed" to false))} }
  return auth.scoped(a){runs.get(id)}
 }
}
