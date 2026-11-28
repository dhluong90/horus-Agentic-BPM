#!/usr/bin/env python3
"""Upgrade test: a populated 1.0.0 database, started by the candidate build (#658).

  python3 tools/upgrade-test/upgrade_test.py --old-jar OLD.jar --new-jar NEW.jar [--logs DIR]

OLD.jar and NEW.jar are the example host examples/rule-and-process built from the tag v1.0.0 and from the commit
under test (tools/upgrade-test/run.sh builds both). The test

  1. starts PostgreSQL 16 (the image pinned in docker-compose.yml) and the 1.0.0 host on it;
  2. seeds, through the REST API: rule sets with approvals (two approved versions, one pending), decisions in the
     log, process instances waiting at user tasks (one claimed), at a message wait and at a timer, one instance
     completed, and a record of every audit row;
  3. stops the 1.0.0 host and starts the candidate host on the same database, which runs the new migrations;
  4. asserts that the data is intact (every row of the audit and decision tables is still there, unchanged), that the
     seals hold (versions approved before the seals existed carry the legacy marker and are served; an approval after the
     upgrade writes a real seal), that logged decisions replay to the same outcome, that every in-flight instance
     completes (task, message, timer), that a pending approval can still be approved, and that the audit trail only grew.

Standard library only; needs Docker, Java 21 or later and python 3.9 or later. It stops only what it started.
Exit code 0 when every check passes, 1 when a check fails, 2 when the setup fails.
"""
import argparse
import base64
import json
import os
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

# postgres:16-alpine, the image of docker-compose.yml; tag and digest are updated together.
POSTGRES_IMAGE = "postgres:16-alpine@sha256:f2f98726820d0342055351c6e9d36e705f5bfffb3dd53ff556325114b08afb6d"
PASSWORD = "upgrade-test-demo-password"
DB_PASSWORD = "upgrade-test-db-password"
RULES = "/api/bpm/rules/v1"
PROCESSES = "/api/bpm/processes/v1"

UPG_FEE_V1 = """schemaVersion: 1
ruleSet: upg-fee
description: Upgrade test fee table
hitPolicy: first
inputs:
  - { name: tier, type: string }
outputs:
  - { name: fee, type: money }
rules:
  - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
  - { id: R2, when: {}, then: { fee: "30000 VND" } }
"""
UPG_FEE_V2 = UPG_FEE_V1.replace("30000 VND", "35000 VND")
UPG_FEE_V3 = UPG_FEE_V1.replace("30000 VND", "40000 VND")

# Audit and decision tables: the columns that exist in 1.0.0, so a column the migrations add does not count as a change.
ROW_QUERIES = {
    "rle_version": "SELECT id, row(rule_set_id, version_num, status, canonical_hash, source_hash, requested_by, "
                   "requested_at, approved_by, approved_at, created_by, created_at, effective_from)::text FROM rle_version",
    "rle_version_history": "SELECT id, row(version_id, action, from_status, to_status, actor, reason, "
                           "lock_version, occurred_at, canonical_hash)::text FROM rle_version_history",
    "rle_decision": "SELECT id, row(rule_set_name, version_num, hit_policy, outcome, outputs::text, "
                    "matched_rule_ids::text, caller_id, on_behalf_of, eval_clock, created_at)::text FROM rle_decision",
    "pre_history": "SELECT id, row(instance_id, definition_id, event_type, node_id, token_id, actor_id, "
                   "occurred_at, details::text)::text FROM pre_history",
    "pre_definition": "SELECT id, row(source_hash, definition_hash, approved_by, approved_at)::text FROM pre_definition",
    "pre_definition_history": "SELECT id, row(definition_id, action, from_status, to_status, actor_id, "
                              "occurred_at)::text FROM pre_definition_history",
}


