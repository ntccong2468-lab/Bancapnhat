@echo off
setlocal EnableDelayedExpansion
chcp 65001 >nul

set "APP_DIR=%~dp0"
set "LOG_FILE=%APP_DIR%portable-diagnostic.txt"

(
echo ===== WCode JavaFX Portable Diagnostic =====
echo Date: %date% %time%
echo APP_DIR: %APP_DIR%
echo.
echo [1] Required package structure
if exist "%APP_DIR%WCode.exe" (echo OK: WCode.exe found) else (echo ERROR: WCode.exe missing)
if exist "%APP_DIR%app" (echo OK: app folder found) else (echo ERROR: app folder missing)
if exist "%APP_DIR%runtime\bin\java.dll" (echo OK: bundled runtime found) else (echo ERROR: bundled runtime missing)
echo.
echo [2] JavaFX launcher configuration
if exist "%APP_DIR%app\WCode.cfg" (type "%APP_DIR%app\WCode.cfg") else (echo ERROR: app\WCode.cfg missing)
echo.
echo [3] Windows and app-data locations
ver
echo USERNAME=%USERNAME%
echo LOCALAPPDATA=%LOCALAPPDATA%
echo APPDATA=%APPDATA%
echo.
echo [4] Start WCode and capture output
pushd "%APP_DIR%"
start "" /wait "%APP_DIR%WCode.exe" 1^>"%APP_DIR%portable-stdout.txt" 2^>"%APP_DIR%portable-stderr.txt"
echo Exit code: !ERRORLEVEL!
popd
echo.
echo [5] Startup log
if exist "%LOCALAPPDATA%\WCode\logs\startup.log" (type "%LOCALAPPDATA%\WCode\logs\startup.log") else (echo No startup.log was created.)
) > "%LOG_FILE%"

type "%LOG_FILE%"
echo Saved diagnostic report to %LOG_FILE%
endlocal
