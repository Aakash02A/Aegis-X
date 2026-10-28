@echo off
setlocal
echo =======================================
echo Building Aegis-X Full Stack Production Artifacts
echo =======================================

echo 1. Building Backend Spring Boot JAR...
cd /d "%~dp0..\backend"
call ./mvnw.cmd clean package -DskipTests
if errorlevel 1 (
  echo Backend build failed.
  exit /b 1
)

echo 2. Building Frontend Vite Bundle...
cd /d "%~dp0..\frontend"
call npm.cmd run build
if errorlevel 1 (
  echo Frontend build failed.
  exit /b 1
)

echo.
echo =======================================
echo Full Stack Build Successful!
echo Backend JAR: backend\target\aegisx-0.0.1-SNAPSHOT.jar
echo Frontend Dist: frontend\dist\
echo =======================================

