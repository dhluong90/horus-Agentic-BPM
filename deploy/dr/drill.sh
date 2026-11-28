#!/usr/bin/env bash
# Disaster recovery drill against the compose stack (docs/DISASTER-RECOVERY.md).
#
#   deploy/dr/drill.sh
#
# 1. starts a separate compose project (horus-dr, port 18080) with the rule-and-process example host in the dev profile;
# 2. seeds decisions, approved rule sets (already sealed by the dev seeder), applications waiting at a task, and a
#    payment review waiting at a timer, then fingerprints the seals and the audit trail;
# 3. backs up with pg_dump (custom format), writes more after the backup, then destroys the database and the host;
# 4. restores into a fresh PostgreSQL, starts the host and checks: seals and audit trail equal the fingerprints, every
#    in-flight instance is there and resumes, the timer fires, the host serves decisions;
# 5. prints RPO and RTO as measured, and writes them to $DR_REPORT (default dr-report.json).
#
# Needs docker compose, python3 and openssl. Takes about 5 to 8 minutes, most of it the first image build. It removes
# its own project (containers and volumes) at the end; set DR_KEEP=1 to keep it, DR_NO_BUILD=1 to reuse the image. Exit 0 when every check passed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
PROJECT="${DR_PROJECT:-horus-dr}"
PORT="${DR_PORT:-18080}"
GAP="${DR_GAP_SECONDS:-10}"          # writes after the backup and before the failure: the data the restore cannot bring back
REPORT="${DR_REPORT:-$ROOT/dr-report.json}"
WORK="$(mktemp -d)"
export HORUS_EXAMPLE=rule-and-process SPRING_PROFILES_ACTIVE=dev HORUS_HTTP_PORT="$PORT"
export HORUS_MANAGEMENT_PORT="" HORUS_MANAGEMENT_EXPOSURE=health

compose() { docker compose -p "$PROJECT" "$@"; }
now()     { python3 -c 'import time; print(f"{time.time():.3f}")'; }
elapsed() { python3 -c "print(f'{$2 - $1:.1f}')"; }
sql()     { compose exec -T postgres psql -U postgres -d horus -At -c "$1"; }
step()    { echo "== $*"; }
cleanup() {
  if [[ "${DR_KEEP:-0}" != 1 ]]; then compose down -v --remove-orphans >/dev/null 2>&1 || true; fi
  rm -rf "$WORK"
}
trap cleanup EXIT

fingerprint() {
  # Seals: every approved version with its seal. Audit trail: every history row of the rule sets and of the instances.
  echo "seals     $(sql "SELECT count(*) || ' ' || md5(coalesce(string_agg(rule_set_id || ':' || version_num || ':' || approval_seal, ',' ORDER BY rule_set_id, version_num), '')) FROM rle_version WHERE approval_seal IS NOT NULL")"
  echo "ruleaudit $(sql "SELECT count(*) || ' ' || md5(coalesce(string_agg(id || action || coalesce(canonical_hash, ''), ',' ORDER BY id), '')) FROM rle_version_history")"
  echo "procaudit $(sql "SELECT count(*) || ' ' || md5(coalesce(string_agg(id, ',' ORDER BY id), '')) FROM pre_history")"
}

[[ -s secrets/demo_password ]] || deploy/create-secrets.sh >/dev/null
PASSWORD_FILE="secrets/demo_password"
BASE="http://127.0.0.1:$PORT"

step "1. start the stack ($PROJECT)"
T_START=$(now)
if [[ -n "${DR_NO_BUILD:-}" ]]; then compose up --no-build -d --wait; else compose up --build -d --wait; fi
echo "up in $(elapsed "$T_START" "$(now)") s"

step "2. seed work through the API"
python3 deploy/dr/drill_client.py seed --base "$BASE" --password-file "$PASSWORD_FILE" --state "$WORK/state.json" --tag "DR$$"
BEFORE="$(fingerprint)"
echo "$BEFORE"
DECISIONS_BEFORE=$(sql "SELECT count(*) FROM rle_decision")
INSTANCES_BEFORE=$(sql "SELECT count(*) FROM pre_instance WHERE status = 'RUNNING'")

