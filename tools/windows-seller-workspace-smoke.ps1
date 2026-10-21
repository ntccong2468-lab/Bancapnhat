param([Parameter(Mandatory=$true)][string] $Version)
$ErrorActionPreference='Stop'
if($env:GITHUB_ACTIONS -cne 'true' -or $env:RUNNER_OS -cne 'Windows' -or $env:RUNNER_ENVIRONMENT -cne 'github-hosted'){throw 'Use an isolated GitHub-hosted Windows runner for native workspace smoke.'}
$root=Join-Path $env:RUNNER_TEMP ('vncode-workspace-'+[guid]::NewGuid())
$image=Join-Path $root 'image'
New-Item -ItemType Directory -Path $root | Out-Null
& jpackage --type app-image --name 'VN code' --input target\jpackage-input --main-jar "VNcode-$Version.jar" --main-class com.vncode.app.Launcher --dest $image --app-version $Version --java-options '--enable-native-access=ALL-UNNAMED' --jlink-options '--strip-native-commands --strip-debug --no-man-pages --no-header-files --bind-services'
if($LASTEXITCODE -ne 0){throw 'Cannot package workspace smoke image.'}
$results=@()
foreach($shops in @(4,10)){
    foreach($scale in @('1.0','1.25','1.5')){
        $output=Join-Path $root "reports-$shops-$scale"
        & (Join-Path $PSScriptRoot 'windows-seller-workspace-benchmark.ps1') -AppImage (Join-Path $image 'VN code') -FixtureRoot (Join-Path $root "fixture-$shops") -OutputDirectory $output -BuildId $env:GITHUB_SHA -OzonShops $shops -UiScale $scale -Cycles 20
        $results+=Get-Content (Join-Path $output 'benchmark.json') -Raw | ConvertFrom-Json
    }
}
@{appName='VN code';version=$Version;result='passed';sourceRevision=$env:GITHUB_SHA;liveMarketplaceMutations=$false;targetWindowsLowEndMeasured=$false;datasets=@('6wb4ozon','10ozon');scales=@('1.0','1.25','1.5');runs=$results} | ConvertTo-Json -Depth 30 | Set-Content out\seller-workspace-smoke.json -Encoding utf8
Compress-Archive -Path (Join-Path $root 'reports-*') -DestinationPath out\seller-workspace-evidence.zip
