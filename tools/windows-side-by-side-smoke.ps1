param([Parameter(Mandatory = $true)][string] $Version, [string] $MsiPath, [switch] $InspectOnly)
$ErrorActionPreference = 'Stop'
# Installation/uninstallation is allowed only on a fresh disposable GitHub-hosted runner.
if ($env:GITHUB_ACTIONS -cne 'true' -or $env:RUNNER_OS -cne 'Windows' -or
    $env:RUNNER_ENVIRONMENT -cne 'github-hosted') {
    throw 'This installation probe requires a disposable GitHub-hosted Windows runner.'
}
. (Join-Path $PSScriptRoot 'windows-smoke-common.ps1')
$oldProgram = Join-Path $env:ProgramFiles 'WCodeApp'
$oldDirectory = Join-Path $env:LOCALAPPDATA 'WCode'
$oldData = Join-Path $env:LOCALAPPDATA 'WCodeData'
$newData = Join-Path $env:LOCALAPPDATA 'VNcodeData'
$newProgram = Join-Path $env:LOCALAPPDATA 'VNcodeApp'
$oldSystem = Join-Path $env:ProgramData 'WCode'
foreach ($directory in @($oldProgram, $oldDirectory, $oldData, $newData, $newProgram, $oldSystem)) {
    if (Test-Path $directory) { throw "Probe refuses to touch a pre-existing application directory: $directory" }
}
$probeRoot = Join-Path $env:RUNNER_TEMP ("vncode-coinstall-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $probeRoot | Out-Null
$oldExe = Join-Path $probeRoot 'WCode-1.1.75.exe'
$oldMsi = Join-Path $probeRoot 'WCode-1.1.75.msi'
Invoke-WebRequest -Uri 'https://github.com/rupphi/relatest-wcode/releases/download/v1.1.75/WCode.exe' -OutFile $oldExe
if ((Get-FileHash $oldExe -Algorithm SHA256).Hash.ToLowerInvariant() -cne
    '509e29e167b4731e8a387e4f9309cfb3779405ba84bd75c16ba9fd1ffab42cca') {
    throw 'Unexpected checksum for the real WCode 1.1.75 installer.'
}
[VNcode.Smoke.EmbeddedMsi]::Extract($oldExe, $oldMsi)
if ((Get-FileHash $oldMsi -Algorithm SHA256).Hash.ToLowerInvariant() -cne
    '5f109cb64afb6be8947a46238708c6e28e67dacdeb265ca5cb3189b30bb39a52') {
    throw 'Unexpected checksum for the original embedded WCode 1.1.75 MSI.'
}

function Read-MsiProperty([string] $Package, [string] $Property) {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $installer.OpenDatabase($Package, 0)
    $view = $database.OpenView("SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = '$Property'")
    try { $view.Execute(); $row = $view.Fetch(); if (-not $row) { throw "Missing MSI property: $Property" }; return $row.StringData(1) }
    finally { $view.Close() }
}
$originalName = Read-MsiProperty $oldMsi 'ProductName'
$originalVersion = Read-MsiProperty $oldMsi 'ProductVersion'
Write-Host (ConvertTo-Json @{ originalName=$originalName; originalVersion=$originalVersion;
    nameType=$originalName.GetType().FullName; versionType=$originalVersion.GetType().FullName })
if ($InspectOnly) { return } # Read-only diagnostic: never installs or produces release proof.
if ($originalName -cne 'WCode' -or $originalVersion -cne '1.1.75') { throw 'Incorrect original WCode MSI identity.' }
if (-not $MsiPath) {
    # jpackage embeds this delivered MSI; wixobj can contain another intermediate MSI.
    $MsiPath = Join-Path $PWD "target\jpackage-temp\msi\VN code-$Version.msi"
    if (-not (Test-Path -LiteralPath $MsiPath -PathType Leaf)) { throw 'Missing the MSI embedded in the newly built EXE.' }
}
$MsiPath = (Resolve-Path -LiteralPath $MsiPath).Path
$upgrade = (Read-MsiProperty $MsiPath 'UpgradeCode').Trim('{}').ToUpperInvariant()
if ($upgrade -cne '8CBBA0E2-6E73-4F56-9101-6BC0948D3C72' -or
    $upgrade -eq (Read-MsiProperty $oldMsi 'UpgradeCode').Trim('{}').ToUpperInvariant() -or
    $upgrade -eq '0356BE08-487C-4E04-A2C2-353AF93DB2DE') { throw 'VN code must have its own installer upgrade identity.' }
if ((Read-MsiProperty $MsiPath 'ProductName') -cne 'VN code' -or
    (Read-MsiProperty $MsiPath 'ProductVersion') -cne $Version) { throw 'Incorrect VN code MSI identity.' }

function Invoke-Msi([string] $Operation, [string] $Package) {
    $arguments = @($Operation, "`"$Package`"", '/qn', '/norestart')
    $process = Start-Process msiexec.exe -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -notin @(0,3010)) { throw "MSI $Operation failed: $($process.ExitCode)" }
}
function Registrations([string] $Name) {
    $roots = @('HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*')
    return @(Get-ItemProperty $roots -ErrorAction SilentlyContinue | Where-Object DisplayName -CEQ $Name)
}
Invoke-Msi '/i' $oldMsi
$oldExecutable = Join-Path $oldProgram 'WCode.exe'
if (-not (Test-Path $oldExecutable -PathType Leaf)) { throw 'The real WCode MSI did not install its native launcher.' }
$classpath = "$PWD\target\VNcode-$Version.jar;$PWD\target\lib\*"
& javac -cp $classpath -d $probeRoot tools\WindowsDataProbe.java
if ($LASTEXITCODE -ne 0) { throw 'Cannot compile the coexistence data probe.' }
$probeClasspath = "$probeRoot;$classpath"
$protected = @($oldExecutable)
foreach ($directory in @($oldDirectory, $oldData)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    & java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsDataProbe seed $directory
    if ($LASTEXITCODE -ne 0) { throw 'Cannot seed isolated WCode fixture data.' }
    'wcode-license-sentinel' | Set-Content (Join-Path $directory 'license.json') -Encoding ascii
    'wcode-user-data-sentinel' | Set-Content (Join-Path $directory 'coinstall-data-sentinel.txt') -Encoding ascii
    $protected += @((Join-Path $directory 'database.db'), (Join-Path $directory 'license.json'),
        (Join-Path $directory 'coinstall-data-sentinel.txt'))
}
$backup = Join-Path $oldSystem 'update-backup'
New-Item -ItemType Directory -Force -Path $backup | Out-Null
Copy-Item (Join-Path $oldData 'database.db') (Join-Path $backup 'database.db')
'old-update-backup' | Set-Content (Join-Path $backup 'restore-required.marker') -Encoding ascii
$protected += @((Join-Path $backup 'database.db'), (Join-Path $backup 'restore-required.marker'))
$before = @{}
foreach ($path in $protected) { $before[$path] = (Get-FileHash $path -Algorithm SHA256).Hash }
function Verify-WcodeUnchanged {
    foreach ($path in $protected) {
        if (-not (Test-Path $path -PathType Leaf) -or
            (Get-FileHash $path -Algorithm SHA256).Hash -cne $before[$path]) { throw "WCode fixture changed: $path" }
    }
    $registrations = @(Registrations 'WCode')
    if ($registrations.Count -ne 1 -or $registrations[0].DisplayVersion -cne '1.1.75') { throw 'WCode registration was replaced or removed.' }
}
$installed = $false
$app = $null
try {
    Invoke-Msi '/i' $MsiPath
    $installed = $true
    Verify-WcodeUnchanged
    $registrations = @(Registrations 'VN code')
    if ($registrations.Count -ne 1 -or $registrations[0].DisplayVersion -cne $Version) { throw 'Expected independent VN code registration.' }
    $launcher = Join-Path $newProgram 'VN code.exe'
    if (-not (Test-Path $launcher)) { throw 'VN code was not installed in its own program directory.' }
    $configuration = Get-Content (Join-Path $newProgram 'app\VN code.cfg') -Raw
    if ($configuration.Contains('-Dvncode.appdata.dir=')) { throw 'Production installer contains a test data override.' }
    $app = Start-Process $launcher -PassThru
    $windowTitle = Wait-VncodeWindow -Process $app -Version $Version
    & java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsDataProbe fresh $newData
    if ($LASTEXITCODE -ne 0) { throw 'Installed VN code did not start with its own empty data.' }
    if (Test-Path (Join-Path $newData 'coinstall-data-sentinel.txt')) { throw 'WCode user files were imported.' }
    Verify-WcodeUnchanged
    Stop-Process -Id $app.Id -Force
    $app.WaitForExit()
    $app = $null
    Invoke-Msi '/x' $MsiPath
    $installed = $false
    Verify-WcodeUnchanged
    if (@(Registrations 'VN code').Count -ne 0) { throw 'VN code was not uninstalled.' }
    if (-not (Test-Path (Join-Path $newData 'database.db'))) { throw 'Uninstall deleted VN code user data.' }
    New-Item -ItemType Directory -Force -Path out | Out-Null
    @{
        appName = 'VN code'; version = $Version; platform = 'windows-x64'; result = 'passed'
        wcodeVersion = '1.1.75'; installerUpgradeUuid = $upgrade; windowTitle = $windowTitle
        independentRegistrations = $true; freshVncodeData = $true; wcodeFilesUnchanged = $true
        uninstallPreservesWcode = $true; liveMarketplaceMutations = $false
    } | ConvertTo-Json | Set-Content out\side-by-side-smoke.json -Encoding utf8
} finally {
    if ($app -and -not $app.HasExited) { Stop-Process -Id $app.Id -Force }
    if ($installed) { Invoke-Msi '/x' $MsiPath }
}
