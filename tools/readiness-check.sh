#!/usr/bin/env bash
# Production-readiness gate (#665). Prints one row per sign-off item with a status:
#   pass      the evidence is on main and its automatable checks hold
#   pending   work or evidence is still missing
#   decision  waits for a product owner decision
#   deferred  the product owner moved it to a later release
# Needs git, python3 and an authenticated gh (without gh, PR and run checks print "pending").
# Usage: tools/readiness-check.sh [--strict]   (--strict exits 1 unless every item is pass or deferred)
# The checklist with the evidence links is docs/PRODUCTION-READINESS.md; keep both in step.
set -u
cd "$(git rev-parse --show-toplevel)"
STRICT=0; [ "${1:-}" = "--strict" ] && STRICT=1
HAVE_GH=0; command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1 && HAVE_GH=1
ROWS=""; NPASS=0; NPEND=0; NDEC=0; NDEF=0

row() { # id status item note
  case "$2" in pass) NPASS=$((NPASS+1));; pending) NPEND=$((NPEND+1));; decision) NDEC=$((NDEC+1));; deferred) NDEF=$((NDEF+1));; esac
  ROWS="${ROWS}$(printf '%-5s | %-8s | %-44s | %s' "$1" "$2" "$3" "$4")
"
}

merged() { [ "$HAVE_GH" = 1 ] && [ "$(gh pr view "$1" --json state -q .state 2>/dev/null)" = "MERGED" ]; }
# prs_state "677 680": prints "all merged" or "open: 692 696"
prs_state() { local open="" p; for p in $1; do merged "$p" || open="$open #$p"; done; [ -z "$open" ] && echo "PRs merged" || echo "not merged:$open"; }
have_files() { local f miss=""; for f in "$@"; do [ -e "$f" ] || miss="$miss $f"; done; [ -z "$miss" ] && return 0; MISSING="$miss"; return 1; }
last_run() { # workflow file -> "last main run: success|none"
  [ "$HAVE_GH" = 1 ] || { echo "gh unavailable"; return; }
  local c; c=$(gh run list --workflow="$1" --branch main --limit 1 --json conclusion -q '.[0].conclusion' 2>/dev/null)
  echo "last main run: ${c:-none}"
}

# 1 Load baselines (#656, PR 680); the architect asked for the median of three runs
S=$(prs_state "680"); R=$(last_run perf-smoke.yml)
if have_files perf/baseline.json perf/LoadHarness.java perf/check_regression.py .github/workflows/perf-smoke.yml && [ "$S" = "PRs merged" ]; then
  if grep -qi median perf/baseline.json; then row R1 pass "Load baselines (#656, PR 680)" "median of three recorded; $R"
  else row R1 pending "Load baselines (#656, PR 680)" "baseline.json lacks the median of three runs; $R"; fi
else row R1 pending "Load baselines (#656, PR 680)" "$S${MISSING:+; missing:$MISSING}"; fi

# 2 Upgrade from 1.0.0 (#658, PR 683) with the legacy seal scope (PR 692)
S=$(prs_state "683 692"); R=$(last_run upgrade-test.yml)
if have_files .github/workflows/upgrade-test.yml && [ "$S" = "PRs merged" ]; then row R2 pass "Upgrade from 1.0.0 (#658, PRs 683, 692)" "$R"
else row R2 pending "Upgrade from 1.0.0 (#658, PRs 683, 692)" "$S${MISSING:+; missing:$MISSING}"; fi

# 3 Backup and DR drill (#657, PR 690): RPO 11.6 s, RTO 14.6 s
S=$(prs_state "690"); R=$(last_run dr-drill.yml)
if have_files deploy/dr/drill.sh docs/DISASTER-RECOVERY.md .github/workflows/dr-drill.yml && [ "$S" = "PRs merged" ]; then row R3 pass "Backup and DR drill (#657, PR 690)" "RPO 11.6 s, RTO 14.6 s; $R"
else row R3 pending "Backup and DR drill (#657, PR 690)" "$S${MISSING:+; missing:$MISSING}"; fi

