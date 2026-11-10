[CmdletBinding()]
param([string]$InstallRoot, [string]$VNCodeExecutable, [string]$WindowName = 'VN code')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'WindowsMCP.Common.ps1')
Assert-WindowsWorkerPlatform
$root = Get-WindowsWorkerRoot $InstallRoot
$marker = Join-Path $root 'managed-install.json'
if ([IO.Directory]::Exists($root) -and -not [IO.File]::Exists($marker) -and
    @(Get-ChildItem -LiteralPath $root -Force).Count -gt 0) {
    throw 'WINDOWS_MCP_INSTALL_ROOT_NOT_EMPTY: select an empty, dedicated directory.'
}
Protect-WindowsWorkerDirectory $root
if ([IO.File]::Exists($marker)) {
    $managed = Read-WindowsWorkerJson $marker
    if ($managed.sourceCommit -ne $script:WindowsMcpSourceCommit) { throw 'WINDOWS_MCP_EXISTING_INSTALL_DIFFERENT_PIN' }
} else {
    Write-WindowsWorkerJson $marker @{ sourceCommit = $script:WindowsMcpSourceCommit; managedBy = 'VNCode Windows MCP kit' }
}
$cache = Join-Path $root 'cache'
[void][IO.Directory]::CreateDirectory($cache)

function Get-VerifiedDownload([string]$Url, [string]$Path, [string]$ExpectedSha256) {
    if ([IO.File]::Exists($Path) -and (Get-WindowsWorkerHash $Path) -eq $ExpectedSha256) { return }
    $partial = $Path + '.' + [Guid]::NewGuid().ToString('N') + '.partial'
    try {
        Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $partial -TimeoutSec 120
        if ((Get-WindowsWorkerHash $partial) -ne $ExpectedSha256) { throw 'WINDOWS_MCP_DOWNLOAD_CHECKSUM_MISMATCH' }
        Move-Item -LiteralPath $partial -Destination $Path -Force
    } finally { if ([IO.File]::Exists($partial)) { Remove-Item -LiteralPath $partial -Force } }
}

