#!/usr/bin/env bash
# Writes the OpenAPI schema to docs/openapi.yaml and docs/openapi.json.
#
# There is no schema file in the repository by design: springdoc derives it at runtime from the
# controllers, the DTO records and their Bean Validation annotations, so it cannot drift from the
# code that enforces the rules. This script captures a snapshot of it for reviewers who should not
# have to boot the application. Re-run it whenever an endpoint or a DTO changes.
#
# The backend must be running:  cd backend && ./mvnw spring-boot:run
set -euo pipefail
BASE="${1:-http://localhost:8080}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! curl -sf -o "$ROOT/docs/openapi.yaml" "$BASE/v3/api-docs.yaml"; then
  echo "Could not read $BASE/v3/api-docs.yaml" >&2
  echo "  - is the backend running?   cd backend && ./mvnw spring-boot:run" >&2
  echo "  - is SPRINGDOC_ENABLED set to false?" >&2
  exit 1
fi
curl -sf -o "$ROOT/docs/openapi.json" "$BASE/v3/api-docs"
echo "Wrote docs/openapi.yaml and docs/openapi.json from $BASE"