class Setup(Exception):
    pass


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Run:
    def __init__(self, args):
        self.old_jar = Path(args.old_jar).resolve()
        self.new_jar = Path(args.new_jar).resolve()
        self.logs = Path(args.logs or tempfile.mkdtemp(prefix="horus-upgrade-test-"))
        self.logs.mkdir(parents=True, exist_ok=True)
        self.pg_port = free_port()
        self.http_port = free_port()
        self.container = f"horus-upgrade-test-{os.getpid()}"
        self.proc = None
        self.checks = []
        self.state = {}

    # ---- plumbing -------------------------------------------------------------------------------------------

    def sh(self, *cmd, check=True):
        r = subprocess.run(cmd, capture_output=True, text=True)
        if check and r.returncode != 0:
            raise Setup(f"{' '.join(cmd[:4])} failed: {r.stderr.strip() or r.stdout.strip()}")
        return r.stdout

    def start_postgres(self):
        self.sh("docker", "run", "-d", "--name", self.container, "-e", "POSTGRES_DB=horus", "-e", "POSTGRES_USER=horus",
                "-e", f"POSTGRES_PASSWORD={DB_PASSWORD}", "-p", f"127.0.0.1:{self.pg_port}:5432", POSTGRES_IMAGE)
        for _ in range(60):
            if subprocess.run(["docker", "exec", self.container, "pg_isready", "-U", "horus", "-d", "horus"],
                              capture_output=True).returncode == 0:
                time.sleep(1)
                return
            time.sleep(1)
        raise Setup("PostgreSQL did not become ready")

    def sql(self, query):
        out = self.sh("docker", "exec", self.container, "psql", "-U", "horus", "-d", "horus", "-At", "-F", "\t", "-c", query)
        return [line.split("\t") for line in out.splitlines() if line != ""]

    def start_host(self, jar, label):
        env = dict(os.environ, SPRING_PROFILES_ACTIVE="dev", HORUS_DEMO_PASSWORD=PASSWORD, HORUS_DB_PASSWORD=DB_PASSWORD,
                   HORUS_DB_URL=f"jdbc:postgresql://127.0.0.1:{self.pg_port}/horus", PORT=str(self.http_port))
        log = open(self.logs / f"host-{label}.log", "wb")
        self.proc = subprocess.Popen(["java", "-jar", str(jar)], env=env, stdout=log, stderr=subprocess.STDOUT)
        deadline = time.time() + 180
        while time.time() < deadline:
            if self.proc.poll() is not None:
                raise Setup(f"the {label} host exited with {self.proc.returncode}; see {self.logs}/host-{label}.log")
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{self.http_port}/actuator/health", timeout=2) as r:
                    if b"UP" in r.read():
                        self.wait_for_seeders(label)
                        return
            except (urllib.error.URLError, ConnectionError, socket.timeout):
                pass
            time.sleep(1)
        raise Setup(f"the {label} host did not become healthy in 180 s")

    def wait_for_seeders(self, label):
        # The dev seeders run after the context is ready; the demo rule set is their last step.
        for _ in range(60):
            if "card-fee v1 PUBLISHED" in (self.logs / f"host-{label}.log").read_text(errors="replace") \
                    or "card-fee exists" in (self.logs / f"host-{label}.log").read_text(errors="replace"):
                return
            time.sleep(1)

    def stop_host(self):
        if self.proc and self.proc.poll() is None:
            self.proc.send_signal(signal.SIGTERM)
            try:
                self.proc.wait(timeout=60)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait()
        self.proc = None

    def api(self, user, method, path, body=None, ctype="application/json", headers=None):
        url = f"http://127.0.0.1:{self.http_port}{path}"
        data = None
        if body is not None:
            data = body.encode() if isinstance(body, str) else json.dumps(body).encode()
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", "Basic " + base64.b64encode(f"{user}:{PASSWORD}".encode()).decode())
        if data is not None:
            req.add_header("Content-Type", ctype)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                text = r.read().decode()
                return r.status, (json.loads(text) if text.strip().startswith(("{", "[")) else text), dict(r.headers)
        except urllib.error.HTTPError as e:
            text = e.read().decode()
            try:
                return e.code, json.loads(text), dict(e.headers)
            except ValueError:
                return e.code, text, dict(e.headers)

    def ok(self, user, method, path, body=None, ctype="application/json", headers=None, expect=(200, 201)):
        status, js, hdr = self.api(user, method, path, body, ctype, headers)
        if status not in expect:
            raise Setup(f"{user} {method} {path} answered {status}: {str(js)[:300]}")
        return js

    def check(self, name, condition, detail=""):
        self.checks.append((name, bool(condition), detail))
        print(f"  {'PASS' if condition else 'FAIL'}  {name}" + (f"  [{detail}]" if detail and not condition else ""))

    # ---- seed (1.0.0 host) ----------------------------------------------------------------------------------

    def seed(self):
        s = self.state
        # Rule sets: v1 and v2 approved, v3 waiting for its approval.
        self.ok("lan", "POST", f"{RULES}/rule-sets", UPG_FEE_V1, "application/yaml", {"Idempotency-Key": "upg-rs-1"})
        v = self.ok("lan", "POST", f"{RULES}/rule-sets/upg-fee/versions/1:request-publish")
        self.approve(1, v)
        s["decisions"] = []
        for tier, n in (("GOLD", 3), ("SILVER", 3)):
            for _ in range(n):  # decisions logged on version 1 of upg-fee
                d = self.ok("tuan", "POST", f"{RULES}/decisions/upg-fee:evaluate", {"inputs": {"tier": tier}})
                s["decisions"].append((d["decisionId"], d["version"], d["outcome"], d["matchedRuleIds"], d["outputs"]))
        self.ok("lan", "POST", f"{RULES}/rule-sets/upg-fee/versions", UPG_FEE_V2, "application/yaml")
        v = self.ok("lan", "POST", f"{RULES}/rule-sets/upg-fee/versions/2:request-publish")
        self.approve(2, v)
        for tier in ("GOLD", "SILVER", "BRONZE"):
            d = self.ok("tuan", "POST", f"{RULES}/decisions/upg-fee:evaluate", {"inputs": {"tier": tier}})
            s["decisions"].append((d["decisionId"], d["version"], d["outcome"], d["matchedRuleIds"], d["outputs"]))
        for i in range(5):  # the seeded card-fee set, evaluated in different tiers
            d = self.ok("tuan", "POST", f"{RULES}/decisions/card-fee:evaluate",
                        {"inputs": {"tier": "STANDARD" if i % 2 else "GOLD", "txCount": i}})
            s["decisions"].append((d["decisionId"], d["version"], d["outcome"], d["matchedRuleIds"], d["outputs"]))
        self.ok("lan", "POST", f"{RULES}/rule-sets/upg-fee/versions", UPG_FEE_V3, "application/yaml")
        v = self.ok("lan", "POST", f"{RULES}/rule-sets/upg-fee/versions/3:request-publish")
        s["v3_requested"] = v
        # Process instances at user tasks: four applications, one claimed by an.
        s["tasks"] = []
        for i in range(4):
            app = f"UPG-APP-{i}"
            r = self.ok("tuan", "POST", "/api/cards/applications", {"applicationId": app, "customerId": "C-002"})
            inst = self.ok("an", "GET", f"{PROCESSES}/instances/{r['instanceId']}")
            task = inst["tasks"][0]
            s["tasks"].append({"app": app, "instance": r["instanceId"], "task": task["id"]})
        t0 = s["tasks"][0]
        self.ok("an", "POST", f"{PROCESSES}/tasks/{t0['task']}:claim", headers={"If-Match": '"0"', "Idempotency-Key": "upg-claim-0"})
        # One application that completed before the upgrade (a gold customer: no review).
        r = self.ok("tuan", "POST", "/api/cards/applications", {"applicationId": "UPG-DONE", "customerId": "C-001"})
        s["done"] = r["instanceId"]
        # Instances waiting for a message.
        s["orders"] = []
        for i in range(4):
            order = f"UPG-ORD-{i}"
            inst = self.ok("tuan", "POST", f"{PROCESSES}/instances",
                           {"definitionKey": "order-payment", "businessKey": order,
                            "variables": {"orderId": order, "amount": "100"}}, headers={"Idempotency-Key": f"upg-start-{i}"})
            s["orders"].append({"order": order, "instance": inst["id"]})

    def approve(self, version, requested, comment="ok"):
        """minh approves what lan requested: the lock version and the content hash the reviewer saw."""
        return self.ok("minh", "POST", f"{RULES}/rule-sets/upg-fee/versions/{version}:approve",
                       {"comment": comment, "expectedCanonicalHash": requested["canonicalHash"]},
                       headers={"If-Match": f'"{requested["lockVersion"]}"'})

    def seed_timers(self):
        """Last, so the one-minute timer is still pending when the 1.0.0 host stops."""
        self.state["timers"] = []
        for i in range(2):
            inst = self.ok("tuan", "POST", f"{PROCESSES}/instances",
                           {"definitionKey": "card-issue", "businessKey": f"UPG-TIMER-{i}", "variables": {}},
                           headers={"Idempotency-Key": f"upg-timer-{i}"})
            self.state["timers"].append(inst["id"])

    # ---- snapshot -------------------------------------------------------------------------------------------

    def snapshot(self):
        snap = {t: {r[0]: r[1] for r in self.sql(q)} for t, q in ROW_QUERIES.items()}
        snap["flyway"] = [tuple(r) for r in self.sql("SELECT 'rle', version, success FROM rle_schema_history UNION ALL "
                                  "SELECT 'pre', version, success FROM pre_schema_history ORDER BY 1, 2")]
        snap["instances"] = {r[0]: r[1] for r in self.sql(
            "SELECT id, row(business_key, status, variables::text, started_by, started_at, lock_version)::text "
            "FROM pre_instance WHERE business_key LIKE 'UPG-%'")}
        snap["approved_versions"] = [r[0] for r in self.sql("SELECT id FROM rle_version WHERE approved_at IS NOT NULL")]
        snap["tasks"] = {r[0]: r[1] for r in self.sql(
            "SELECT id, row(instance_id, node_id, status, claimed_by, lock_version)::text FROM pre_task")}
        return snap

    # ---- verify (candidate host) ------------------------------------------------------------------------------

    def verify(self, before):
        s = self.state
        after = self.snapshot()
        print("\nData is intact")
        for t in ROW_QUERIES:
            missing = [k for k in before[t] if k not in after[t]]
            changed = [k for k in before[t] if k in after[t] and after[t][k] != before[t][k]]
            self.check(f"UPG-01 {t}: all {len(before[t])} rows are still there", not missing, f"missing {missing[:3]}")
            self.check(f"UPG-01 {t}: no row changed", not changed,
                       f"{changed[:2]}: {before[t].get(changed[0]) if changed else ''} -> {after[t].get(changed[0]) if changed else ''}")
        for t in ("instances", "tasks"):
            gone = [k for k in before[t] if k not in after[t]]
            self.check(f"UPG-01 {t}: all {len(before[t])} rows are still there", not gone, str(gone[:3]))
        changed_instances = [k for k in before["instances"] if k not in s["timers"] and after["instances"].get(k) != before["instances"][k]]
        self.check("UPG-01 waiting instances are unchanged by the upgrade", not changed_instances, str(changed_instances[:3]))
        self.check("UPG-02 Flyway history: the old versions are kept and V2 is applied successfully on both engines",
                   set(before["flyway"]) <= set(after["flyway"])
                   and ("rle", "2", "t") in after["flyway"] and ("pre", "2", "t") in after["flyway"]
                   and all(r[2] == "t" for r in after["flyway"]), str(after["flyway"]))
        vs = self.ok("lan", "GET", f"{RULES}/rule-sets/upg-fee/versions/1")
        self.check("UPG-03 version 1 reads with its approver and hash", vs["status"] in ("PUBLISHED", "RETIRED")
                   and vs["approvedBy"] == "minh" and len(vs["canonicalHash"]) == 64, str(vs)[:200])

        print("\nSeals verify")
        seals = {r[0]: r[1] for r in self.sql("SELECT id, approval_seal FROM rle_version WHERE approved_at IS NOT NULL")}
        approved_before = set(before["approved_versions"])
        self.check("UPG-04 every version approved before the upgrade carries the legacy marker once",
                   approved_before and all(seals.get(i) == "legacy:pre-seal" for i in approved_before), str(seals)[:200])
        self.check("UPG-04 no approved version has a missing seal", all(v for v in seals.values()))
        # decisions replay: the engine loads the stored version cold in this JVM and checks its seal
        print("\nDeterministic replay")
        mismatch = []
        for did, version, outcome, matched, outputs in s["decisions"]:
            logged = self.ok("minh", "GET", f"{RULES}/decisions/{did}")
            rep = self.ok("minh", "POST", f"{RULES}/decisions/{did}:replay", {})
            if not (rep["outputs"] == outputs == logged["outputs"] and rep["matchedRuleIds"] == matched
                    and rep["version"] == version and logged["outcome"] == "OK"):
                mismatch.append((did, outputs, rep["outputs"]))
        self.check(f"UPG-05 {len(s['decisions'])} logged decisions (versions 1 and 2, cold start) replay to the same outputs",
                   not mismatch, str(mismatch[:2]))
        d = self.ok("tuan", "POST", f"{RULES}/decisions/upg-fee:evaluate", {"inputs": {"tier": "SILVER"}})
        self.check("UPG-05 the version in force (v2, legacy seal) serves a new evaluation", d["version"] == 2
                   and d["outputs"]["fee"]["amount"] == 35000, str(d))

        print("\nA pending approval completes")
        self.approve(3, s["v3_requested"], "after the upgrade")
        seal3 = self.sql("SELECT approval_seal FROM rle_version WHERE rule_set_id = (SELECT id FROM rle_rule_set WHERE name='upg-fee') AND version_num = 3")
        self.check("UPG-06 the approval after the upgrade writes a real seal", seal3 and seal3[0][0].startswith("sha256:"), str(seal3))
        d = self.ok("tuan", "POST", f"{RULES}/decisions/upg-fee:evaluate", {"inputs": {"tier": "SILVER"}})
        self.check("UPG-06 version 3 is in force and served", d["version"] == 3 and d["outputs"]["fee"]["amount"] == 40000, str(d))
        old = self.ok("minh", "POST", f"{RULES}/decisions/{s['decisions'][0][0]}:replay", {})
        self.check("UPG-06 a decision logged on version 1 still replays on version 1", old["version"] == 1)

        print("\nIn-flight instances complete")
        done = self.ok("tuan", "GET", f"{PROCESSES}/instances/{s['done']}")
        self.check("UPG-07 the instance completed before the upgrade is still COMPLETED", done["status"] == "COMPLETED")
        for i, t in enumerate(s["tasks"]):
            view = self.ok("an", "GET", f"{PROCESSES}/tasks/{t['task']}")
            if i > 0:
                view = self.ok("an", "POST", f"{PROCESSES}/tasks/{t['task']}:claim",
                               headers={"If-Match": f'"{view["lockVersion"]}"', "Idempotency-Key": f"upg-claim-{i}-new"})
            else:
                self.check("UPG-07 the task claimed before the upgrade is still claimed by an", view.get("claimedBy") == "an"
                           or view.get("status") == "CLAIMED", str(view)[:200])
            res = self.ok("an", "POST", f"{PROCESSES}/tasks/{t['task']}:complete", {"result": {"approved": True}},
                          headers={"If-Match": f'"{view["lockVersion"]}"', "Idempotency-Key": f"upg-complete-{i}"})
            decided = self.sql(f"SELECT status FROM card_application WHERE application_id = '{t['app']}'")
            self.check(f"UPG-07 task {i}: the instance completes and the host's application is APPROVED",
                       res["status"] == "COMPLETED" and decided == [["APPROVED"]], f"{res['status']} {decided}")
        for o in s["orders"]:
            res = self.ok("hung", "POST", f"{PROCESSES}/messages:correlate",
                          {"messageName": "payment-received", "correlationKey": o["order"], "payload": {"reference": "TT-1"}},
                          headers={"Idempotency-Key": f"upg-msg-{o['order']}"})
            inst = self.ok("tuan", "GET", f"{PROCESSES}/instances/{o['instance']}")
            self.check(f"UPG-08 {o['order']}: the message is delivered to the waiting instance, which completes",
                       res["outcome"] == "DELIVERED" and inst["status"] == "COMPLETED", f"{res} {inst['status']}")
        deadline = time.time() + 240
        pending = list(s["timers"])
        while pending and time.time() < deadline:
            pending = [i for i in pending if self.ok("tuan", "GET", f"{PROCESSES}/instances/{i}")["status"] != "COMPLETED"]
            if pending:
                time.sleep(3)
        self.check("UPG-09 the timers that were pending at the upgrade fire and their instances complete", not pending, str(pending))

        print("\nAudit is unbroken")
        end = self.snapshot()
        for t in ("rle_version_history", "pre_history", "rle_decision", "pre_definition_history"):
            unchanged = all(end[t].get(k) == v for k, v in before[t].items())
            grew = len(end[t]) - len(before[t])
            self.check(f"UPG-10 {t}: the {len(before[t])} old rows are unchanged and {grew} rows were appended",
                       unchanged and (grew > 0 or t == "pre_definition_history"))
        hist = self.ok("lan", "GET", f"{RULES}/rule-sets/upg-fee/history")
        events = [(v["version"], e["action"]) for v in hist["data"] for e in v["events"]]
        wanted = {(1, "CREATED"), (1, "REQUESTED"), (1, "APPROVED"), (2, "APPROVED"), (3, "REQUESTED"), (3, "APPROVED")}
        self.check("UPG-10 the rule set history shows every step, before and after the upgrade, in the API", wanted <= set(events), str(sorted(events)))
        approvals = self.sql("SELECT count(*) FROM pre_definition_approval")[0][0]
        approved = self.sql("SELECT count(*) FROM pre_definition WHERE approved_by IS NOT NULL AND approved_at IS NOT NULL")[0][0]
        self.check("UPG-10 every approved process definition has its approval record (V2 backfill)", approvals == approved and int(approved) > 0, f"{approvals} vs {approved}")
        completed = self.sql("SELECT count(*) FROM pre_instance WHERE business_key LIKE 'UPG-%' AND status <> 'COMPLETED'")[0][0]
        self.check("UPG-10 every instance seeded before the upgrade is COMPLETED at the end", completed == "0", completed)

    # ---- main -----------------------------------------------------------------------------------------------

    def run(self):
        try:
            print(f"PostgreSQL on {self.pg_port}, host on {self.http_port}, logs in {self.logs}")
            self.start_postgres()
            print(f"Starting the 1.0.0 host ({self.old_jar.name})")
            self.start_host(self.old_jar, "old")
            print("Seeding")
            self.seed()
            self.seed_timers()
            before = self.snapshot()
            print(f"Seeded: {len(before['rle_version'])} rule set versions, {len(before['rle_decision'])} decisions, "
                  f"{len(before['instances'])} instances, {len(before['pre_history'])} history rows")
            pending = [i for i in self.state["timers"] if self.ok("tuan", "GET", f"{PROCESSES}/instances/{i}")["status"] == "RUNNING"]
            if len(pending) != len(self.state["timers"]):
                raise Setup("a timer fired before the upgrade; the seeding took too long")
            self.stop_host()
            print(f"Starting the candidate host ({self.new_jar.name}) on the same database")
            self.start_host(self.new_jar, "new")
            self.verify(before)
        except Setup as e:
            print(f"SETUP FAILED: {e}", file=sys.stderr)
            return 2
        finally:
            self.stop_host()
            subprocess.run(["docker", "rm", "-f", "-v", self.container], capture_output=True)
        failed = [c for c in self.checks if not c[1]]
        print(f"\n{len(self.checks) - len(failed)} of {len(self.checks)} checks passed")
        for name, _, detail in failed:
            print(f"FAILED {name}: {detail}")
        return 1 if failed else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--old-jar", required=True)
    ap.add_argument("--new-jar", required=True)
    ap.add_argument("--logs")
    return Run(ap.parse_args()).run()


if __name__ == "__main__":
    sys.exit(main())
