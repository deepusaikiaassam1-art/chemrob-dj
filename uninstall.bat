@echo off
REM ===================================================================
REM  ChemRob DJ - remove the virtual environment.
REM  Your data\, artifacts\ and code are left untouched.
REM ===================================================================
setlocal
cd /d "%~dp0"

if not exist ".venv" (
    echo   Nothing to remove - .venv does not exist.
    pause
    exit /b 0
)

echo   This deletes .venv only. data\ and artifacts\ are kept.
choice /c YN /m "   Remove the virtual environment"
if errorlevel 2 exit /b 0

rmdir /s /q .venv
echo   Removed. Re-install any time with install.bat
pause