$previousTls = [Net.ServicePointManager]::SecurityProtocol
$envBefore = @{}
try {
    # Preserve certificate validation and any inherited proxy. TLS1.2 is needed by Windows PowerShell5.1.
    [Net.ServicePointManager]::SecurityProtocol = $previousTls -bor [Net.SecurityProtocolType]::Tls12
    $sourceZip = Join-Path $cache 'windows-mcp-source.zip'
    Get-VerifiedDownload ('https://github.com/CursorTouch/Windows-MCP/archive/' + $script:WindowsMcpSourceCommit + '.zip') $sourceZip $script:WindowsMcpSourceSha256
    $uvZip = Join-Path $cache 'uv-windows-x64.zip'
    Get-VerifiedDownload ('https://github.com/astral-sh/uv/releases/download/' + $script:WindowsMcpUvVersion + '/uv-x86_64-pc-windows-msvc.zip') $uvZip $script:WindowsMcpUvSha256

    $source = Join-Path $root 'source'
    if (-not [IO.Directory]::Exists($source)) {
        $stage = Join-Path $cache ('source-' + [Guid]::NewGuid().ToString('N'))
        Expand-Archive -LiteralPath $sourceZip -DestinationPath $stage
        Move-Item -LiteralPath (Join-Path $stage ('Windows-MCP-' + $script:WindowsMcpSourceCommit)) -Destination $source
        Remove-Item -LiteralPath $stage
        $files = @(Get-ChildItem -LiteralPath $source -File -Recurse | ForEach-Object {
            @{ path = $_.FullName.Substring($source.Length + 1).Replace('\', '/'); sha256 = Get-WindowsWorkerHash $_.FullName }
        })
        Write-WindowsWorkerJson (Join-Path $root 'source-manifest.json') @{ commit = $script:WindowsMcpSourceCommit; files = $files }
    }
    Assert-WindowsWorkerSource $root

    $uvDir = Join-Path $root 'tools\uv'
    [void][IO.Directory]::CreateDirectory($uvDir)
    Expand-Archive -LiteralPath $uvZip -DestinationPath $uvDir -Force
    $uv = Join-Path $uvDir 'uv.exe'
    if (-not [IO.File]::Exists($uv)) { throw 'WINDOWS_MCP_UV_ARCHIVE_LAYOUT_CHANGED' }
    $installEnv = @{
        UV_PYTHON_INSTALL_DIR = (Join-Path $root 'runtime\python')
        UV_PROJECT_ENVIRONMENT = (Join-Path $root 'runtime\venv')
        UV_CACHE_DIR = (Join-Path $cache 'uv')
        ANONYMIZED_TELEMETRY = 'false'
        PYTHONUTF8 = '1'
    }
    foreach ($entry in $installEnv.GetEnumerator()) {
        $envBefore[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
    & $uv sync --directory $source --frozen --no-dev --managed-python --python 3.14
    if ($LASTEXITCODE -ne 0) { throw 'WINDOWS_MCP_DEPENDENCY_INSTALL_FAILED: rerun to repair the managed environment.' }
    Initialize-WindowsWorkerToken $root
    [IO.File]::WriteAllText((Join-Path $root 'worker.toml'), @'
[server]
transport = "streamable-http"
host = "127.0.0.1"
port = 18765
allow_insecure_remote = false
[control]
enabled = true
policy = "balanced"
idle_timeout_seconds = 10.0
consent_countdown_seconds = 10
'@, [System.Text.UTF8Encoding]::new($false))

    $target = Join-Path $root 'target.json'
    if (-not [string]::IsNullOrWhiteSpace($VNCodeExecutable)) {
        $exe = [IO.Path]::GetFullPath($VNCodeExecutable)
        if (-not [IO.File]::Exists($exe) -or [IO.Path]::GetExtension($exe) -ne '.exe') { throw 'WINDOWS_MCP_VNCODE_EXE_NOT_FOUND' }
        Write-WindowsWorkerJson $target @{ executable = $exe; window_name = $WindowName; args = @() }
    } elseif (-not [IO.File]::Exists($target)) {
        Write-WindowsWorkerJson $target @{ executable = $null; window_name = $WindowName; args = @() }
    }
    foreach ($name in @('WindowsMCP.Common.ps1', 'Install-WindowsMCP.ps1', 'Start-WindowsMCP.ps1', 'Export-ConnectionToken.ps1', 'Test-WindowsMCP.ps1', 'TestWorkerProtocol.py', 'Install-WindowsMCP.cmd', 'Start-WindowsMCP.cmd', 'Export-ConnectionToken.cmd')) {
        $from = Join-Path $PSScriptRoot $name
        $to = Join-Path $root $name
        if ([IO.Path]::GetFullPath($from) -ne [IO.Path]::GetFullPath($to)) { Copy-Item -LiteralPath $from -Destination $to -Force }
    }
    Write-WindowsWorkerJson (Join-Path $root 'installed.json') @{
        sourceCommit = $script:WindowsMcpSourceCommit
        sourceArchiveSha256 = $script:WindowsMcpSourceSha256
        uvVersion = $script:WindowsMcpUvVersion
        uvArchiveSha256 = $script:WindowsMcpUvSha256
        installedAtUtc = [DateTime]::UtcNow.ToString('o')
        bind = '127.0.0.1:18765'; telemetry = $false; authentication = 'DPAPI CurrentUser protected bearer'
    }
    Assert-WindowsWorkerInstalled $root
    Write-Host ('Installed pinned Windows-MCP in ' + $root)
    Write-Host 'Next: run Start-WindowsMCP.ps1 in the signed-in Windows desktop. No firewall, service, or public tunnel was created.'
    Write-Host 'The token was saved with Windows DPAPI; it was not printed. Export-ConnectionToken.ps1 copies it only when you explicitly run that helper.'
} finally {
    Restore-WindowsWorkerEnvironment $envBefore
    [Net.ServicePointManager]::SecurityProtocol = $previousTls
}
