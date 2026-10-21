#!/usr/bin/env bash
# Runs the backend with no PostgreSQL installed and no Docker.
#
# The application starts its own PostgreSQL from binaries that arrive as Maven dependencies, applies the
# migrations and seeds the demo merchants. The data lives in a temp directory and is discarded on exit.
#
# The dashboard is separate:  cd ../frontend && npm install && npm run dev
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
exec ./mvnw spring-boot:run -Dspring-boot.run.profiles=embedded "$@"
