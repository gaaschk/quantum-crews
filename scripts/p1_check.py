#!/usr/bin/env python3
"""Non-destructive P1 API journey. Requires running API/runtime in test mode.

Creates unique p1test accounts and definitions. Never calls live models or deletes
data. Run: python3 scripts/p1_check.py [http://127.0.0.1:8100]
"""
import copy
import concurrent.futures
import http.cookiejar
import json
import sys
import time
import urllib.error
import urllib.request
import uuid


BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8100").rstrip("/")
ORIGIN = sys.argv[2] if len(sys.argv)>2 else ("http://127.0.0.1:3100" if BASE.endswith(":8100") else BASE)
SUFFIX = uuid.uuid4().hex
PASSWORD = "p1test-local-password-" + SUFFIX
CHECKS = 0


class Client:
    def __init__(self):
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
        )

    def call(self, method, path, body=None, expected=200):
        global CHECKS
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(
            BASE + "/api" + path,
            data=data,
            method=method,
            headers={"Content-Type": "application/json", "Origin": ORIGIN},
        )
        try:
            with self.opener.open(request, timeout=55) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        parsed = json.loads(raw) if raw else None
        allowed = expected if isinstance(expected, tuple) else (expected,)
        assert status in allowed, f"{method} {path}: expected {expected}, got {status}: {parsed}"
        CHECKS += 1
        return (status, parsed) if isinstance(expected, tuple) else parsed

    def switch(self, organization):
        self.call("POST", "/organizations/active", {"organizationId": organization})
        assert self.call("GET", "/me")["activeOrganizationId"] == organization

    def create(self, kind, name, spec):
        return self.call("POST", "/components", {"kind": kind, "name": "p1test " + name, "spec": spec})

    def update(self, component, **overrides):
        body = {"baseRevision": component["revision"], "name": component["name"], "spec": component["spec"]}
        body.update(overrides)
        return self.call("PUT", "/components/" + component["id"], body)


