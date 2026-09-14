@echo off
REM ===================================================================
REM  ChemRob DJ - Windows installer
REM
REM  Creates a private virtual environment in .venv, installs ChemRob DJ
REM  and its dependencies into it, and verifies the result. Nothing is
REM  written outside this folder, so uninstalling is deleting .venv.
REM
REM  Double-click this file, or run it from a terminal.
REM ===================================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0"

echo.
echo   ChemRob DJ - installer
echo   ======================
echo.

REM ---------- 1. locate a suitable Python ----------
set "PY="
for %%V in (3.13 3.12 3.11 3.10) do (
    if not defined PY (
        py -%%V -c "import sys" >nul 2>&1
        if !errorlevel! equ 0 set "PY=py -%%V"
    )
)
if not defined PY (
    py -3 -c "import sys" >nul 2>&1
    if !errorlevel! equ 0 set "PY=py -3"
)
if not defined PY (
    python -c "import sys" >nul 2>&1
    if !errorlevel! equ 0 set "PY=python"
)
if not defined PY (
    echo   [X] No Python installation found.
    echo.
    echo       Install Python 3.10 or newer from https://www.python.org/downloads/
    echo       and be sure to tick "Add python.exe to PATH" during setup.
    echo.
    pause
    exit /b 1
)

for /f "tokens=2" %%v in ('%PY% -V 2^>^&1') do set "PYVER=%%v"
echo   [1/4] Using Python %PYVER%  ^(%PY%^)

REM ---------- 2. create the virtual environment ----------
if exist ".venv\Scripts\python.exe" (
    echo   [2/4] Reusing the existing .venv
) else (
    echo   [2/4] Creating .venv ...
    %PY% -m venv .venv
    if errorlevel 1 (
        echo   [X] Could not create the virtual environment.
        pause
        exit /b 1
    )
)
set "VPY=%~dp0.venv\Scripts\python.exe"

REM ---------- 3. install ----------
echo   [3/4] Installing ChemRob DJ and its dependencies ...
echo         ^(first run downloads RDKit and friends - a few minutes^)
echo.
"%VPY%" -m pip install --upgrade pip --quiet
"%VPY%" -m pip install -e . --quiet
if errorlevel 1 (
    echo.
    echo   [X] Installation failed. Re-run showing the full output with:
    echo       .venv\Scripts\python.exe -m pip install -e .
    echo.
    pause
    exit /b 1
)

REM ---------- 4. verify ----------
echo   [4/4] Verifying ...
"%VPY%" -c "import chemrob, rdkit; print('         chemrob', chemrob.__version__, '/ rdkit', rdkit.__version__)"
if errorlevel 1 (
    echo   [X] The package imports failed after installation.
    pause
    exit /b 1
)

if exist "artifacts\baseline_activity.joblib" (
    echo         Trained model found - ready to use.
) else (
    echo.
    echo   [!] No trained model in artifacts\
    echo       Build one with:
    echo         .venv\Scripts\python.exe scripts\build_dataset.py --max-records 60000 --refresh
    echo         .venv\Scripts\python.exe scripts\train.py
)

echo.
echo   Done.
echo.
echo   Start the app        :  start-chemrob.bat
echo   Command line         :  chemrob.bat predict --smiles "CC(=O)Oc1ccccc1C(=O)O"
echo   Activate the venv    :  .venv\Scripts\activate
echo.
pause