step "3. back up with pg_dump (custom format)"
T_BACKUP=$(now)
compose exec -T postgres pg_dump -U postgres -d horus -Fc > "$WORK/horus.dump"
T_BACKUP_END=$(now)
DUMP_BYTES=$(wc -c < "$WORK/horus.dump" | tr -d ' ')
echo "dump $DUMP_BYTES bytes in $(elapsed "$T_BACKUP" "$T_BACKUP_END") s"

step "writes after the backup (the restore cannot bring these back), then a ${GAP} s gap"
for i in 1 2 3 4 5; do
  python3 - "$BASE" "$PASSWORD_FILE" "$i" <<'PY'
import sys; sys.path.insert(0, "deploy/dr")
import drill_client
c = drill_client.Client(sys.argv[1], open(sys.argv[2]).read().strip())
c.evaluate("SILVER", 9, f"after-backup-{sys.argv[3]}")
PY
done
LOST_DECISIONS=$(( $(sql "SELECT count(*) FROM rle_decision") - DECISIONS_BEFORE ))
sleep "$GAP"

step "4. disaster: the host and the database are gone"
T_FAIL=$(now)
compose down -v --remove-orphans >/dev/null 2>&1
echo "volumes removed"

step "5. recover: fresh PostgreSQL, restore, host"
T_RECOVERY=$(now)
compose up -d --wait postgres
T_PG=$(now)
compose exec -T postgres pg_restore -U postgres -d horus --no-owner --role=horus_owner --exit-on-error < "$WORK/horus.dump"
T_RESTORED=$(now)
compose up -d --wait host
T_HOST=$(now)
echo "postgres $(elapsed "$T_RECOVERY" "$T_PG") s, restore $(elapsed "$T_PG" "$T_RESTORED") s, host $(elapsed "$T_RESTORED" "$T_HOST") s"

step "6. verify"
AFTER="$(fingerprint)"
echo "$AFTER"
FINGERPRINT_OK=False; [[ "$BEFORE" == "$AFTER" ]] && FINGERPRINT_OK=True
DECISIONS_AFTER=$(sql "SELECT count(*) FROM rle_decision")
INSTANCES_AFTER=$(sql "SELECT count(*) FROM pre_instance WHERE status = 'RUNNING'")
LAST_SURVIVING=$(sql "SELECT extract(epoch FROM max(created_at)) FROM rle_decision")
set +e
VERIFY="$(python3 deploy/dr/drill_client.py verify --base "$BASE" --password-file "$PASSWORD_FILE" --state "$WORK/state.json")"
VERIFY_STATUS=$?
set -e
echo "$VERIFY"
T_FIRST=$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['firstSuccessAt'])" "$VERIFY")

python3 - "$REPORT" <<PY
import json, sys
verify = json.loads('''$VERIFY''')
rto = $T_FIRST - $T_FAIL
report = {
    "backup": {"tool": "pg_dump -Fc", "bytes": $DUMP_BYTES, "seconds": round($T_BACKUP_END - $T_BACKUP, 1)},
    "rpoSeconds": round($T_FAIL - $LAST_SURVIVING, 1),
    "decisionsLost": $LOST_DECISIONS,
    "rtoSeconds": round(rto, 1),
    "rtoPhases": {"freshPostgres": round($T_PG - $T_RECOVERY, 1), "restore": round($T_RESTORED - $T_PG, 1),
                  "hostStart": round($T_HOST - $T_RESTORED, 1), "firstDecisionServed": round($T_FIRST - $T_HOST, 1)},
    "timerFiredSecondsAfterRecovery": verify.get("timerFiredAfterSeconds"),
    "sealsAndAuditTrailMatch": $FINGERPRINT_OK,
    "runningInstances": {"before": $INSTANCES_BEFORE, "after": $INSTANCES_AFTER},
    "decisions": {"atBackup": $DECISIONS_BEFORE, "afterRestore": $DECISIONS_AFTER},
    "checks": verify["checks"],
}
report["ok"] = report["sealsAndAuditTrailMatch"] and verify["ok"] and $DECISIONS_AFTER == $DECISIONS_BEFORE \
    and $INSTANCES_AFTER >= $INSTANCES_BEFORE
json.dump(report, open(sys.argv[1], "w"), indent=2)
print(json.dumps(report, indent=2))
sys.exit(0 if report["ok"] else 1)
PY
