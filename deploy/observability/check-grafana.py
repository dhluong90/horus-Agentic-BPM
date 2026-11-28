#!/usr/bin/env python3
"""Asks a running Grafana (GRAFANA_URL, GRAFANA_PASSWORD) whether it loaded the shipped dashboards and whether
every panel query is valid. Called by check-grafana.sh; standard library only.

Checks, each of which fails the run:
  1. the data source horus-prometheus is provisioned and its health check passes;
  2. every dashboard file under DASHBOARD_DIR is provisioned (by uid), into the folder "Horus BPM";
  3. every target of every panel, including panels inside rows, runs through POST /api/ds/query without an error;
  4. every panel that has targets reads the provisioned data source, and at least one query ran per dashboard.
"""
import base64
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ["GRAFANA_URL"].rstrip("/")
AUTH = "Basic " + base64.b64encode(f"admin:{os.environ['GRAFANA_PASSWORD']}".encode()).decode()
DATASOURCE_UID = "horus-prometheus"
FOLDER = "Horus BPM"


def call(method, path, body=None):
    request = urllib.request.Request(BASE + path, method=method, headers={"Authorization": AUTH, "Content-Type": "application/json"},
                                     data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read() or b"null")
    except urllib.error.HTTPError as error:
        raw = error.read().decode(errors="replace")
        try:
            return error.code, json.loads(raw)
        except ValueError:
            return error.code, {"message": raw[:300]}


def wait(what, probe, seconds=120):
    deadline = time.time() + seconds
    last = None
    while time.time() < deadline:
        try:
            ok, last = probe()
            if ok:
                return last
        except (urllib.error.URLError, ConnectionError, OSError) as error:
            last = str(error)
        time.sleep(2)
    sys.exit(f"FAIL: {what} not ready after {seconds} s; last answer: {last}")


def panels_of(panels):
    for panel in panels:
        yield panel
        yield from panels_of(panel.get("panels", []))


def main(dashboard_dir):
    failures = []
    wait("Grafana", lambda: (call("GET", "/api/health")[0] == 200, None))
    wait("the Prometheus data source", lambda: (lambda r: (r[0] == 200 and r[1].get("status") == "OK", r))(
        call("GET", f"/api/datasources/uid/{DATASOURCE_UID}/health")))
    print(f"data source {DATASOURCE_UID}: healthy")

    files = sorted(pathlib.Path(dashboard_dir).glob("*.json"))
    if not files:
        sys.exit(f"FAIL: no dashboard file in {dashboard_dir}")
    queries = 0
    for path in files:
        source = json.loads(path.read_text(encoding="utf-8"))
        uid = source["uid"]
        status, loaded = wait(f"dashboard {uid} to be provisioned", lambda uid=uid: (lambda r: (r[0] == 200, r))(
            call("GET", f"/api/dashboards/uid/{uid}")), seconds=60)
        folder = loaded["meta"].get("folderTitle")
        if folder != FOLDER:
            failures.append(f"{path.name}: provisioned into folder {folder!r}, not {FOLDER!r}")
        per_dashboard = 0
        for panel in panels_of(loaded["dashboard"].get("panels", [])):
            targets = [t for t in panel.get("targets", []) if t.get("expr")]
            if not targets:
                continue
            where = f"{path.name}: panel {panel.get('id')} {panel.get('title')!r}"
            panel_source = (panel.get("datasource") or {}).get("uid")
            if panel_source != DATASOURCE_UID:
                failures.append(f"{where}: reads data source {panel_source!r}, not {DATASOURCE_UID!r}")
            for target in targets:
                body = {"from": "now-1h", "to": "now", "queries": [{
                    "refId": target.get("refId", "A"), "datasource": {"type": "prometheus", "uid": DATASOURCE_UID},
                    "expr": target["expr"], "range": True, "instant": False,
                    "intervalMs": 15000, "maxDataPoints": 500}]}
                code, answer = call("POST", "/api/ds/query", body)
                result = (answer.get("results") or {}).get(body["queries"][0]["refId"], {}) if isinstance(answer, dict) else {}
                error = result.get("error") or (answer.get("message") if code != 200 and isinstance(answer, dict) else None)
                if code != 200 or error:
                    failures.append(f"{where}: query {target['expr']!r} -> HTTP {code}: {error}")
                per_dashboard += 1
        if per_dashboard == 0:
            failures.append(f"{path.name}: no panel query ran")
        queries += per_dashboard
        print(f"{path.name}: uid {uid}, folder {folder!r}, {per_dashboard} queries")

    if failures:
        print(f"FAILED: {len(failures)} problem(s)")
        for failure in failures:
            print("  - " + failure)
        sys.exit(1)
    print(f"OK: {len(files)} dashboards provisioned, {queries} panel queries valid")


if __name__ == "__main__":
    main(sys.argv[1])
