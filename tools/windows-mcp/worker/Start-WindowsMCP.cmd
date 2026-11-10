@echo off
setlocal
set "VNCODE_MCP_PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if exist "%SystemRoot%\Sysnative\WindowsPowerShell\v1.0\powershell.exe" set "VNCODE_MCP_PS=%SystemRoot%\Sysnative\WindowsPowerShell\v1.0\powershell.exe"
"%VNCODE_MCP_PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start-WindowsMCP.ps1"
set "VNCODE_MCP_EXIT=%errorlevel%"
if not "%VNCODE_MCP_EXIT%"=="0" echo Worker stopped with an error. Read the error above.
pause
exit /b %VNCODE_MCP_EXIT%
