#!/usr/bin/env bash
# Run the pull-request checks of .github/workflows on this machine.
#
# Usage: tools/ci-local.sh [job ...]      no argument runs the default jobs, in this order, failing fast
#        tools/ci-local.sh all            every job below
#        tools/ci-local.sh verify cockpit only the named jobs
#
# Default jobs (ci.yml, as a pull request runs them):
#   spec-coverage     spec-to-test gate, retry-script and image-pin checks
#   verify            Java 21, Spring Boot 4.x, PostgreSQL 18 (Testcontainers needs Docker)
#   dependency-gates  licence gate and CycloneDX SBOMs, test-oracle allow-list, jar notices
#   vuln-scan         Grype canary and vulnerability gates (Grype on PATH, or Docker)
#   cockpit           licences, schema drift, lint, typecheck, Vitest
#   migration-order   engine migrations in order against origin/main
# On request (run on demand or on path changes in CI):
#   cockpit-ui        Playwright smoke suite (needs the browsers: npx playwright install chromium)
#   query-plan        bounded list queries, PostgreSQL 14 and 18 (query-plan.yml)
#   client            generate and compile the Rule Engine Java client (rule-engine-client.yml)
#   docs-site         build the site from the wiki and check its links (docs-site.yml)
# Not covered: the nightly matrix, the arm64 and compose smoke image build (deploy.yml), and the release dry run.
#
# A tool that is not installed gives "SKIPPED: <tool> not installed", never a silent pass.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"

