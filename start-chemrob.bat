@echo off
REM ===================================================================
REM  ChemRob DJ - start the web app and open it in the browser.
REM  Double-click this file. Close the window (or Ctrl+C) to stop.
REM ===================================================================
setlocal
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
    echo   ChemRob DJ is not installed yet.
    echo   Run install.bat first.
    echo.
    pause
    exit /b 1
)

set "PORT=8000"
if not "%~1"=="" set "PORT=%~1"

echo.
echo   Starting ChemRob DJ on http://127.0.0.1:%PORT%/app
echo   Close this window to stop the server.
echo.

REM Give uvicorn a moment to bind before the browser asks for the page.
start "" /b cmd /c "timeout /t 4 /nobreak >nul & start http://127.0.0.1:%PORT%/app"

".venv\Scripts\python.exe" -m chemrob.cli serve --port %PORT%
