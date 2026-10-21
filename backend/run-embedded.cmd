@echo off
REM Runs the backend with no PostgreSQL installed and no Docker.
REM
REM The application starts its own PostgreSQL from binaries that arrive as Maven dependencies, applies the
REM migrations and seeds the demo merchants. The data lives in a temp directory and is discarded on exit.
REM
REM The dashboard is separate:  cd ..\frontend  &&  npm install  &&  npm run dev
cd /d "%~dp0"
call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=embedded %*
