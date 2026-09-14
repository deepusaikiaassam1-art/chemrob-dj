@echo off
REM ===================================================================
REM  ChemRob DJ - command-line entry point.
REM  Usage:  chemrob.bat predict --smiles "CC(=O)Oc1ccccc1C(=O)O"
REM          chemrob.bat screen library.csv
REM  Runs inside the project's own .venv, so no activation is needed.
REM ===================================================================
setlocal
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
    echo   ChemRob DJ is not installed yet. Run install.bat first.
    exit /b 1
)

".venv\Scripts\python.exe" -m chemrob.cli %*
