@echo off
REM ===================================================================
REM  ChemRob DJ - build a standalone Windows executable.
REM
REM  Everything happens inside this folder: PyInstaller is installed
REM  into .buildenv here, never into your system Python. Delete
REM  .buildenv and build\ afterwards to reclaim the space.
REM
REM  Output:  dist\ChemRobDJ\ChemRobDJ.exe   (with data + artifacts beside it)
REM  Expect 15-30 minutes and roughly 600 MB.
REM ===================================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0"

echo.
echo   ChemRob DJ - executable build
echo   =============================
echo.

if not exist "artifacts\baseline_activity.joblib" (
    echo   [X] No trained model in artifacts\ - nothing to bundle.
    pause
    exit /b 1
)

REM ---------- 1. isolated build environment ----------
if exist ".buildenv\Scripts\python.exe" (
    echo   [1/4] Reusing .buildenv
) else (
    echo   [1/4] Creating .buildenv ...
    py -3 -m venv .buildenv 2>nul || python -m venv .buildenv
    if errorlevel 1 (
        echo   [X] Could not create the build environment.
        pause
        exit /b 1
    )
)
set "BPY=%~dp0.buildenv\Scripts\python.exe"

REM ---------- 2. build-time dependencies ----------
echo   [2/4] Installing build dependencies into .buildenv ...
echo         ^(PyInstaller plus the libraries to be bundled^)
"%BPY%" -m pip install --upgrade pip --quiet
"%BPY%" -m pip install --quiet pyinstaller rdkit numpy pandas scipy scikit-learn joblib pyarrow requests fastapi uvicorn pydantic
if errorlevel 1 (
    echo   [X] Dependency installation failed.
    pause
    exit /b 1
)

REM ---------- 3. build ----------
echo   [3/4] Building - this is the slow part, 15-30 minutes ...
echo.
"%BPY%" -m PyInstaller chemrob.spec --noconfirm --clean
if errorlevel 1 (
    echo.
    echo   [X] Build failed. The log above names the missing piece.
    pause
    exit /b 1
)

REM ---------- 4. stage the model beside the exe ----------
echo   [4/4] Copying the trained model next to the executable ...
xcopy /e /i /q /y "artifacts" "dist\ChemRobDJ\artifacts" >nul
if exist "demo_library.csv" copy /y "demo_library.csv" "dist\ChemRobDJ\" >nul
if exist "README.md" copy /y "README.md" "dist\ChemRobDJ\" >nul

echo.
echo   Done.
echo.
echo   Run it      :  dist\ChemRobDJ\ChemRobDJ.exe
echo   Send it     :  zip the whole dist\ChemRobDJ folder
echo   Reclaim disk:  rmdir /s /q .buildenv build
echo.
echo   The recipient needs no Python. They unzip and double-click the exe.
echo.
pause
