#!/usr/bin/env bash
# Production-readiness gate (#665). Prints one row per sign-off item with a status:
#   pass      the evidence is on main and its automatable checks hold
#   pending   work or evidence is still missing
#   decision  waits for a product owner decision
#   fail      a workflow's latest run on main did not succeed
#   deferred  the product owner moved it to a later release
# Needs git, python3 and an authenticated gh (without gh, PR and run checks print "pending").
# Usage: tools/readiness-check.sh [--strict]   (--strict exits 1 unless every item is pass or deferred)
# The checklist with the evidence links is docs/PRODUCTION-READINESS.md; keep both in step.
set -u
cd "$(git rev-parse --show-toplevel)"
STRICT=0; [ "${1:-}" = "--strict" ] && STRICT=1
HAVE_GH=0; command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1 && HAVE_GH=1
ROWS=""; NPASS=0; NPEND=0; NDEC=0; NDEF=0; NFAIL=0

row() { # id status item note
  case "$2" in pass) NPASS=$((NPASS+1));; pending) NPEND=$((NPEND+1));; decision) NDEC=$((NDEC+1));; deferred) NDEF=$((NDEF+1));; fail) NFAIL=$((NFAIL+1));; esac
  ROWS="${ROWS}$(printf '%-5s | %-8s | %-46s | %s' "$1" "$2" "$3" "$4")
"
}

merged() { [ "$HAVE_GH" = 1 ] && [ "$(gh pr view "$1" --json state -q .state 2>/dev/null)" = "MERGED" ]; }
# prs_state "677 680": prints "all merged" or "open: 692 696"
prs_state() { local open="" p; for p in $1; do merged "$p" || open="$open #$p"; done; [ -z "$open" ] && echo "PRs merged" || echo "not merged:$open"; }
have_files() { local f miss=""; for f in "$@"; do [ -e "$f" ] || miss="$miss $f"; done; [ -z "$miss" ] && return 0; MISSING="$miss"; return 1; }
run_state() { # workflow file -> success | failure | none | unknown (latest run on main)
  [ "$HAVE_GH" = 1 ] || { echo unknown; return; }
  local c; c=$(gh run list --workflow="$1" --branch main --limit 1 --json conclusion -q '.[0].conclusion' 2>/dev/null)
  echo "${c:-none}"
}
# item ID "label" "PRs" "workflow" "files..." : pass only if PRs merged, files exist and the latest main run succeeded
wf_item() {
  local id="$1" label="$2" prs="$3" wf="$4" extra="$5"; shift 5
  local S R; MISSING=""; S=$(prs_state "$prs"); R=$(run_state "$wf")
  if ! have_files "$@" ".github/workflows/$wf"; then row "$id" pending "$label" "missing:$MISSING"
  elif [ "$S" != "PRs merged" ]; then row "$id" pending "$label" "$S"
  elif [ "$R" = success ]; then row "$id" pass "$label" "$extra; $wf latest main run: success"
  elif [ "$R" = failure ] || [ "$R" = cancelled ] || [ "$R" = timed_out ]; then row "$id" fail "$label" "$wf latest main run: $R"
  else row "$id" pending "$label" "$wf has no run on main yet ($R)"; fi
}

# R1 Load baselines (#656, PR 680): baseline.json must hold three runs and their median
MISSING=""; S=$(prs_state "680")
if ! have_files perf/baseline.json perf/LoadHarness.java perf/check_regression.py .github/workflows/perf-smoke.yml; then row R1 pending "Load baselines (#656, PR 680)" "missing:$MISSING"
elif [ "$S" != "PRs merged" ]; then row R1 pending "Load baselines (#656, PR 680)" "$S"
else
  MED=$(python3 - <<'PY'
import json
d = json.load(open("perf/baseline.json"))
runs = d.get("runs")
ok = isinstance(runs, list) and len(runs) == 3 and isinstance(d.get("median"), dict) and d["median"]
print("ok" if ok else "no")
PY
)
  if [ "$MED" = ok ]; then
    R=$(run_state perf-smoke.yml)
    if [ "$R" = success ]; then row R1 pass "Load baselines (#656, PR 680)" "three runs and median recorded; perf-smoke.yml latest main run: success"
    elif [ "$R" = none ] || [ "$R" = unknown ]; then row R1 pending "Load baselines (#656, PR 680)" "perf-smoke.yml has no run on main yet ($R)"
    else row R1 fail "Load baselines (#656, PR 680)" "perf-smoke.yml latest main run: $R"; fi
  else row R1 pending "Load baselines (#656, PR 680)" "baseline.json has no \"runs\" (three) with a \"median\" object"; fi
