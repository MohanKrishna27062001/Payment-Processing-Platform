@echo off
REM Writes the OpenAPI schema to docs\openapi.yaml and docs\openapi.json.
REM
REM There is no schema file in the repository by design: springdoc derives it at runtime from the
REM controllers, the DTO records and their Bean Validation annotations, so it cannot drift from the
REM code that enforces the rules. This script captures a snapshot of it for reviewers who should not
REM have to boot the application. Re-run it whenever an endpoint or a DTO changes.
REM
REM The backend must be running:  cd backend ^&^& .\mvnw.cmd spring-boot:run
setlocal
set BASE=http://localhost:8080
if not "%~1"=="" set BASE=%~1
set ROOT=%~dp0..
curl -sf -o "%ROOT%\docs\openapi.yaml" "%BASE%/v3/api-docs.yaml" || goto fail
curl -sf -o "%ROOT%\docs\openapi.json" "%BASE%/v3/api-docs"      || goto fail
echo Wrote docs\openapi.yaml and docs\openapi.json from %BASE%
exit /b 0
:fail
echo.
echo Could not read %BASE%/v3/api-docs
echo   - is the backend running?   cd backend ^&^& .\mvnw.cmd spring-boot:run
echo   - is SPRINGDOC_ENABLED set to false?
exit /b 1
