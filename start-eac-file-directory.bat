@echo off
setlocal

cd /d "%~dp0"

echo.
echo ========================================
echo  EAC File Directory Local Launcher
echo ========================================
echo.

if not exist ".env" (
    echo [ERROR] .env was not found.
    echo Copy .env.example to .env and fill in the required values first.
    echo.
    pause
    exit /b 1
)

echo [1/4] Updating APP_BASE_URL from current LAN IPv4...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0update-base-url.ps1"
if errorlevel 1 (
    echo.
    echo [ERROR] update-base-url.ps1 failed. Spring Boot was not started.
    echo.
    pause
    exit /b 1
)

echo.
echo [2/4] Checking ONLYOFFICE_JWT_SECRET...
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$envFile = Join-Path '%~dp0' '.env'; $secret = (Get-Content $envFile | Where-Object { $_ -match '^\s*ONLYOFFICE_JWT_SECRET\s*=' } | Select-Object -First 1) -replace '^\s*ONLYOFFICE_JWT_SECRET\s*=', ''; if ([string]::IsNullOrWhiteSpace($secret)) { exit 1 }"
if errorlevel 1 (
    echo [ERROR] ONLYOFFICE_JWT_SECRET is empty in .env.
    echo Set a non-empty shared secret before starting Docker Compose.
    echo.
    pause
    exit /b 1
)

echo.
echo [3/4] Starting ONLYOFFICE Document Server...
docker compose up -d onlyoffice
if errorlevel 1 (
    echo.
    echo [ERROR] Docker Compose failed to start ONLYOFFICE.
    echo Make sure Docker Desktop is running.
    echo.
    pause
    exit /b 1
)

echo.
echo [4/4] Starting Spring Boot on http://localhost:8084 ...
set "MAVEN_USER_HOME=%USERPROFILE%\.m2"
start "EAC File Directory - Spring Boot" cmd /k "cd /d ""%~dp0"" && set ""MAVEN_USER_HOME=%USERPROFILE%\.m2"" && call mvnw.cmd spring-boot:run"

echo.
echo Started:
echo   ONLYOFFICE: http://localhost:8085
echo   Spring Boot: http://localhost:8084
echo.
echo Leave the Spring Boot window open while using the app.
echo To stop ONLYOFFICE later, run: docker compose down
echo.
pause