fi

# R2 Upgrade from 1.0.0 (#658, PR 683) with the legacy seal scope (PR 692)
wf_item R2 "Upgrade from 1.0.0 (#658, PRs 683, 692)" "683 692" upgrade-test.yml "PRs merged"

# R3 Backup and DR drill (#657, PR 690): RPO and RTO are read from the recorded report, never typed here
MISSING=""; S=$(prs_state "690")
FIG=$(python3 - <<'PY'
import glob, json, re
for f in ["dr-report.json", "deploy/dr/dr-report.json"] + glob.glob("docs/**/dr-report*.json", recursive=True):
    try:
        d = json.load(open(f)); print("RPO %s s, RTO %s s (%s)" % (d["rpoSeconds"], d["rtoSeconds"], f)); raise SystemExit
    except (OSError, KeyError, ValueError): pass
t = open("docs/DISASTER-RECOVERY.md").read()
m = re.search(r"RPO[^0-9\n]{0,20}([0-9.]+) ?s[^\n]{0,40}RTO[^0-9\n]{0,20}([0-9.]+) ?s", t)
if m: print("RPO %s s, RTO %s s (docs/DISASTER-RECOVERY.md)" % m.groups())
PY
)
if [ -z "$FIG" ]; then row R3 pending "Backup and DR drill (#657, PR 690)" "RPO and RTO not recorded in a report or in docs/DISASTER-RECOVERY.md; cannot verify"
else wf_item R3 "Backup and DR drill (#657, PR 690)" "690" dr-drill.yml "$FIG" deploy/dr/drill.sh docs/DISASTER-RECOVERY.md; fi

# R4 Metrics, alerts, dashboards (#659, PR 681)
wf_item R4 "Metrics, alerts, dashboards (#659, PR 681)" "681" observability.yml "PRs merged" docs/OBSERVABILITY.md deploy/observability/check.sh

# R5 HA soak, short CI mode (#655, PR 695) and deterministic timer re-lease (PR 698)
wf_item R5 "HA soak, CI mode (#655, PRs 695, 698)" "695 698" soak-nightly.yml "PRs merged" perf/soak

# R6 72-hour soak on pilot infrastructure
row R6 pending "72-hour soak on pilot infrastructure" "needs the pilot environment the owner chooses (D1)"

# R7 Runbook (#661, PR 697) and support policy (#662, PR 696): document existence, no workflow
MISSING=""; S=$(prs_state "697 696")
if ! have_files wiki/Operations-Runbook.md wiki/Support-and-Versioning-Policy.md; then row R7 pending "Runbook and support policy (#661, #662)" "$S; missing:$MISSING"
elif [ "$S" != "PRs merged" ]; then row R7 pending "Runbook and support policy (#661, #662)" "$S"
else row R7 pass "Runbook and support policy (#661, #662)" "PRs 697, 696 merged; files exist"; fi

# R8 Release gate: SBOM and signature (PR 677). Checks the merge, the files and the latest main run of release.yml
wf_item R8 "Release gate: SBOM and signature (#664)" "677" release.yml "PR 677 merged; scripts exist (the attestations themselves are verified by the release job)" tools/release/verify-release-attestations.sh tools/release/release-sbom.py

# R9 Penetration test, moved to 1.2 after the pilot (owner decision, 28 Nov)
row R9 deferred "Penetration test" "deferred by owner to 1.2, after the pilot (28 Nov)"

# R10 Tomcat CVE exception (owner decision D3): the expiry is read from .grype.yaml
EXP=$(grep -E '^ *\{"vulnerability"' .grype.yaml 2>/dev/null | grep -oE 'expires [0-9]{4}-[0-9]{2}-[0-9]{2}' | head -1 | cut -d' ' -f2)
if [ -z "$EXP" ]; then row R10 pass "Tomcat CVE exception (D3)" "no exception in .grype.yaml"
else
  DAYS=$(python3 -c "import datetime,sys;print((datetime.date.fromisoformat(sys.argv[1])-datetime.date.today()).days)" "$EXP")
  if [ "$DAYS" -lt 0 ]; then row R10 decision "Tomcat CVE exception (D3)" "EXPIRED $EXP; owner decides on extension"
  else row R10 decision "Tomcat CVE exception (D3)" "expires $EXP ($DAYS days); owner decides on extension"; fi
