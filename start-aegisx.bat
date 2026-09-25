@echo off
echo ===============================================
echo         Starting Aegis-X Platform
echo ===============================================
echo.

REM Check if Docker is running
docker --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Docker is not installed or not running
    echo Please install Docker Desktop and start it before running this script
    pause
    exit /b 1
)

echo [1/4] Checking environment configuration...
if not exist .env (
    echo WARNING: .env file not found. Creating from template...
    copy .env.example .env
    echo.
    echo IMPORTANT: Please edit .env file with your actual credentials before continuing
    echo Press any key to open .env file for editing...
    pause
    notepad .env
    echo.
)

echo [2/4] Starting PostgreSQL database...
docker compose up -d postgres
if %errorlevel% neq 0 (
    echo ERROR: Failed to start PostgreSQL
    pause
    exit /b 1
)

echo Waiting for PostgreSQL to be ready...
timeout /t 10 /nobreak >nul

echo [3/4] Building and starting Aegis-X backend...
docker compose up -d app
if %errorlevel% neq 0 (
    echo ERROR: Failed to start Aegis-X backend
    pause
    exit /b 1
)

echo [4/4] Waiting for application to start...
timeout /t 30 /nobreak >nul

echo.
echo ===============================================
echo         Aegis-X Platform Started!
echo ===============================================
echo.
echo Backend API: http://localhost:8080
echo Frontend:    Open frontend/index.html in browser
echo Health:      http://localhost:8080/actuator/health
echo API Docs:    http://localhost:8080/api/health
echo.
echo Docker Services Status:
docker compose ps
echo.

REM Try to open frontend in default browser
if exist "frontend\index.html" (
    echo Opening frontend in your default browser...
    start "" "frontend\index.html"
) else (
    echo Frontend not found at frontend\index.html
)

echo.
echo To stop the application, run: docker compose down
echo To view logs, run: docker compose logs -f app
echo.
pause