default_jobs=(spec-coverage verify dependency-gates vuln-scan cockpit migration-order)
all_jobs=("${default_jobs[@]}" cockpit-ui query-plan client docs-site)
if [[ $# -eq 0 ]]; then selected=("${default_jobs[@]}")
elif [[ "$1" == all ]]; then selected=("${all_jobs[@]}")
else selected=("$@"); fi

results=(); failed=0
ci=.github/workflows/ci.yml
pg_image() { sed -nE "s/^  POSTGRES_$1_IMAGE: ([^ ]+).*/\1/p" "$ci" | head -n 1; }

# Docker pull hangs in the macOS credential helper: use an empty config that still sees the CLI plugins.
docker_cfg="$(mktemp -d)"; trap 'rm -rf "$docker_cfg"' EXIT
mkdir -p "$docker_cfg/cli-plugins"
if [[ -d "$HOME/.docker/cli-plugins" ]]; then ln -s "$HOME"/.docker/cli-plugins/* "$docker_cfg/cli-plugins/" 2>/dev/null; fi
export DOCKER_CONFIG="$docker_cfg"

# CI builds on Java 21; use it when installed, otherwise the default JDK.
if jh="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then export JAVA_HOME="$jh"; fi
mvnr=(tools/mvn-retry.sh -B -ntp)
drop_failed_downloads() { find ~/.m2/repository -name '*.lastUpdated' -print -delete 2>/dev/null | sed 's/^/removed /' || true; }

summary() {
  echo; echo "== CI summary"; printf '  %s\n' "${results[@]}"
  if [[ $failed -eq 0 ]]; then echo "RESULT: PASS"; else echo "RESULT: FAIL"; fi
}

run_job() { # name, tools needed (space separated)
  local name="$1" tools="$2" t fn="job_${1//-/_}"
  for t in $tools; do
    if ! command -v "$t" >/dev/null; then
      echo "== $name: SKIPPED: $t not installed"; results+=("SKIPPED  $name ($t not installed)"); return 0
    fi
  done
  echo "== $name"; local start=$SECONDS
  if ( "$fn" ); then results+=("PASS     $name ($((SECONDS-start))s)")
  else results+=("FAIL     $name ($((SECONDS-start))s)"); failed=1; summary; exit 1; fi
}

job_spec_coverage() {
  python3 tools/test_spec_coverage.py -v && python3 tools/spec-coverage.py \
    && tools/test-mvn-retry.sh \
    && python3 tools/test_check_image_pins.py -v && python3 tools/test_rescan_test_images.py -v \
    && python3 tools/check-image-pins.py
}
job_verify() {
  local img; img="$(pg_image 18)"; [[ -n "$img" ]] || { echo "no POSTGRES_18_IMAGE in $ci"; return 1; }
  docker pull "$img" && drop_failed_downloads \
    && (cd cockpit/web && npm ci && npm run build) \
    && "${mvnr[@]}" verify -Dbpm.test.postgres.image="$img"
}
job_dependency_gates() {
  drop_failed_downloads
  python3 tools/check-dependency-policy.py poms && python3 tools/check-dependency-policy.py grype-config \
    && (cd cockpit/web && npm ci && npm run build) \
    && "${mvnr[@]}" verify -DskipTests || return 1
  python3 tools/test_check_test_oracle.py -v \
    && "${mvnr[@]}" -q dependency:tree -Dscope=test -pl rule-engine/rule-engine-core -DoutputFile="$PWD/target/oracle-tree.txt" \
    && python3 tools/check-test-oracle.py tree target/oracle-tree.txt || return 1
  if [[ -f target/bom.json ]]; then python3 tools/check-dependency-policy.py jar-notices || return 1; fi
  if [[ -f target/bom-build.json ]]; then
    "${mvnr[@]}" -q org.apache.maven.plugins:maven-help-plugin:3.6.0:effective-pom -Doutput=target/effective-pom.xml \
      && python3 tools/check-dependency-policy.py add-processors || return 1
  fi
}
job_vuln_scan() {
  [[ -f target/bom.json ]] || { echo "target/bom.json missing: run dependency-gates first"; return 1; }
  tools/scan-dependencies.sh --canary && tools/scan-dependencies.sh && tools/scan-dependencies.sh --build
}
job_cockpit() {
  cd cockpit/web && npm ci && npm run licences && npm run schemas:check && npm run lint && npm run typecheck && npm test
}
job_migration_order() {
  python3 tools/test_check_migration_order.py -v && python3 tools/check-migration-order.py --base origin/main --head HEAD
}
job_cockpit_ui() {
  local image locked
  image="$(sed -nE 's#^ +image: mcr\.microsoft\.com/playwright:v([0-9]+\.[0-9]+\.[0-9]+)-.*#\1#p' "$ci" | head -n 1)"
  locked="$(cd cockpit/web && node -p "require('./package-lock.json').packages['node_modules/@playwright/test'].version")"
  [[ "$locked" == "$image" ]] || { echo "package-lock.json has @playwright/test $locked, ci.yml has '$image'"; return 1; }
  cd cockpit/web && npm ci && npm run e2e -- --workers=100% --grep-invert "_(1440x1200|dark|vi)(_|$)"
}
job_query_plan() {
  local v img
  for v in 14 18; do
    img="$(pg_image "$v")"; [[ -n "$img" ]] || { echo "no POSTGRES_${v}_IMAGE in $ci"; return 1; }
    docker pull "$img" && drop_failed_downloads \
      && "${mvnr[@]}" -pl process-engine/process-engine-postgres -am test \
           -Dtest='BoundedListQueriesTest,ListQueryPlanCheckTest' -Dsurefire.failIfNoSpecifiedTests=false \
           -Dbpm.test.postgres.image="$img" || return 1
  done
}
job_client() {
  local gen=7.27.0 out="$PWD/target/rule-engine-client" tmp; tmp="$(mktemp -d)"
  "${mvnr[@]}" -N org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy \
      -Dartifact="org.openapitools:openapi-generator-cli:$gen" -DoutputDirectory="$tmp" -Dmdep.stripVersion=true \
    && java -jar "$tmp/openapi-generator-cli.jar" generate -i rule-engine/api/openapi.yaml -g java --library native -o "$out" \
      --additional-properties=groupId=io.github.dhluong90.bpm,artifactId=rule-engine-client,hideGenerationTimestamp=true \
      --additional-properties=invokerPackage=io.github.dhluong90.bpm.rules.client \
      --additional-properties=apiPackage=io.github.dhluong90.bpm.rules.client.api \
      --additional-properties=modelPackage=io.github.dhluong90.bpm.rules.client.model \
    && "${mvnr[@]}" -f "$out/pom.xml" -Dmaven.javadoc.skip=true compile
}
job_docs_site() {
  (cd site && npm ci && node ../cockpit/web/scripts/check-licences.mjs --include-dev --exceptions=licence-exceptions.json \
    && npm test && npm run build) \
  && SITE_BASE=/horus-Agentic-BPM/ node site/check-links.mjs \
  && (cd site/.generated/.vitepress/dist \
      && ! grep -rhoE '<(script|link|img|iframe)[^>]*(src|href)="https?://[^"]+' . \
      && ! grep -rliE 'google-analytics|googletagmanager|plausible\.io|gtag\(' . && test -f index.html)
}

for j in "${selected[@]}"; do
  case "$j" in
    spec-coverage)    run_job "$j" "python3 java mvn" ;;
    verify)           run_job "$j" "docker java mvn node npm" ;;
    dependency-gates) run_job "$j" "python3 java mvn node npm" ;;
    vuln-scan)        if command -v grype >/dev/null; then run_job "$j" ""; else run_job "$j" "docker"; fi ;;
    cockpit)          run_job "$j" "node npm" ;;
    migration-order)  run_job "$j" "python3 git" ;;
    cockpit-ui)       run_job "$j" "node npm" ;;
    query-plan)       run_job "$j" "docker java mvn" ;;
    client)           run_job "$j" "java mvn" ;;
    docs-site)        run_job "$j" "node npm" ;;
    *) echo "unknown job: $j (see the header of this script)"; exit 2 ;;
  esac
done
summary
