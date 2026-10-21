param([Parameter(Mandatory=$true)][string]$Version)
$ErrorActionPreference='Stop'
$root=Join-Path $env:RUNNER_TEMP ('vncode-ui-layout-'+[guid]::NewGuid())
$classes=Join-Path $root 'classes'
$evidence=Join-Path $root 'evidence'
New-Item -ItemType Directory -Path $classes,$evidence -Force | Out-Null
$classpath="$PWD\target\test-classes;$PWD\target\VNcode-$Version.jar;$PWD\target\lib\*"
try {
    & javac -cp $classpath -d $classes tools\UiLayoutProbe.java tools\SellerWorkspaceProbe.java
    if($LASTEXITCODE -ne 0){throw 'Cannot compile UI layout probe.'}
    & java --enable-native-access=ALL-UNNAMED -cp "$classes;$classpath" SellerWorkspaceProbe seed (Join-Path $root 'data') 4
    if($LASTEXITCODE -ne 0){throw 'Cannot create synthetic UI fixture.'}
    & java --enable-native-access=ALL-UNNAMED -cp "$classes;$classpath" UiLayoutProbe (Join-Path $root 'data') $evidence
    if($LASTEXITCODE -ne 0){throw 'UI layout overflow or failed screen capture.'}
    $report=Get-Content -Raw (Join-Path $evidence 'ui-layout.json')|ConvertFrom-Json
    if($report.version -cne $Version -or $report.liveSellerMutations -or $report.layouts.Count -ne 288){throw 'Incomplete UI layout coverage.'}
    Copy-Item (Join-Path $evidence 'ui-layout.json') out\ui-layout.json
    # Include screenshots and geometry only, never the synthetic license key directory.
    Compress-Archive -Path (Join-Path $evidence '*.png'),(Join-Path $evidence 'ui-layout.json') -DestinationPath out\ui-layout-evidence.zip -Force
} finally {Remove-Item -Recurse -Force -LiteralPath $root}
