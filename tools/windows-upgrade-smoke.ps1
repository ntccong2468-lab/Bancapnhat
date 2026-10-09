param([Parameter(Mandatory=$true)][string] $Version,[switch] $FromPreview)
$ErrorActionPreference='Stop'
if ($env:GITHUB_ACTIONS -cne 'true' -or $env:RUNNER_OS -cne 'Windows' -or $env:RUNNER_ENVIRONMENT -cne 'github-hosted') {
    throw 'Upgrade installation probe requires a disposable GitHub-hosted Windows runner.'
}
. (Join-Path $PSScriptRoot 'windows-smoke-common.ps1')
$prior=Get-Content out\side-by-side-smoke.json -Raw | ConvertFrom-Json
if ($prior.result -cne 'passed' -or $prior.version -cne $Version -or -not $prior.freshVncodeData) { throw 'Require the immediately preceding clean coexistence probe.' }
$root=Join-Path $env:RUNNER_TEMP ('vncode-upgrade-'+[guid]::NewGuid())
New-Item -ItemType Directory -Path $root | Out-Null
$data=Join-Path $env:LOCALAPPDATA 'VNcodeData'
$program=Join-Path $env:LOCALAPPDATA 'VNcodeApp'
if (Test-Path $program) { throw 'VN code must have been uninstalled by the preceding probe.' }
$classpath="$PWD\target\VNcode-$Version.jar;$PWD\target\lib\*"
& javac -cp $classpath -d $root tools\WindowsDataProbe.java tools\WindowsUpgradeDataProbe.java
if ($LASTEXITCODE -ne 0) { throw 'Cannot compile upgrade data probes.' }
$probeClasspath="$root;$classpath"
if($FromPreview){
    $previous=Get-Content out\upgrade-smoke.json -Raw | ConvertFrom-Json
    if($previous.result -cne 'passed' -or $previous.version -cne $Version -or -not $previous.dataPreserved){throw 'Require successful baseline upgrade before preview upgrade.'}
    $fingerprint=& java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsUpgradeDataProbe fingerprint $data
    if($LASTEXITCODE -ne 0 -or $fingerprint -cne $previous.fingerprint){throw 'Unexpected fixture data before preview upgrade.'}
    Move-Item -LiteralPath $data -Destination (Join-Path $root 'preserved-baseline-fixture')
    if(Test-Path $data){throw 'Preview upgrade requires a fresh data directory after preserving the baseline fixture.'}
}else{
    & java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsDataProbe fresh $data
    if ($LASTEXITCODE -ne 0) { throw 'Previous CI data is not the expected empty fixture.' }
}
$fromVersion=if($FromPreview){'1.2.0'}else{'1.1.34'}
$oldExe=Join-Path $root "VN-code-$fromVersion.exe";$oldMsi=Join-Path $root "VN-code-$fromVersion.msi"
$baselineUrl=if($FromPreview){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.2.0-preview.1/VN-code-1.2.0-preview.1-Windows-x64.exe'}else{'https://github.com/ntccong2468-lab/Vncode/releases/download/v1.1.34/VN-code-1.1.34-Windows-x64.exe'}
$baselineHash=if($FromPreview){'2290088fb9e1065430f4d9c5e9b47185c5581a93996b3ad692e8bbc846756ad1'}else{'d3970ee5ca88f72a810bbedb807c84cdb7201b96c0d2ef132d0a7a01cea6ac79'}
Invoke-WebRequest -Uri $baselineUrl -OutFile $oldExe
if ((Get-FileHash $oldExe -Algorithm SHA256).Hash.ToLowerInvariant() -cne $baselineHash) { throw 'Incorrect baseline VN code installer checksum.' }
[VNcode.Smoke.EmbeddedMsi]::Extract($oldExe,$oldMsi)
$newMsi=(Resolve-Path "target\jpackage-temp\msi\VN code-$Version.msi").Path
function Property([string]$Package,[string]$Name){
    $installer=New-Object -ComObject WindowsInstaller.Installer;$database=$installer.OpenDatabase($Package,0)
    $view=$database.OpenView("SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = '$Name'")
    try{[void]$view.Execute();$row=$view.Fetch();if(-not $row){throw 'Missing MSI property'};return $row.StringData(1)}finally{[void]$view.Close()}
}
$upgrade='8CBBA0E2-6E73-4F56-9101-6BC0948D3C72'
foreach($package in @($oldMsi,$newMsi)){
    if((Property $package 'UpgradeCode').Trim('{}').ToUpperInvariant() -cne $upgrade -or (Property $package 'ProductName') -cne 'VN code'){throw 'Installer identity changed.'}
}
if((Property $oldMsi 'ProductVersion') -cne $fromVersion -or (Property $newMsi 'ProductVersion') -cne $Version){throw 'Incorrect MSI versions.'}
function Msi([string]$Action,[string]$Package){$p=Start-Process msiexec.exe -ArgumentList @($Action,"`"$Package`"",'/qn','/norestart') -Wait -PassThru;if($p.ExitCode -notin @(0,3010)){throw "MSI failed: $($p.ExitCode)"}}
function Registrations(){return @(Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*','HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue | Where-Object DisplayName -CEQ 'VN code')}
$app=$null;$installed=$false;$activeMsi=$oldMsi
try{
    Msi '/i' $oldMsi;$installed=$true
    $launcher=Join-Path $program 'VN code.exe'
    $app=Start-Process $launcher -PassThru
    [void](Wait-VncodeWindow -Process $app -Version $fromVersion)
    Stop-Process -Id $app.Id -Force;$app.WaitForExit();$app=$null
    & java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsDataProbe fresh $data
    if($LASTEXITCODE -ne 0){throw 'The baseline launcher did not create the expected empty upgrade fixture.'}
    $baselineClasspath="$root;$program\app\*;$program\app\lib\*"
    & java --enable-native-access=ALL-UNNAMED -cp $baselineClasspath WindowsUpgradeDataProbe seed $data
    if($LASTEXITCODE -ne 0){throw 'Cannot seed upgrade fixture.'}
    $before=& java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsUpgradeDataProbe fingerprint $data
    if($LASTEXITCODE -ne 0 -or $before -notmatch '^[0-9a-f]{64}$'){throw 'Invalid pre-upgrade fingerprint.'}
    Msi '/i' $newMsi;$activeMsi=$newMsi
    $registrations=Registrations
    if($registrations.Count -ne 1 -or $registrations[0].DisplayVersion -cne $Version){throw 'Upgrade created a second VN code registration or retained the old version.'}
    $app=Start-Process $launcher -PassThru
    $window=Wait-VncodeWindow -Process $app -Version $Version
    Stop-Process -Id $app.Id -Force;$app.WaitForExit();$app=$null
    $after=& java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsUpgradeDataProbe fingerprint $data
    if($LASTEXITCODE -ne 0 -or $after -cne $before){throw 'Existing VN code data or saved templates changed during upgrade.'}
    Msi '/x' $newMsi;$installed=$false
    if(@(Registrations).Count -ne 0 -or -not(Test-Path (Join-Path $data 'database.db'))){throw 'Uninstall did not preserve upgrade data.'}
    @{appName='VN code';fromVersion=$fromVersion;version=$Version;result='passed';installerUpgradeUuid=$upgrade;singleRegistration=$true;dataPreserved=$true;windowTitle=$window;liveMarketplaceMutations=$false;fingerprint=$after} | ConvertTo-Json | Set-Content $(if($FromPreview){'out\upgrade-preview-smoke.json'}else{'out\upgrade-smoke.json'}) -Encoding utf8
}finally{if($app -and -not $app.HasExited){Stop-Process -Id $app.Id -Force};if($installed){Msi '/x' $activeMsi}}
