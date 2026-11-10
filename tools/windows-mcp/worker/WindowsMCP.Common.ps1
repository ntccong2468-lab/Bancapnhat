Set-StrictMode -Version Latest

$script:WindowsMcpSourceCommit = '59e3b194799d5a1ced14298bca4cd200f7edbfd8'
$script:WindowsMcpSourceSha256 = '5fc20d7ad7f2207ee3ed46fbad4c6636cb5383f0fd486c39fadabb25b61de0db'
$script:WindowsMcpUvVersion = '0.13.7'
$script:WindowsMcpUvSha256 = '0cdc5b38fe5a8168ed6a25814f5ba43c21d313dd3187314fccb37b7cec46e021'
$script:WindowsMcpTools = 'App,Snapshot,Screenshot,Click,Type,Scroll,Move,Shortcut,Wait,WaitFor'

function Assert-WindowsWorkerPlatform {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw 'WINDOWS_MCP_WINDOWS_REQUIRED: this worker requires Windows 10/11 x64 and a signed-in desktop.'
    }
    if (-not [Environment]::Is64BitOperatingSystem -or -not [Environment]::Is64BitProcess -or
        $env:PROCESSOR_ARCHITECTURE -ne 'AMD64') {
        throw 'WINDOWS_MCP_X64_REQUIRED: use 64-bit Windows PowerShell on Windows x64.'
    }
    if ([Environment]::OSVersion.Version.Major -lt 10) {
        throw 'WINDOWS_MCP_WINDOWS_10_REQUIRED'
    }
    Add-Type -AssemblyName System.Security
}

function Get-WindowsWorkerRoot([string]$Root) {
    if ([string]::IsNullOrWhiteSpace($Root)) {
        if ([IO.File]::Exists((Join-Path $PSScriptRoot 'managed-install.json'))) {
            $Root = $PSScriptRoot
        } else { $Root = Join-Path $env:LOCALAPPDATA 'VNCode\WindowsMCP' }
    }
    $full = [IO.Path]::GetFullPath($Root).TrimEnd('\')
    if ($full -eq [IO.Path]::GetPathRoot($full).TrimEnd('\') -or $full -eq $env:WINDIR) {
        throw 'WINDOWS_MCP_INSTALL_ROOT_INVALID'
    }
    return $full
}

function Protect-WindowsWorkerDirectory([string]$Path) {
    [void][IO.Directory]::CreateDirectory($Path)
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = [System.Security.AccessControl.DirectorySecurity]::new()
    $acl.SetOwner($sid)
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($identity in @($sid, [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
        $rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
            $identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        [void]$acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Write-WindowsWorkerJson([string]$Path, $Value) {
    $json = $Value | ConvertTo-Json -Depth 12
    [IO.File]::WriteAllText($Path, $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
}

function Read-WindowsWorkerJson([string]$Path) {
    return (Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Get-WindowsWorkerToken([string]$Root) {
    $path = Join-Path $Root 'private\token.dpapi'
    if (-not [IO.File]::Exists($path)) { throw 'WINDOWS_MCP_TOKEN_MISSING: run Install-WindowsMCP.ps1 first.' }
    $clear = [Security.Cryptography.ProtectedData]::Unprotect(
        [IO.File]::ReadAllBytes($path), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    try {
        $token = [Text.Encoding]::ASCII.GetString($clear)
        if ($token.Length -ne 44) { throw 'WINDOWS_MCP_TOKEN_INVALID' }
        return $token
    } finally { [Array]::Clear($clear, 0, $clear.Length) }
}

function Initialize-WindowsWorkerToken([string]$Root) {
    $private = Join-Path $Root 'private'
    Protect-WindowsWorkerDirectory $private
    $path = Join-Path $private 'token.dpapi'
    if ([IO.File]::Exists($path)) {
        [void](Get-WindowsWorkerToken $Root)
        return
    }
    $random = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($random) } finally { $rng.Dispose() }
    $clear = [Text.Encoding]::ASCII.GetBytes([Convert]::ToBase64String($random))
    try {
        $protected = [Security.Cryptography.ProtectedData]::Protect(
            $clear, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        [IO.File]::WriteAllBytes($path, $protected)
    } finally {
        [Array]::Clear($random, 0, $random.Length)
        [Array]::Clear($clear, 0, $clear.Length)
    }
}

function Get-WindowsWorkerHash([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-WindowsWorkerSource([string]$Root) {
    $manifest = Read-WindowsWorkerJson (Join-Path $Root 'source-manifest.json')
    if ($manifest.commit -ne $script:WindowsMcpSourceCommit) { throw 'WINDOWS_MCP_SOURCE_PIN_MISMATCH' }
    foreach ($file in $manifest.files) {
        $path = Join-Path (Join-Path $Root 'source') $file.path
        if (-not [IO.File]::Exists($path) -or (Get-WindowsWorkerHash $path) -ne $file.sha256) {
            throw ('WINDOWS_MCP_SOURCE_CHANGED: ' + $file.path)
        }
    }
}

function Assert-WindowsWorkerInstalled([string]$Root) {
    $installed = Read-WindowsWorkerJson (Join-Path $Root 'installed.json')
    if ($installed.sourceCommit -ne $script:WindowsMcpSourceCommit -or
        $installed.sourceArchiveSha256 -ne $script:WindowsMcpSourceSha256 -or
        $installed.uvVersion -ne $script:WindowsMcpUvVersion) {
        throw 'WINDOWS_MCP_INSTALL_PIN_MISMATCH'
    }
    Assert-WindowsWorkerSource $Root
    if (-not [IO.File]::Exists((Join-Path $Root 'runtime\venv\Scripts\windows-mcp.exe'))) {
        throw 'WINDOWS_MCP_RUNTIME_MISSING: rerun the installer.'
    }
    [void](Get-WindowsWorkerToken $Root)
}

function Get-WindowsWorkerArguments([string]$Root) {
    return @('serve', '--config', (Join-Path $Root 'worker.toml'), '--transport', 'streamable-http',
        '--host', '127.0.0.1', '--port', '18765', '--tools', $script:WindowsMcpTools,
        '--control', '--control-policy', 'balanced')
}

function Set-WindowsWorkerEnvironment([string]$Root) {
    $before = @{}
    $values = @{
        ANONYMIZED_TELEMETRY = 'false'
        WINDOWS_MCP_AUTH_KEY = (Get-WindowsWorkerToken $Root)
        PYTHONUTF8 = '1'
        WINDOWS_MCP_OAUTH_CLIENT_ID = $null
        WINDOWS_MCP_OAUTH_CLIENT_SECRET = $null
        WINDOWS_MCP_SSL_CERTFILE = $null
        WINDOWS_MCP_SSL_KEYFILE = $null
        WINDOWS_MCP_IP_ALLOWLIST = $null
        WINDOWS_MCP_EXCLUDE_TOOLS = $null
        WINDOWS_MCP_CORS_ORIGINS = $null
        WINDOWS_MCP_STATELESS_HTTP = $null
        WINDOWS_MCP_CONTROL_IDLE_TIMEOUT = $null
        WINDOWS_MCP_DEBUG = $null
        WINDOWS_MCP_WATCHDOG = 'false'
    }
    foreach ($key in $values.Keys) {
        $before[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process')
    }
    return $before
}

function Restore-WindowsWorkerEnvironment($Before) {
    foreach ($key in $Before.Keys) { [Environment]::SetEnvironmentVariable($key, $Before[$key], 'Process') }
}
