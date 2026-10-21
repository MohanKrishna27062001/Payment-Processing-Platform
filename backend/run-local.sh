#!/usr/bin/env bash
# Runs the backend AND the fake payment processor in one JVM. No Docker, no second service.
# Requires a locally installed PostgreSQL; one-time setup is in ../scripts/create-local-db.sql
#   backend:   http://localhost:8080
#   processor: http://localhost:8089  (admin: /__admin/requests)
set -e
cd "$(dirname "$0")"
./mvnw spring-boot:run -Dspring-boot.run.profiles=local "$@"
