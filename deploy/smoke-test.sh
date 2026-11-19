#!/usr/bin/env bash
# Smoke test of the docker compose deployment (docs/DEPLOYING.md). Run from the repository root after
# "docker compose up --build --wait". Needs bash and curl. Reads HORUS_DEMO_PASSWORD, HORUS_HTTP_PORT and
# HORUS_EXAMPLE from the environment or, when unset, from ./.env.
#
#   1. GET /actuator/health answers {"status":"UP"} (waits up to SMOKE_TIMEOUT seconds, default 180).
#   2. GET /api/bpm/meta without a caller answers 401.
#   3. The demo user lan signs in with the host's form; GET /api/bpm/meta then answers 200 with the host id.
#   4. Rule lifecycle: hoa (author and approver) imports rules/card-fee.yaml and requests publication,
#      hoa's own approval is refused with 403 SELF_APPROVAL_FORBIDDEN, minh approves, and tuan evaluates a
#      Gold customer and gets rule R1 and a fee of 0 VND.
#   5. Cockpit: GET /bpm/cockpit/ with lan's session answers 200 with the cockpit page.
#      Steps 4 and 5 run only when the host serves them. A host built from a commit before the rule
#      lifecycle endpoints or the cockpit answers the probe with a plain 404, and the step is reported as SKIP.
#
# Exit 0 when every step that ran passed, 1 otherwise.

set -euo pipefail

env_value() { # env_value NAME: the variable from the environment, else from ./.env
  local name="$1"
  if [[ -n "${!name:-}" ]]; then printf '%s' "${!name}"; return; fi
  [[ -f .env ]] && sed -n "s/^${name}=//p" .env | tail -n 1
}

PORT="$(env_value HORUS_HTTP_PORT)"; PORT="${PORT:-8080}"
EXAMPLE="$(env_value HORUS_EXAMPLE)"; EXAMPLE="${EXAMPLE:-rule-engine-only}"
DEMO_PASSWORD="$(env_value HORUS_DEMO_PASSWORD)"
BASE="${SMOKE_BASE_URL:-http://127.0.0.1:${PORT}}"
TIMEOUT="${SMOKE_TIMEOUT:-180}"
RULES="$BASE/api/bpm/rules/v1"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
skipped=0

pass() { printf 'PASS  %s\n' "$*"; }
skip() { printf 'SKIP  %s\n' "$*"; }
fail() { printf 'FAIL  %s\n' "$*" >&2; exit 1; }

# Credentials never appear on a command line (ps would show them): each demo user gets a curl config
# file in $WORK, written by the printf builtin and read with curl -K.
umask 077
quote() { local v="${1//\\/\\\\}"; printf '%s' "${v//\"/\\\"}"; }
for user in lan minh hoa tuan; do
  printf 'user = "%s:%s"\n' "$user" "$(quote "$DEMO_PASSWORD")" > "$WORK/$user.curlrc"
done
printf 'data-urlencode = "password=%s"\n' "$(quote "$DEMO_PASSWORD")" > "$WORK/form.curlrc"

# call METHOD URL [curl args...]: prints the HTTP status; the body is in $WORK/body
call() {
  local method="$1" url="$2"; shift 2
  curl -s -o "$WORK/body" -w '%{http_code}' -X "$method" "$url" "$@"
}

# 1. Health
deadline=$(( $(date +%s) + TIMEOUT ))
until [[ "$(curl -s "$BASE/actuator/health" 2>/dev/null || true)" == *'"status":"UP"'* ]]; do
  (( $(date +%s) < deadline )) || fail "health did not reach UP within ${TIMEOUT}s at $BASE/actuator/health"
  sleep 2
done
pass "health: $(curl -s "$BASE/actuator/health")"

# 2. The engine API refuses an anonymous caller
status="$(call GET "$BASE/api/bpm/meta")"
[[ "$status" == 401 ]] || fail "anonymous GET /api/bpm/meta: expected 401, got $status: $(cat "$WORK/body")"
pass "anonymous GET /api/bpm/meta: 401 $(grep -o '"code":"[A-Z_]*"' "$WORK/body" || true)"

