#!/usr/bin/env bash
# Tests of tools/mvn-retry.sh with a stub mvn on PATH (#171). No network, no Maven.
#
# Usage:   tools/test-mvn-retry.sh
# Cases:   each stub prints Maven-like output and fails or passes per attempt. The test checks the
#          number of attempts, the exit status and the annotations (GITHUB_ACTIONS=true unless GHA is set):
#            1. success on the first attempt                            1 attempt, exit 0
#            2. plugin resolution failure (CI run 44474251588), then OK  2 attempts, exit 0
#            3. Surefire provider not resolved (run 44466251258), then OK 2 attempts, exit 0
#            4. failing test that prints "Could not find artifact"      1 attempt, exit 1 (reproduced)
#            5. test failures beside a resolution summary line          1 attempt, exit 1
#            6. compile error                                           1 attempt, exit 1
#            7. Central never delivers                                  3 attempts, exit 1, ::error
#            8. MVN_RETRY_WAIT not a whole number                       0 attempts, exit 2
#            9. import POM absent, a false 404 (image build, 20 Nov), then OK 2 attempts, exit 0
#           10. import POM answered 429 after the resolver's retries, then OK 2 attempts, exit 0
#           11. enforcer rule failure                                   1 attempt, exit 1
#           12. outside GitHub Actions (docker build): plain "retrying"  2 attempts, exit 0, no ::warning
# Exit:    0 when every case behaves as expected, 1 otherwise.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
# Keep the exit status: bash 3.2 would otherwise exit with the status of the clean-up.
trap 'status=$?; rm -rf "$WORK"; exit $status' EXIT
mkdir -p "$WORK/bin" "$WORK/home/.m2/repository"

# The stub: STUB_SCRIPT names a file of "attempt-number|exit-status|output" records, one per line, where
# attempt-number is a number or *; output keeps \n and \t escapes, which the stub expands.
cat > "$WORK/bin/mvn" <<'STUB'
#!/usr/bin/env bash
n=$(( $(cat "$STUB_STATE" 2>/dev/null || echo 0) + 1 ))
echo "$n" > "$STUB_STATE"
echo "stub mvn attempt $n: $*"
line="$(grep -E "^($n|\*)\|" "$STUB_SCRIPT" | head -n 1)"
code="$(cut -d'|' -f2 <<< "$line")"
printf '%b\n' "$(cut -d'|' -f3- <<< "$line")"
exit "$code"
STUB
chmod +x "$WORK/bin/mvn"

failures=0
# expect NAME ATTEMPTS EXIT RECORDS [TEXT...]: RECORDS are separated by " ;; "; a TEXT starting with ! must
# not appear in the output.
expect() {
  local name="$1" want_attempts="$2" want_exit="$3" script="$4"; shift 4
  printf '%s\n' "$script" | sed 's/ ;; /\
/g' > "$WORK/script"
  rm -f "$WORK/state"
  local out="$WORK/out" got=0 attempts ok=true text
  PATH="$WORK/bin:$PATH" HOME="$WORK/home" STUB_SCRIPT="$WORK/script" STUB_STATE="$WORK/state" \
    MVN_RETRY_WAIT="${WAIT:-0}" GITHUB_ACTIONS="${GHA-true}" "$ROOT/tools/mvn-retry.sh" -B verify > "$out" 2>&1 || got=$?
  attempts="$(cat "$WORK/state" 2>/dev/null || echo 0)"
  [[ "$got" == "$want_exit" && "$attempts" == "$want_attempts" ]] || ok=false
  for text in "$@"; do
    if [[ "$text" == "!"* ]]; then
      ! grep -qF -- "${text#!}" "$out" || { ok=false; echo "  unexpected: ${text#!}"; }
    else
      grep -qF -- "$text" "$out" || { ok=false; echo "  missing: $text"; }
    fi
  done
  if [[ "$ok" == true ]]; then
    echo "PASS  $name ($attempts attempt(s), exit $got)"
  else
    echo "FAIL  $name: $attempts attempt(s), exit $got; expected $want_attempts, exit $want_exit. Output:"
    sed 's/^/  | /' "$out"
    failures=$((failures + 1))
  fi
}

PLUGIN='[ERROR] Plugin org.apache.maven.plugins:maven-install-plugin:3.1.4 or one of its dependencies could not be resolved:\n[ERROR] \tCould not find artifact org.apache.maven.plugins:maven-install-plugin:jar:3.1.4 in central (https://repo.maven.apache.org/maven2)'
PROVIDER='[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project bpm-host-metadata: The following artifacts could not be resolved: org.apache.maven.surefire:surefire-junit-platform:jar:3.5.6 (absent): Could not find artifact org.apache.maven.surefire:surefire-junit-platform:jar:3.5.6 in central (https://repo.maven.apache.org/maven2) -> [Help 1]'
FLAKY_TEST='[INFO] Running com.example.ResolverTest\n[ERROR] Could not find artifact com.example:missing:jar:1.0 in central (logged by the test)\n[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0\n[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project x: There are test failures.'

