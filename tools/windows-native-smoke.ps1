param([Parameter(Mandatory = $true)][string] $Version)
$ErrorActionPreference = 'Stop'
$probeRoot = Join-Path $env:RUNNER_TEMP ("vncode-native-" + [guid]::NewGuid())
$data = Join-Path $probeRoot 'data'
$imageRoot = Join-Path $probeRoot 'image'
New-Item -ItemType Directory -Force -Path $data, $imageRoot | Out-Null
$classpath = "$PWD\target\FBSBarcode-$Version.jar;$PWD\target\lib\*"
& javac -cp $classpath -d $probeRoot tools\WindowsDataProbe.java
if ($LASTEXITCODE -ne 0) { throw 'Cannot compile the native data probe.' }
$probeClasspath = "$probeRoot;$classpath"
& java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsDataProbe seed $data
if ($LASTEXITCODE -ne 0) { throw 'Cannot seed isolated schema-3 registration history.' }

# Use the same JAR/dependency input and runtime options as the EXE installer.
# The app-data override belongs only to this disposable smoke image.
& jpackage --type app-image --name WCode --input target\jpackage-input `
    --main-jar "FBSBarcode-$Version.jar" --main-class com.tuandev.fbsbarcode.Launcher `
    --dest $imageRoot --app-version $Version --vendor TuanDev `
    --java-options '--enable-native-access=ALL-UNNAMED' `
    --java-options "-Dwcode.appdata.dir=$data" `
    --jlink-options '--strip-native-commands --strip-debug --no-man-pages --no-header-files --bind-services'
if ($LASTEXITCODE -ne 0) { throw 'Native Windows app-image packaging failed.' }
$launcher = Join-Path $imageRoot 'WCode\WCode.exe'
$config = Get-Content (Join-Path $imageRoot 'WCode\app\WCode.cfg') -Raw
if (-not $config.Contains('com.tuandev.fbsbarcode.Launcher')) { throw 'Incorrect packaged main class.' }
$process = Start-Process -FilePath $launcher -PassThru
try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $process.Refresh()
        if ($process.HasExited) { throw 'The native Windows launcher exited during startup.' }
        & java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsDataProbe ready $data 2>$null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Native Windows schema migration did not finish.' }
    Start-Sleep -Seconds 3
    $process.Refresh()
    if ($process.HasExited) { throw 'The native Windows application exited after migration.' }
    & java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsDataProbe verify $data
    if ($LASTEXITCODE -ne 0) { throw 'Native startup did not preserve registration history.' }
    $snapshots = @(Get-ChildItem (Join-Path $data 'snapshots') -Recurse -Filter database.db -File)
    if ($snapshots.Count -ne 1) { throw 'Expected one verified migration snapshot.' }
    & java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsDataProbe snapshot $snapshots[0].FullName
    if ($LASTEXITCODE -ne 0) { throw 'The migration snapshot did not preserve schema-3 history.' }
    $startupLog = Join-Path $data 'logs\startup.log'
    if ((Test-Path $startupLog) -and (Get-Item $startupLog).Length -gt 0) {
        Get-Content $startupLog
        throw 'The packaged app recorded a startup exception.'
    }
    @{
        version = $Version
        platform = 'windows-x64'
        nativeLauncher = 'passed'
        schemaMigration = '3 to 4'
        historyPreserved = $true
        rollbackSnapshotVerified = $true
        liveMarketplaceMutations = $false
    } | ConvertTo-Json | Set-Content out\native-smoke.json -Encoding utf8
} finally {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
}
