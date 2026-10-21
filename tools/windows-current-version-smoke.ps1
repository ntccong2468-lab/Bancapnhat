param([Parameter(Mandatory = $true)][string] $Version)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'windows-smoke-common.ps1')
if ($Version -cne '1.5.10') { throw 'This owner-requested release is limited to 1.5.10.' }
$probeRoot = Join-Path $env:RUNNER_TEMP ('vncode-current-' + [guid]::NewGuid())
$data = Join-Path $probeRoot 'data'
$imageRoot = Join-Path $probeRoot 'image'
New-Item -ItemType Directory -Force -Path $data, $imageRoot | Out-Null
$classpath = "$PWD\target\VNcode-$Version.jar;$PWD\target\lib\*"
& javac -cp $classpath -d $probeRoot tools\WindowsDataProbe.java
if ($LASTEXITCODE -ne 0) { throw 'Could not compile current-version data probe.' }
& jpackage --type app-image --name 'VN code' --input target\jpackage-input `
    --main-jar "VNcode-$Version.jar" --main-class com.vncode.app.Launcher `
    --dest $imageRoot --app-version $Version --vendor 'VN code' `
    --java-options '--enable-native-access=ALL-UNNAMED' `
    --java-options "-Dvncode.appdata.dir=$data" `
    --jlink-options '--strip-native-commands --strip-debug --no-man-pages --no-header-files --bind-services'
if ($LASTEXITCODE -ne 0) { throw 'Native current-version packaging failed.' }
$launcher = Join-Path $imageRoot 'VN code\VN code.exe'
$process = Start-Process -FilePath $launcher -PassThru
try {
    $title = Wait-VncodeWindow -Process $process -Version $Version
    & java --enable-native-access=ALL-UNNAMED -cp "$probeRoot;$classpath" WindowsDataProbe fresh $data 6
    if ($LASTEXITCODE -ne 0) { throw 'Current-version fresh database verification failed.' }
    $startupLog = Join-Path $data 'logs\startup.log'
    if ((Test-Path $startupLog) -and (Get-Item $startupLog).Length -gt 0) {
        throw 'Current-version application recorded a startup exception.'
    }
    @{
        version = $Version; platform = 'windows-x64'; nativeLauncher = 'passed'
        windowTitle = $title; freshDatabase = 'passed'; databaseIntegrity = 'ok'
        previousVersionInstalled = $false; legacyUpgradeMatrixRun = $false
        liveCatalogWriteVerified = $false; liveMarketplaceWriteVerified = $false
    } | ConvertTo-Json | Set-Content out/native-smoke.json -Encoding utf8NoBOM
} finally {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
}