# 3. Form sign-in as lan, then metadata with the session
[[ -n "$DEMO_PASSWORD" ]] || fail "HORUS_DEMO_PASSWORD is empty; set it in .env and start with SPRING_PROFILES_ACTIVE=dev"
jar="$WORK/cookies"
csrf="$(curl -s -c "$jar" -b "$jar" "$BASE/login" | sed -n 's/.*name="_csrf" type="hidden" value="\([^"]*\)".*/\1/p')"
[[ -n "$csrf" ]] || fail "no sign-in form at $BASE/login; is the host on the dev profile?"
login="$(curl -s -c "$jar" -b "$jar" -o /dev/null -w '%{http_code} %{redirect_url}' \
  -K "$WORK/form.curlrc" --data-urlencode "username=lan" --data-urlencode "_csrf=$csrf" "$BASE/login")"
[[ "$login" != *"error"* ]] || fail "sign-in as lan refused ($login); check HORUS_DEMO_PASSWORD"
status="$(call GET "$BASE/api/bpm/meta" -b "$jar")"
[[ "$status" == 200 && "$(cat "$WORK/body")" == *'"hostId"'* ]] || fail "GET /api/bpm/meta as lan: expected 200, got $status: $(cat "$WORK/body")"
pass "GET /api/bpm/meta as lan: 200 $(cat "$WORK/body")"

# 4. Rule lifecycle, when the host serves it
status="$(call GET "$RULES/rule-sets/card-fee/versions/1" -b "$jar")"
body="$(cat "$WORK/body")"
if [[ "$status" == 404 && "$body" != *'"code":"VERSION_NOT_FOUND"'* && "$body" != *'"code":"RULE_SET_NOT_FOUND"'* ]]; then
  skip "rule lifecycle: this host has no rule lifecycle endpoints yet (probe answered a plain 404)"
  skipped=1
else
  rule_file="examples/${EXAMPLE}/src/main/resources/rules/card-fee.yaml"
  if [[ "$status" == 404 ]]; then
    [[ -f "$rule_file" ]] || fail "rule file $rule_file not found; run from the repository root"
    status="$(call POST "$RULES/rule-sets" -K "$WORK/hoa.curlrc" -H 'Content-Type: application/yaml' \
      -H "Idempotency-Key: smoke-import-$(date +%s)" --data-binary "@$rule_file")"
    [[ "$status" == 201 ]] || fail "import as hoa: expected 201, got $status: $(cat "$WORK/body")"
    pass "import card-fee as hoa: 201 $(grep -o '"status":"[A-Z_]*"' "$WORK/body" | head -n 1)"

    status="$(call POST "$RULES/rule-sets/card-fee/versions/1:request-publish" -K "$WORK/hoa.curlrc")"
    [[ "$status" == 200 ]] || fail "request publication as hoa: expected 200, got $status: $(cat "$WORK/body")"
    pass "request publication as hoa: 200 $(grep -o '"status":"[A-Z_]*"' "$WORK/body" | head -n 1)"

    status="$(call POST "$RULES/rule-sets/card-fee/versions/1:approve" -K "$WORK/hoa.curlrc")"
    [[ "$status" == 403 && "$(cat "$WORK/body")" == *SELF_APPROVAL_FORBIDDEN* ]] \
      || fail "self-approval as hoa: expected 403 SELF_APPROVAL_FORBIDDEN, got $status: $(cat "$WORK/body")"
    pass "self-approval as hoa refused: 403 $(grep -o '"code":"[A-Z_]*"' "$WORK/body" | head -n 1)"

    status="$(call POST "$RULES/rule-sets/card-fee/versions/1:approve" -K "$WORK/minh.curlrc" \
      -H 'Content-Type: application/json' -d '{"comment":"Smoke test"}')"
    [[ "$status" == 200 ]] || fail "approve as minh: expected 200, got $status: $(cat "$WORK/body")"
    pass "approve as minh: 200 $(grep -o '"status":"[A-Z_]*"' "$WORK/body" | head -n 1)"
  else
    [[ "$status" == 200 ]] || fail "probe of card-fee v1: unexpected $status: $body"
    pass "card-fee v1 already imported by an earlier run: $(grep -o '"status":"[A-Z_]*"' "$WORK/body" | head -n 1)"
  fi

  status="$(call POST "$RULES/decisions/card-fee:evaluate" -K "$WORK/tuan.curlrc" \
    -H 'Content-Type: application/json' -d '{"inputs":{"tier":"GOLD","txCount":5}}')"
  body="$(cat "$WORK/body")"
  [[ "$status" == 200 && "$body" == *'"matchedRuleIds":["R1"]'* && "$body" == *'"amount":0'* ]] \
    || fail "evaluate as tuan: expected 200 with R1 and 0 VND, got $status: $body"
  pass "evaluate card-fee as tuan: 200 $body"
fi

# 5. Cockpit, when the host serves it
status="$(call GET "$BASE/bpm/cockpit/" -L -b "$jar")"
if [[ "$status" == 404 ]]; then
  skip "cockpit: this host serves no cockpit (GET /bpm/cockpit/ answered 404)"
  skipped=1
else
  [[ "$status" == 200 && "$(cat "$WORK/body")" == *"<title>HORUS Cockpit</title>"* ]] \
    || fail "cockpit as lan: expected 200 with the cockpit page, got $status"
  pass "cockpit as lan: 200 <title>HORUS Cockpit</title>"
fi

if (( skipped )); then echo "Smoke test passed (some steps skipped)."; else echo "Smoke test passed."; fi