expect "success on the first attempt" 1 0 '*|0|[INFO] BUILD SUCCESS'
expect "plugin not delivered, then delivered" 2 0 "1|1|$PLUGIN ;; *|0|[INFO] BUILD SUCCESS" \
  "Attempt 1 of 3: org.apache.maven.plugins:maven-install-plugin:jar:3.1.4 not delivered"
expect "Surefire provider not delivered, then delivered" 2 0 "1|1|$PROVIDER ;; *|0|[INFO] BUILD SUCCESS" \
  "org.apache.maven.surefire:surefire-junit-platform:jar:3.5.6 not delivered"
expect "a failing test that prints 'Could not find artifact' is not retried" 1 1 "*|1|$FLAKY_TEST"
expect "test failures beside a resolution summary are not retried" 1 1 \
  "*|1|[ERROR] Tests run: 4, Failures: 0, Errors: 1\n$PROVIDER"
expect "a compile error is not retried" 1 1 \
  '*|1|[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.15.0:compile (default-compile) on project x: Compilation failure'
expect "Central never delivers: three attempts, then an error" 3 1 "*|1|$PLUGIN" \
  "Attempt 2 of 3" "::error title=Maven Central download failed::Maven Central did not deliver org.apache.maven.plugins:maven-install-plugin:jar:3.1.4 in 3 attempts"
WAIT="30s" expect "MVN_RETRY_WAIT must be a whole number" 0 2 '*|0|unused' "must be a whole number of seconds"

# The image build's failures on 20 Nov 2026 (#295): the BOM import of the root POM not delivered.
IMPORT_404='[ERROR] [ERROR] Some problems were encountered while processing the POMs:\n[ERROR] Non-resolvable import POM: The following artifacts could not be resolved: org.springframework.boot:spring-boot-dependencies:pom:4.1.2 (absent): Could not find artifact org.springframework.boot:spring-boot-dependencies:pom:4.1.2 in central (https://repo.maven.apache.org/maven2) @ line 117, column 19\n[ERROR]   The project io.github.dhluong90.bpm:bpm-parent:0.2.0-SNAPSHOT (/src/pom.xml) has 1 error\n[ERROR]     Non-resolvable import POM: The following artifacts could not be resolved: org.springframework.boot:spring-boot-dependencies:pom:4.1.2 (absent): Could not find artifact org.springframework.boot:spring-boot-dependencies:pom:4.1.2 in central (https://repo.maven.apache.org/maven2) @ line 117, column 19'
IMPORT_429='[ERROR] [ERROR] Some problems were encountered while processing the POMs:\n[ERROR] Non-resolvable import POM: Could not transfer artifact org.springframework.boot:spring-boot-dependencies:pom:4.1.2 from/to central (https://repo.maven.apache.org/maven2): status code: 429, reason phrase: Too Many Requests (429) @ line 117, column 19'
ENFORCER='[ERROR] Failed to execute goal org.apache.maven.plugins:maven-enforcer-plugin:3.6.3:enforce (enforce-build-baseline) on project bpm-parent:\n[ERROR] Rule 0: org.apache.maven.enforcer.rules.dependency.BannedDependencies failed with message:'

expect "import POM absent (false 404), then delivered" 2 0 "1|1|$IMPORT_404 ;; *|0|[INFO] BUILD SUCCESS" \
  "Attempt 1 of 3: org.springframework.boot:spring-boot-dependencies:pom:4.1.2 not delivered"
expect "import POM answered 429 after the resolver's retries, then delivered" 2 0 "1|1|$IMPORT_429 ;; *|0|[INFO] BUILD SUCCESS" \
  "Attempt 1 of 3: org.springframework.boot:spring-boot-dependencies:pom:4.1.2 not delivered"
expect "an enforcer rule failure is not retried" 1 1 "*|1|$ENFORCER" "!Retrying"
GHA="" expect "outside GitHub Actions the retry is a plain line" 2 0 "1|1|$IMPORT_404 ;; *|0|[INFO] BUILD SUCCESS" \
  "mvn-retry: warning: Attempt 1 of 3: org.springframework.boot:spring-boot-dependencies:pom:4.1.2 not delivered. Retrying with -U" \
  "!::warning"

if (( failures > 0 )); then
  echo "mvn-retry tests: $failures case(s) failed."
  exit 1
fi
echo "mvn-retry tests: all 12 cases behaved as expected."
