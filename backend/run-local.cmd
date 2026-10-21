@echo off
REM Runs the backend AND the fake payment processor in one JVM. No Docker, no second service.
REM Requires a locally installed PostgreSQL; one-time setup is in ..\scripts\create-local-db.sql
REM   backend:   http://localhost:8080
REM   processor: http://localhost:8089  (admin: /__admin/requests)
setlocal
cd /d "%~dp0"
call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local %*
