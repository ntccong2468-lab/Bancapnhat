[CmdletBinding()]
param([string]$InstallRoot)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'WindowsMCP.Common.ps1')
Assert-WindowsWorkerPlatform
$root = Get-WindowsWorkerRoot $InstallRoot
Assert-WindowsWorkerInstalled $root
$before = Set-WindowsWorkerEnvironment $root
try {
    Write-Host 'Starting Windows-MCP on http://127.0.0.1:18765/mcp with authentication and desktop control enabled.'
    Write-Host 'Keep this window open. Ctrl+C stops the server. This command does not publish a network tunnel.'
    $arguments = Get-WindowsWorkerArguments $root
    & (Join-Path $root 'runtime\venv\Scripts\windows-mcp.exe') @arguments
    if ($LASTEXITCODE -ne 0) { throw 'WINDOWS_MCP_SERVER_FAILED: inspect the local error above.' }
} finally { Restore-WindowsWorkerEnvironment $before }