fi

# R11 DB application role must not own the tables (issue #700)
MISSING=""; S=$(prs_state "704")
if ! have_files examples/rule-and-process/src/test/java/io/github/dhluong90/bpm/examples/ruleandprocess/TwoRoleDatabaseIT.java deploy/postgres/app-role-grants.sql; then row R11 pending "DB app role does not own the tables (#700, PR 704)" "missing:$MISSING"
else row R11 "$([ "$S" = "PRs merged" ] && echo pass || echo pending)" "DB app role does not own the tables (#700, PR 704)" "$S; TwoRoleDatabaseIT and app-role-grants.sql exist"; fi

# R12 Secrets and key rotation: the page exists, its PRs are merged, and every link it makes to a file or anchor resolves
R12D=docs/SECRETS-AND-KEY-ROTATION.md; R12P="644 692 703 704"; MISSING=""
if ! have_files "$R12D" wiki/Operations-Runbook.md; then row R12 pending "Secrets and key rotation" "missing:$MISSING"
else
  BAD=$(python3 - <<'PY'
import re, os
doc = open("docs/SECRETS-AND-KEY-ROTATION.md").read()
bad = []
def slugs(path):
    out = set()
    for l in open(path, encoding="utf8"):
        m = re.match(r"#{1,6}\s+(.*)", l)
        if m:
            out.add(re.sub(r"[^\w\- ]", "", m.group(1).strip().lower()).replace(" ", "-"))
    return out
for target, anchor in re.findall(r"\]\(([^)#\s]+)(?:#([^)\s]*))?\)", doc):
    if target.startswith("http"): continue
    path = os.path.normpath(os.path.join("docs", target))
    if not os.path.exists(path): bad.append(target); continue
    if anchor and anchor not in slugs(path): bad.append(target + "#" + anchor)
print(" ".join(bad))
PY
)
  S=$(prs_state "$R12P")
  if [ -n "$BAD" ]; then row R12 pending "Secrets and key rotation" "broken links in $R12D: $BAD"
  elif [ "$S" != "PRs merged" ]; then row R12 pending "Secrets and key rotation" "$S"
  else row R12 pass "Secrets and key rotation" "$R12D; PRs $R12P merged; every link and anchor resolves"; fi
fi

# R13 and R14 pilot-scope gaps: pending until the owner or architect marks each covered elsewhere or out of scope
row R13 pending "Rollback of a bad release" "beyond the 1.0.0 upgrade test; no evidence"
row R14 pending "Multi-tenant isolation test result" "no evidence linked; state covered elsewhere or out of scope"

# R15 Audit and retention configuration: documented, but the retention figures are the owner's decision D2
if have_files docs/AUDIT-AND-RETENTION.md; then row R15 decision "Audit and retention configuration" "docs/AUDIT-AND-RETENTION.md written; retention figures wait for D2"
else row R15 pending "Audit and retention configuration" "missing:$MISSING; tied to D2 (retention)"; fi

# Owner decisions that are not an item above (D3 is R10 and is not counted twice)
row D1 decision "Pilot infrastructure (D1)" "owner chooses the environment (gates R6)"
row D2 decision "SLA, LTS and retention in the policy (D2)" "owner approves the figures in the support policy"

# Sign-off: the doc's sign-off table must have a name and a date
SIGNED=$(python3 - <<'PY'
import re
t = open("docs/PRODUCTION-READINESS.md").read()
m = re.search(r"\|\s*Product owner\s*\|([^|]*)\|([^|]*)\|", t)
print("yes" if m and m.group(1).strip() and m.group(2).strip() else "no")
PY
)

printf '%-5s | %-8s | %-46s | %s\n' ID STATUS ITEM EVIDENCE
printf '%s\n' "------+----------+------------------------------------------------+---------"
printf '%s' "$ROWS"
echo
echo "pass: $NPASS  pending: $NPEND  decision: $NDEC  fail: $NFAIL  deferred: $NDEF  (D3 is R10)"
echo "product owner sign-off recorded: $SIGNED"
if [ $((NPEND+NDEC+NFAIL)) -gt 0 ] || [ "$SIGNED" != yes ]; then
  echo "gate: NOT READY"; [ "$STRICT" = 1 ] && exit 1; exit 0
fi
echo "gate: ready"
exit 0
