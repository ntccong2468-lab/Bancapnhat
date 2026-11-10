[CmdletBinding()]
param([string]$InstallRoot)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'WindowsMCP.Common.ps1')
Assert-WindowsWorkerPlatform
$root = Get-WindowsWorkerRoot $InstallRoot
Assert-WindowsWorkerInstalled $root
# Deliberate opt-in clipboard export. Never write the bearer to stdout or a transcript.
Set-Clipboard -Value (Get-WindowsWorkerToken $root)
Write-Host 'Connection token copied to your clipboard. Paste it into the Codex environment Secret, not chat. Clear the clipboard after use.'
