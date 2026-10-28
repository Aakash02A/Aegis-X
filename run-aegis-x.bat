@echo off
setlocal
title Aegis-X Local Demo
cd /d "%~dp0"

echo.
echo Aegis-X local demo startup
echo Database: MySQL 8.4 (the only supported database)
echo =========================

docker info >nul 2>&1
if errorlevel 1 (
  echo Docker Desktop is not running. Start it and run this script again.
  pause
  exit /b 1
)

node --version >nul 2>&1
if errorlevel 1 (
  echo Node.js is required to start the frontend. Install Node.js and retry.
  pause
  exit /b 1
)

if not exist ".env" (
  copy ".env.example" ".env" >nul
  echo Created .env from .env.example. Review local database and integration values if needed.
)

if not exist "frontend\node_modules\vite\bin\vite.js" (
  echo Installing frontend dependencies from package-lock.json...
  call npm ci --prefix frontend
  if errorlevel 1 (
    echo Frontend dependency installation failed.
    pause
    exit /b 1
  )
)

echo Starting MySQL 8.4 and the development-profile backend...
docker compose up --build -d app
if errorlevel 1 (
  echo Backend startup failed. Check Docker Desktop and run: docker compose logs mysql app
  pause
  exit /b 1
)

echo Starting the Vite frontend at http://127.0.0.1:3000 ...
curl -fsS http://127.0.0.1:3000/ >nul 2>&1
if errorlevel 1 (
  start "Aegis-X Frontend" /D "%~dp0frontend" cmd /k "npm run dev -- --host 127.0.0.1 --port 3000 --strictPort"
)

echo Waiting for backend health...
rem MySQL's first boot initialises its data directory, so it gets a generous budget.
set /a maxAttempts=90
set /a attempts=0
:wait_backend
set /a attempts+=1
curl -fsS http://localhost:8080/actuator/health >nul 2>&1
if not errorlevel 1 goto ready
if %attempts% GEQ %maxAttempts% goto backend_timeout
timeout /t 2 /nobreak >nul
goto wait_backend

:backend_timeout
echo Backend has not reported healthy yet. Inspect: docker compose logs -f app mysql
goto open_demo

:ready
echo Backend is healthy.

:open_demo
echo.
echo Frontend:  http://127.0.0.1:3000
echo Backend:   http://localhost:8080
echo Health:    http://localhost:8080/actuator/health
echo Database:  localhost:3307, container port 3306 (database aegisx)
echo.
echo Register a merchant account from the landing page, then sign in.
echo Local payment is simulated by the development profile; it does not charge a card.
start "" http://127.0.0.1:3000/
echo Stop services with: docker compose down
pause
endlocal