def main():
    owner, other, anonymous = Client(), Client(), Client()
    anonymous.call("GET", "/me", expected=401)
    accounts = []
    for client, label in [(owner, "owner"), (other, "other")]:
        email = f"p1test-{label}-{SUFFIX}@example.test"
        me = client.call("POST", "/auth/register", {"email": email, "name": "p1test " + label, "password": PASSWORD})
        assert len(me["organizations"]) == 1
        assert me["organizations"][0]["personal"] is True
        assert me["organizations"][0]["role"] == "owner"
        personal = me["activeOrganizationId"]
        client.call("POST", "/auth/logout")
        client.call("GET", "/me", expected=401)
        signed_in = client.call("POST", "/auth/login", {"email": email, "password": PASSWORD})
        assert signed_in["activeOrganizationId"] == personal
        accounts.append((email, personal))
    (_, personal), (other_email, other_personal) = accounts
    assert personal != other_personal
    other.call("POST", "/organizations/active", {"organizationId": personal}, expected=403)
    owner.call("POST", f"/organizations/{personal}/members", {"email": other_email, "role": "member"}, expected=400)

    private = owner.create("agent", "private", {"role": "Analyst", "instructions": "Summarize provided notes."})
    assert private["id"] not in {item["id"] for item in other.call("GET", "/components")}
    other.call("GET", f"/components/{private['id']}/history", expected=404)
    other.call("PUT", f"/components/{private['id']}", {"baseRevision": 1, "name": "tampered", "spec": private["spec"]}, expected=404)
    other.call("POST", "/components", {"kind": "task", "name": "p1test cross-tenant", "spec": {"instructions": "Do work", "agentId": private["id"], "agentRevision": 1}}, expected=404)
    other.call("POST", "/proposals", {"prompt": "Improve", "agentId": private["id"], "mode": "test"}, expected=404)

    shared = owner.call("POST", "/organizations", {"name": "p1test shared " + SUFFIX})["id"]
    owner.switch(shared)
    owner.call("POST", f"/organizations/{shared}/members", {"email": other_email, "role": "viewer"})
    other.switch(shared)
    other.call("GET", "/components")
    other.call("POST", "/components", {"kind": "agent", "name": "p1test denied", "spec": private["spec"]}, expected=403)
    other.call("POST", "/proposals", {"prompt": "Reviewer", "mode": "test"}, expected=403)
    other.call("POST", f"/organizations/{shared}/members", {"email": other_email, "role": "member"}, expected=403)
    owner.call("POST", f"/organizations/{shared}/members", {"email": other_email, "role": "member"})
    other.call("POST", f"/organizations/{shared}/members", {"email": other_email, "role": "viewer"}, expected=403)
    agent = other.create("agent", "summarizer", {"role": "Summarizer", "instructions": "Summarize source notes accurately."})
    edited = owner.update(agent, name="p1test improved summarizer")
    assert edited["revision"] == 2
    owner.call("PUT", f"/components/{agent['id']}", {"baseRevision": 1, "name": agent["name"], "spec": agent["spec"]}, expected=409)
    history = owner.call("GET", f"/components/{agent['id']}/history")
    assert [version["revision"] for version in history] == [2, 1]
    agent = owner.call("POST", f"/components/{agent['id']}/rollback", {"baseRevision": 2, "targetRevision": 1})
    assert agent["revision"] == 3 and agent["name"] == history[1]["name"]

    # Both users read the same revision before concurrent independent HTTP writes.
    # Row locking plus expected revision must preserve exactly one winner.
    racing = owner.create("agent", "concurrency", {"role": "Analyst", "instructions": "Preserve concurrent edits."})
    import threading
    barrier = threading.Barrier(2)

    def race(client, label):
        barrier.wait(timeout=5)
        return client.call("PUT", f"/components/{racing['id']}", {
            "baseRevision": 1, "name": "p1test race " + label, "spec": racing["spec"]
        }, expected=(200, 409))

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(race, client, label) for client, label in [(owner, "owner"), (other, "member")]]
        results = [future.result(timeout=15) for future in futures]
    assert sorted(status for status, _ in results) == [200, 409], results
    race_history = owner.call("GET", f"/components/{racing['id']}/history")
    assert [item["revision"] for item in race_history] == [2, 1]
    assert race_history[0]["name"] == next(body["name"] for status, body in results if status == 200)

    owner.call("POST", "/components", {"kind": "agent", "name": "p1test forbidden", "spec": {**agent["spec"], "permissions": ["admin"]}}, expected=400)
    owner.call("POST", "/proposals", {"prompt": "Reviewer", "mode": "test", "permissions": ["admin"]}, expected=400)
    proposal = owner.call("POST", "/proposals", {"prompt": "Create a reviewer for a summary of supplied notes.", "mode": "test"})
    assert proposal["simulated"] is True and proposal["status"] == "pending"
    assert proposal["patch"]
    proposed = copy.deepcopy(proposal["proposed"])
    proposed["name"] = "p1test human-edited reviewer"
    proposed["spec"]["instructions"] = "Check the summary against supplied notes. Identify unsupported statements."
    approval = {"baseRevision": proposal["baseRevision"], "proposed": proposed}
    owner.call("POST", f"/proposals/{proposal['id']}/approve", {**approval, "permissions": ["admin"]}, expected=400)
    owner.call("POST", f"/proposals/{proposal['id']}/approve", {**approval, "proposed": {**proposed, "permissions": ["admin"]}}, expected=400)
    reviewer = owner.call("POST", f"/proposals/{proposal['id']}/approve", approval)
    assert reviewer["name"] == proposed["name"] and reviewer["spec"] == proposed["spec"]
    owner.call("POST", f"/proposals/{proposal['id']}/approve", approval, expected=409)

    patch = owner.call("POST", "/proposals", {"prompt": "Make the instructions more concise", "agentId": agent["id"], "mode": "test"})
    assert patch["baseRevision"] == 3
    agent = owner.update(agent, name="p1test changed after proposal")
    owner.call("POST", f"/proposals/{patch['id']}/approve", {"baseRevision": 3, "proposed": patch["proposed"]}, expected=409)
    patch = owner.call("POST", "/proposals", {"prompt": "Make the instructions more concise", "agentId": agent["id"], "mode": "test"})
    patch["proposed"]["name"] = "p1test approved granular edit"
    agent = owner.call("POST", f"/proposals/{patch['id']}/approve", {"baseRevision": agent["revision"], "proposed": patch["proposed"]})
    assert agent["revision"] == 5

    tasks = [owner.create("task", label, {"instructions": instruction, "agentId": member["id"], "agentRevision": member["revision"]})
             for label, instruction, member in [("summary", "Summarize the supplied notes.", agent), ("review", "Review the previous summary against the supplied notes.", reviewer)]]
    crew = owner.create("crew", "notes team", {"agents": [{"id": m["id"], "revision": m["revision"]} for m in [agent, reviewer]], "tasks": [{"id": task["id"], "revision": task["revision"]} for task in tasks]})
    run_body = {"crewId": crew["id"], "crewRevision": crew["revision"], "input": "Project launch is Tuesday. Budget is 100 units. Sarah owns the launch checklist.", "mode": "test"}
    owner.call("POST", f"/organizations/{shared}/members", {"email": other_email, "role": "viewer"})
    other.call("POST", "/runs", run_body, expected=403)
    owner.call("POST", f"/organizations/{shared}/members", {"email": other_email, "role": "member"})
    run = other.call("POST", "/runs", run_body)
    snapshot = copy.deepcopy(run["snapshot"])
    owner.update(agent, name="p1test change after snapshot")
    deadline = time.monotonic() + 35
    while run["status"] in ("queued", "running") and time.monotonic() < deadline:
        time.sleep(0.3)
        run = owner.call("GET", f"/runs/{run['id']}")
    assert run["status"] == "succeeded", run
    assert run["simulated"] is True and run["snapshot"] == snapshot
    assert len(run["outputs"]) == 2 and all(output.get("output") for output in run["outputs"]), run["outputs"]
    assert {output["taskId"] for output in run["outputs"]} == {task["id"] for task in tasks}
    assert {event["type"] for event in run["events"]} >= {"queued", "running", "task_completed", "succeeded"}
    assert run["id"] in {item["id"] for item in owner.call("GET", "/runs")}
    cancelling = owner.call("POST", "/runs", run_body)
    cancelled = owner.call("POST", f"/runs/{cancelling['id']}/cancel")
    assert cancelled["status"] == "cancelled", cancelled
    # A delayed completion must not overwrite a cancelled state.
    time.sleep(2)
    cancelled = owner.call("GET", f"/runs/{cancelling['id']}")
    assert cancelled["status"] == "cancelled" and not cancelled["outputs"], cancelled
    assert "succeeded" not in {event["type"] for event in cancelled["events"]}
    owner.call("POST", f"/runs/{cancelling['id']}/cancel", expected=409)
    owner.switch(personal)
    owner.call("GET", f"/runs/{run['id']}", expected=404)
    owner.call("POST", "/runs", run_body, expected=404)
    owner.call("GET", f"/components/{crew['id']}/history", expected=404)
    owner.call("POST", f"/proposals/{proposal['id']}/approve", approval, expected=404)
    assert proposal["id"] not in {item["id"] for item in owner.call("GET", "/proposals")}
    assert run["id"] not in {item["id"] for item in owner.call("GET", "/runs")}
    assert private["id"] in {item["id"] for item in owner.call("GET", "/components")}
    print(f"P1 deterministic API journey passed ({CHECKS} HTTP assertions).")
    print("Simulated runtime only; no live model compatibility claimed.")


if __name__ == "__main__":
    main()
