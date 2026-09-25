@echo off
:: ==============================================================================
:: Aegis-X Application Launcher for Windows
:: Starts the complete Aegis-X system (Backend + Frontend + Database)
:: ==============================================================================

title Aegis-X Launcher

:: Color codes for output
set GREEN=[32m
set YELLOW=[33m
set RED=[31m
set BLUE=[34m
set NC=[0m

echo.
echo %BLUE%==========================================%NC%
echo %BLUE%  🛡️  Aegis-X Application Launcher  🛡️   %NC%
echo %BLUE%==========================================%NC%
echo.

:: Check if Docker is installed and running
echo %YELLOW%[1/6] Checking Docker installation...%NC%
docker --version >nul 2>&1
if errorlevel 1 (
    echo %RED%❌ Docker is not installed or not running!%NC%
    echo %RED%   Please install Docker Desktop and try again.%NC%
    pause
    exit /b 1
)
echo %GREEN%✅ Docker is available%NC%
echo.

:: Check if .env file exists
echo %YELLOW%[2/6] Checking environment configuration...%NC%
if not exist ".env" (
    echo %YELLOW%⚠️  .env file not found. Creating from template...%NC%
    copy ".env.example" ".env" >nul
    echo %YELLOW%📝 Please edit .env file with your API keys before continuing.%NC%
    echo %YELLOW%   Required: JWT_SECRET, OPENAI_API_KEY, RAZORPAY_*, WHATSAPP_*%NC%
    echo.
    set /p continue="Continue with default values? (y/N): "
    if /i not "!continue!"=="y" (
        echo %YELLOW%Opening .env file for editing...%NC%
        notepad ".env"
        echo %YELLOW%Press any key after editing .env file...%NC%
        pause >nul
    )
)
echo %GREEN%✅ Environment configuration ready%NC%
echo.

:: Stop any existing containers
echo %YELLOW%[3/6] Stopping existing containers...%NC%
docker compose down >nul 2>&1
echo %GREEN%✅ Previous containers stopped%NC%
echo.

:: Pull latest images and build
echo %YELLOW%[4/6] Building and starting services...%NC%
echo %BLUE%This may take a few minutes on first run...%NC%
docker compose up --build -d
if errorlevel 1 (
    echo %RED%❌ Failed to start services!%NC%
    echo %RED%   Check Docker Desktop and try again.%NC%
    pause
    exit /b 1
)
echo %GREEN%✅ Services started successfully%NC%
echo.

:: Wait for services to be ready
echo %YELLOW%[5/6] Waiting for services to be ready...%NC%
set /a counter=0
:wait_loop
set /a counter+=1
if %counter% GTR 30 (
    echo %RED%❌ Services took too long to start%NC%
    goto :show_status
)

:: Check backend health
curl -s http://localhost:8080/actuator/health >nul 2>&1
if errorlevel 1 (
    echo %YELLOW%⏳ Waiting for backend... (%counter%/30)%NC%
    timeout /t 2 >nul
    goto :wait_loop
)

echo %GREEN%✅ All services are ready!%NC%
echo.

:: Show service status and URLs
:show_status
echo %YELLOW%[6/6] Service Status:%NC%
echo.
echo %BLUE%🚀 Aegis-X is now running!%NC%
echo.
echo %GREEN%📡 Backend API:%NC%      http://localhost:8080
echo %GREEN%🌐 Frontend:%NC%         http://localhost:3000 (or open frontend/index.html)
echo %GREEN%🏥 Health Check:%NC%     http://localhost:8080/actuator/health
echo %GREEN%📚 API Docs:%NC%         http://localhost:8080/swagger-ui.html
echo %GREEN%🗄️  Database:%NC%        PostgreSQL running on port 5432
echo.

:: Show container status
echo %BLUE%Container Status:%NC%
docker compose ps
echo.

:: Show logs option
echo %YELLOW%Available Commands:%NC%
echo   📊 View logs:           docker compose logs -f
echo   🔄 Restart services:    docker compose restart  
echo   🛑 Stop services:       docker compose down
echo   🔍 Check health:        curl http://localhost:8080/actuator/health
echo.

:: Open frontend automatically
set /p open_browser="🌐 Open frontend in browser? (Y/n): "
if /i not "%open_browser%"=="n" (
    echo %BLUE%Opening frontend...%NC%
    start http://localhost:3000 2>nul || start frontend/index.html
)

:: Show demo credentials
echo.
echo %BLUE%🎯 Demo Credentials:%NC%
echo   👨‍💼 Merchant:          demo-merchant@aegis-x.com / DemoPass123!
echo   👮‍♂️ Compliance Officer: compliance@aegis-x.com / CompliancePass123!
echo.

:: Option to view logs
set /p view_logs="📊 View live logs? (y/N): "
if /i "%view_logs%"=="y" (
    echo %BLUE%Showing live logs (Ctrl+C to exit)...%NC%
    docker compose logs -f
) else (
    echo %GREEN%🎉 Aegis-X is running successfully!%NC%
    echo %YELLOW%   Press any key to exit launcher...%NC%
    pause >nul
)

exit /b 0