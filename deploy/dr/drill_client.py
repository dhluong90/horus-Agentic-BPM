#!/usr/bin/env python3
"""HTTP half of the disaster recovery drill (docs/DISASTER-RECOVERY.md): seeds work through the host's API, then
checks, after the restore, that the restored host serves it. Standard library only; prints one JSON object.

  drill_client.py seed   --base http://127.0.0.1:18080 --password-file secrets/demo_password --state state.json
  drill_client.py verify --base http://127.0.0.1:18080 --password-file secrets/demo_password --state state.json
"""
import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.request

PE = "/api/bpm/processes/v1"
RULES = "/api/bpm/rules/v1/decisions"


class Client:
    def __init__(self, base, password):
        self.base, self.password, self.n = base.rstrip("/"), password, 0

    def call(self, user, method, path, body=None, headers=None):
        self.n += 1
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        token = base64.b64encode(f"{user}:{self.password}".encode()).decode()
        req.add_header("Authorization", "Basic " + token)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                text = r.read().decode()
                return r.status, (json.loads(text) if text else {})
        except urllib.error.HTTPError as e:
            text = e.read().decode()
            try:
                return e.code, json.loads(text)
            except ValueError:
                return e.code, {"raw": text[:200]}

    def must(self, expected, user, method, path, body=None, headers=None):
        status, payload = self.call(user, method, path, body, headers)
        if status not in expected:
            sys.exit(f"{method} {path} as {user}: HTTP {status} {json.dumps(payload)[:300]}")
        return payload

    def evaluate(self, tier, tx, key):
        return self.must((200,), "tuan", "POST", f"{RULES}/card-fee:evaluate",
                         {"inputs": {"tier": tier, "txCount": tx}}, {"Idempotency-Key": key})

    def start(self, key, business_key, variables):
        return self.must((201,), "hoa", "POST", f"{PE}/instances",
                         {"definitionKey": key, "businessKey": business_key, "variables": variables})["id"]

    def instance(self, iid):
        return self.must((200,), "an", "GET", f"{PE}/instances/{iid}")

    def complete_review(self, iid, tag):
        instance = self.instance(iid)
        task = next(t for t in instance["tasks"] if t["status"] == "OPEN")
        path, lock = f"{PE}/tasks/{task['id']}", int(task["lockVersion"])
        self.must((200,), "an", "POST", path + ":claim", None,
                  {"If-Match": f'"{lock}"', "Idempotency-Key": f"claim-{tag}-{iid}"})
        self.must((200,), "an", "POST", path + ":complete", {"result": {"upheld": True}},
                  {"If-Match": f'"{lock + 1}"', "Idempotency-Key": f"complete-{tag}-{iid}"})


def seed(c, state_path, tag):
    decisions = [c.evaluate("GOLD" if i % 2 == 0 else "SILVER", i, f"{tag}-seed-eval-{i}") for i in range(5)]
    waiting_timer = c.start("payment-review", f"{tag}-PAY-TIMER", {"paymentId": f"{tag}-PAY-TIMER", "riskScore": 10})
    c.complete_review(waiting_timer, tag)
    waiting_task = c.start("payment-review", f"{tag}-PAY-TASK", {"paymentId": f"{tag}-PAY-TASK", "riskScore": 10})
    applications = []
    for i in range(3):
        r = c.must((201,), "tuan", "POST", "/api/cards/applications",
                   {"applicationId": f"{tag}-APP-{i}", "customerId": "C-002"})
        applications.append(r["instanceId"])
    state = {"tag": tag, "timerInstance": waiting_timer, "taskInstance": waiting_task, "applications": applications,
             "decisions": len(decisions), "seededAt": time.time()}
    json.dump(state, open(state_path, "w"))
    return state


def verify(c, state_path):
    state = json.load(open(state_path))
    out = {"checks": {}}
    t0 = time.time()
    # A decision is served: the version loads cold, so its approval seal is checked on the way in.
    served = c.evaluate("GOLD", 0, f"{state['tag']}-verify-eval-{int(t0)}")
    out["checks"]["evaluationServed"] = served.get("outcome") is not None or "outputs" in served
    out["firstSuccessAt"] = t0
    # In-flight instances are there and still running, and the work in progress resumes.
    out["checks"]["applicationsRunning"] = all(
        c.instance(i)["status"] == "RUNNING" for i in state["applications"])
    before = c.instance(state["taskInstance"])
    out["checks"]["taskInstanceRunning"] = before["status"] == "RUNNING"
    c.complete_review(state["taskInstance"], state["tag"] + "-after")
    after = c.instance(state["taskInstance"])
    out["checks"]["taskInstanceResumed"] = after["status"] == "RUNNING" and not any(
        t["status"] == "OPEN" for t in after["tasks"])
    # The timer that was waiting at the backup fires after the restore.
    fired = None
    for _ in range(120):
        timer = c.instance(state["timerInstance"])
        if timer["status"] == "COMPLETED":
            fired = time.time() - t0
            break
        time.sleep(2)
    out["checks"]["timerFired"] = fired is not None
    out["timerFiredAfterSeconds"] = fired
    out["ok"] = all(out["checks"].values())
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("command", choices=["seed", "verify"])
    p.add_argument("--base", required=True)
    p.add_argument("--password-file", required=True)
    p.add_argument("--state", required=True)
    p.add_argument("--tag", default="DR")
    a = p.parse_args()
    client = Client(a.base, open(a.password_file).read().strip())
    result = seed(client, a.state, a.tag) if a.command == "seed" else verify(client, a.state)
    print(json.dumps(result))
    if a.command == "verify" and not result["ok"]:
        sys.exit(1)


if __name__ == "__main__":
    main()