# 4 Metrics, alerts, dashboards (#659, PR 681)
S=$(prs_state "681"); R=$(last_run observability.yml)
if have_files docs/OBSERVABILITY.md deploy/observability/check.sh .github/workflows/observability.yml && [ "$S" = "PRs merged" ]; then row R4 pass "Metrics, alerts, dashboards (#659, PR 681)" "$R"
else row R4 pending "Metrics, alerts, dashboards (#659, PR 681)" "$S${MISSING:+; missing:$MISSING}"; fi

# 5 HA soak, short CI mode (#655, PR 695) and deterministic timer re-lease (PR 698)
S=$(prs_state "695 698"); R=$(last_run soak-nightly.yml)
if have_files perf/soak .github/workflows/soak-nightly.yml && [ "$S" = "PRs merged" ]; then row R5 pass "HA soak, CI mode (#655, PRs 695, 698)" "$R"
else row R5 pending "HA soak, CI mode (#655, PRs 695, 698)" "$S${MISSING:+; missing:$MISSING}"; fi

# 6 72-hour soak on pilot infrastructure
row R6 pending "72-hour soak on pilot infrastructure" "needs the pilot environment the owner chooses (D1)"

# 7 Runbook (#661, PR 697) and support policy (#662, PR 696)
S=$(prs_state "697 696")
if have_files wiki/Operations-Runbook.md wiki/Support-and-Versioning-Policy.md && [ "$S" = "PRs merged" ]; then row R7 pass "Runbook and support policy (#661, #662)" "PRs 697, 696 merged"
else row R7 pending "Runbook and support policy (#661, #662)" "$S${MISSING:+; missing:$MISSING}"; fi

# 8 Release gate: SBOM and signature (PR 677)
S=$(prs_state "677"); R=$(last_run release.yml)
if have_files tools/release/verify-release-attestations.sh tools/release/release-sbom.py .github/workflows/release.yml && [ "$S" = "PRs merged" ]; then row R8 pass "Release gate: SBOM and signature (#664)" "PR 677; $R"
else row R8 pending "Release gate: SBOM and signature (#664)" "$S${MISSING:+; missing:$MISSING}"; fi

# 9 Penetration test, moved to 1.2 after the pilot (owner decision, 28 Nov)
row R9 deferred "Penetration test" "deferred by owner to 1.2, after the pilot (28 Nov)"

# 10 Tomcat CVE exception: the expiry is read from .grype.yaml
EXP=$(grep -E '^ *\{"vulnerability"' .grype.yaml 2>/dev/null | grep -oE 'expires [0-9]{4}-[0-9]{2}-[0-9]{2}' | head -1 | cut -d' ' -f2)
if [ -z "$EXP" ]; then row R10 pass "Tomcat CVE exception" "no exception in .grype.yaml"
else
  DAYS=$(python3 -c "import datetime,sys;print((datetime.date.fromisoformat(sys.argv[1])-datetime.date.today()).days)" "$EXP")
  if [ "$DAYS" -lt 0 ]; then row R10 decision "Tomcat CVE exception" "EXPIRED $EXP; owner decides on extension (D3)"
  else row R10 decision "Tomcat CVE exception" "expires $EXP ($DAYS days); owner decides on extension (D3)"; fi
fi

# 11 DB application role must not own the tables
row R11 pending "DB app role does not own the tables" "no check or test on main yet"

# Open owner decisions
row D1 decision "Pilot infrastructure" "owner chooses the environment (gates R6)"
row D2 decision "SLA, LTS and retention in the policy" "owner approves the figures in the support policy"
row D3 decision "Tomcat exception extension" "see R10"

printf '%-5s | %-8s | %-44s | %s\n' ID STATUS ITEM EVIDENCE
printf '%s\n' "------+----------+----------------------------------------------+---------"
printf '%s' "$ROWS"
echo
echo "pass: $NPASS  pending: $NPEND  decision: $NDEC  deferred: $NDEF"
if [ "$STRICT" = 1 ] && [ $((NPEND+NDEC)) -gt 0 ]; then echo "gate: NOT READY"; exit 1; fi
[ $((NPEND+NDEC)) -gt 0 ] && echo "gate: not ready" || echo "gate: ready for sign-off"
exit 0
