#!/usr/bin/env bash
# Manual smoke test of one reference adapter against the real model API. Never run in CI: it needs a key and the
# network. It prints the outcome's code and labels only; the key stays in your environment.
#
#   MODEL_SMOKE_API=OPENAI_COMPATIBLE MODEL_SMOKE_BASE_URL=https://api.openai.com/v1 \
#     MODEL_SMOKE_MODEL=<model-id> MODEL_SMOKE_API_KEY=... examples/model-adapters/smoke.sh
#   MODEL_SMOKE_API=OPENAI_COMPATIBLE MODEL_SMOKE_BASE_URL=http://localhost:11434/v1 \
#     MODEL_SMOKE_MODEL=<local-model> examples/model-adapters/smoke.sh     # a self-hosted server, no key
#   MODEL_SMOKE_API=ANTHROPIC_MESSAGES MODEL_SMOKE_BASE_URL=https://api.anthropic.com \
#     MODEL_SMOKE_MODEL=claude-opus-5-5 MODEL_SMOKE_API_KEY=... examples/model-adapters/smoke.sh
set -euo pipefail
: "${MODEL_SMOKE_API:?set MODEL_SMOKE_API to OPENAI_COMPATIBLE or ANTHROPIC_MESSAGES}"
: "${MODEL_SMOKE_BASE_URL:?set MODEL_SMOKE_BASE_URL to the API base address}"
: "${MODEL_SMOKE_MODEL:?set MODEL_SMOKE_MODEL to the model id}"
export MODEL_SMOKE_API MODEL_SMOKE_BASE_URL MODEL_SMOKE_MODEL MODEL_SMOKE_API_KEY="${MODEL_SMOKE_API_KEY:-}"
cd "$(dirname "$0")/../.."
mvn -B -q -pl examples/model-adapters -am test -Dtest=ModelSmokeTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcockpit.bundle.skip=true
