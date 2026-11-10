[CmdletBinding()]
param([string]$InstallRoot, [string]$ReportPath, [switch]$RequireDesktopImage)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'WindowsMCP.Common.ps1')
Assert-WindowsWorkerPlatform
$root = Get-WindowsWorkerRoot $InstallRoot
Assert-WindowsWorkerInstalled $root
if ([string]::IsNullOrWhiteSpace($ReportPath)) { $ReportPath = Join-Path $root 'validation.json' }
$probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 18765)
try { $probe.Start() } catch { throw 'WINDOWS_MCP_TEST_PORT_IN_USE: stop the existing worker before running this test.' } finally { $probe.Stop() }
$before = Set-WindowsWorkerEnvironment $root
$process = $null
try {
    $logs = Join-Path $root 'logs'
    [void][IO.Directory]::CreateDirectory($logs)
    # Only controlled paths and static flags enter the command line. The token is inherited in the process environment.
    $quotedArguments = @((Get-WindowsWorkerArguments $root) | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }) -join ' '
    $process = Start-Process -FilePath (Join-Path $root 'runtime\venv\Scripts\windows-mcp.exe') -ArgumentList $quotedArguments -PassThru `
        -RedirectStandardOutput (Join-Path $logs 'validation-server.out.log') -RedirectStandardError (Join-Path $logs 'validation-server.err.log')
    $pythonArguments = @((Join-Path $PSScriptRoot 'TestWorkerProtocol.py'), '--report', [IO.Path]::GetFullPath($ReportPath))
    if ($RequireDesktopImage) { $pythonArguments += '--require-desktop-image' }
    & (Join-Path $root 'runtime\venv\Scripts\python.exe') @pythonArguments
    if ($LASTEXITCODE -ne 0) { throw 'WINDOWS_MCP_PROTOCOL_SMOKE_FAILED: see the validation report and local server logs.' }
} finally {
    if ($null -ne $process -and -not $process.HasExited) {
        & (Join-Path $env:WINDIR 'System32\taskkill.exe') /PID $process.Id /T /F | Out-Null
    }
    Restore-WindowsWorkerEnvironment $before
}
