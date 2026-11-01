param(
    [Parameter(Mandatory=$true)][string] $CandidateRoot,
    [Parameter(Mandatory=$true)][string] $ExpectedSource,
    [Parameter(Mandatory=$true)][long] $ExpectedRunId,
    [Parameter(Mandatory=$true)][string] $ProofPath,
    [string] $Version = '1.6.0'
)
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -cne 'true' -or $env:RUNNER_OS -cne 'Windows' -or $env:RUNNER_ENVIRONMENT -cne 'github-hosted') {
    throw 'This installer probe requires a disposable GitHub-hosted Windows runner.'
}
# The candidate has already been downloaded. No token, secret, credential or
# email belongs in the environment inherited by the old or new application.
foreach ($entry in @(Get-ChildItem Env: | Where-Object { $_.Name -match '(?i)(TOKEN|SECRET|PASS(WOR)?D|API[_-]?KEY|CREDENTIAL|EMAIL|GMAIL)' })) {
    [Environment]::SetEnvironmentVariable($entry.Name, $null, 'Process')
}
if ($Version -cne '1.6.0' -or $ExpectedSource -notmatch '^[a-f0-9]{40}$' -or $ExpectedRunId -le 0) {
    throw 'Invalid candidate identity.'
}
$localAppData = [IO.Path]::GetFullPath($env:LOCALAPPDATA).TrimEnd('\')
$profileLocal = [IO.Path]::GetFullPath([Environment]::GetFolderPath('LocalApplicationData')).TrimEnd('\')
if (-not $localAppData.Equals($profileLocal, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The disposable runner must use its actual LocalAppData installation directory.'
}
. (Join-Path $PSScriptRoot 'windows-smoke-common.ps1')
$candidate = (Resolve-Path -LiteralPath $CandidateRoot).Path
$info = Get-Content -LiteralPath (Join-Path $candidate 'build-info.json') -Raw | ConvertFrom-Json
$manifest = Get-Content -LiteralPath (Join-Path $candidate 'asset-manifest.json') -Raw | ConvertFrom-Json -AsHashtable
if ($info.version -cne $Version -or $info.sourceCommit -cne $ExpectedSource -or [long]$info.runId -ne $ExpectedRunId -or
    $manifest.version -cne $Version -or $manifest.sourceCommit -cne $ExpectedSource -or
    -not $info.verification.fullJavaSuiteRun -or -not $info.verification.nativeSmokeRun -or -not $info.verification.baselineUpgradeRun) {
    throw 'Candidate build identity or previous verification does not match.'
}
$sums = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $candidate 'SHA256SUMS')) {
    if ($line -cnotmatch '^([a-f0-9]{64})  ([A-Za-z0-9._-]+)$') { throw 'Invalid candidate checksum record.' }
    $name = $Matches[2]; $wanted = $Matches[1]
    if ($sums.ContainsKey($name)) { throw 'Duplicate candidate checksum record.' }
    $file = Get-Item -LiteralPath (Join-Path $candidate $name)
    if ($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
        (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -cne $wanted) {
        throw 'Candidate checksum does not match.'
    }
    $sums[$name] = $wanted
}
$msiName = "VN-code-$Version-Windows-x64.msi"
$portableName = "VN-code-$Version-Windows-x64.zip"
$exeName = "VN-code-$Version-Windows-x64.exe"
foreach ($name in @($msiName, $portableName, $exeName)) {
    if (-not $sums.ContainsKey($name) -or -not $manifest.files.ContainsKey($name)) { throw 'Candidate required asset missing.' }
    $entry = $manifest.files[$name]; $file = Get-Item -LiteralPath (Join-Path $candidate $name)
    if ($entry.sha256 -cne $sums[$name] -or [long]$entry.bytes -ne $file.Length) { throw 'Candidate manifest mismatch.' }
}
$newMsi = (Resolve-Path -LiteralPath (Join-Path $candidate $msiName)).Path
$newMsiHash = $sums[$msiName]
$newMsiBytes = (Get-Item -LiteralPath $newMsi).Length
$root = Join-Path $env:RUNNER_TEMP ('vncode-updater-msi-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $root | Out-Null
$data = Join-Path $env:LOCALAPPDATA 'VNcodeData'
$program = Join-Path $env:LOCALAPPDATA 'VNcodeApp'
if ((Test-Path -LiteralPath $data) -or (Test-Path -LiteralPath $program)) { throw 'Isolated runner must start without VN code data or installation.' }
$oldExe = Join-Path $root 'VN-code-1.5.19.exe'
$oldMsi = Join-Path $root 'VN-code-1.5.19.msi'
$baselineUrl = 'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.5.19/VN-code-1.5.19-Windows-x64.exe'
$baselineHash = '13ecaa3296ad8a01dc4f541b3f600b677a629871d04d5ba66e9a53414c0266dc'
Invoke-WebRequest -Uri $baselineUrl -OutFile $oldExe -MaximumRetryCount 3 -RetryIntervalSec 2
if ((Get-Item -LiteralPath $oldExe).Length -ne 147298816 -or (Get-FileHash -LiteralPath $oldExe -Algorithm SHA256).Hash.ToLowerInvariant() -cne $baselineHash) {
    throw 'Official 1.5.19 EXE bytes do not match the pinned baseline.'
}
[VNcode.Smoke.EmbeddedMsi]::Extract($oldExe, $oldMsi)
function Msi([string]$Action,[string]$Package,[switch]$Cleanup) {
    $process = Start-Process msiexec.exe -ArgumentList @($Action,"`"$Package`"",'/qn','/norestart') -Wait -PassThru
    $allowed = if ($Cleanup) { @(0,3010,1605) } else { @(0,3010) }
    if ($process.ExitCode -notin $allowed) { throw "MSI operation failed: $($process.ExitCode)" }
}
function Registrations() {
    return @(Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*','HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue | Where-Object DisplayName -CEQ 'VN code')
}
function AppProcesses([string]$Launcher) {
    return @(Get-CimInstance Win32_Process -Filter "Name='VN code.exe'" | Where-Object { $_.ExecutablePath -and $_.ExecutablePath -ieq $Launcher })
}
if (@(Registrations).Count -ne 0) { throw 'Runner already has a VN code registration.' }
$ruleName = 'vncode-updater-offline-' + [guid]::NewGuid()
$launcher = Join-Path $program 'VN code.exe'
$app = $null; $installed = $false; $firewallInstalled = $false
try {
    Msi '/i' $oldMsi
    $installed = $true
    $baselineRegistrations = @(Registrations)
    Write-Host ("Baseline registrations: {0}; expected launcher exists: {1}" -f $baselineRegistrations.Count, (Test-Path -LiteralPath $launcher))
    if ($baselineRegistrations.Count -ne 1 -or $baselineRegistrations[0].DisplayVersion -cne '1.5.19' -or -not (Test-Path -LiteralPath $launcher)) {
        throw 'Official 1.5.19 installation was not registered as expected.'
    }
    # The app has no need for network in this proof. The Java probe's loopback
    # server runs under java.exe, a different executable from this blocked app.
    New-NetFirewallRule -Name $ruleName -DisplayName 'VN code isolated updater proof' -Direction Outbound -Program $launcher -Action Block -Enabled True | Out-Null
    $firewallInstalled = $true
    Write-Host 'Launching isolated 1.5.19 with outbound network blocked.'
    $app = Start-Process -FilePath $launcher -PassThru
    [void](Wait-VncodeWindow -Process $app -Version '1.5.19')
    Stop-Process -Id $app.Id -Force
    $app.WaitForExit(); $app = $null
    $baselineIds = @((AppProcesses $launcher) | ForEach-Object { [int]$_.ProcessId })
    foreach ($baselineId in $baselineIds) { Stop-Process -Id $baselineId -Force -ErrorAction SilentlyContinue }
    foreach ($baselineId in $baselineIds) { Wait-Process -Id $baselineId -Timeout 20 -ErrorAction SilentlyContinue }
    if (@(AppProcesses $launcher).Count -ne 0) { throw 'An old baseline process is still running before the updater test.' }
    Write-Host '1.5.19 startup succeeded; preparing fake local data.'
    $oldClasspath = "$program\app\*;$program\app\lib\*"
    & javac --add-modules jdk.httpserver -cp $oldClasspath -d $root (Join-Path $PSScriptRoot 'WindowsDataProbe.java') (Join-Path $PSScriptRoot 'WindowsUpgradeDataProbe.java') (Join-Path $PSScriptRoot 'UpdaterMsiProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot compile isolated old-version probes.' }
    $classpath = "$root;$oldClasspath"
    & java --enable-native-access=ALL-UNNAMED -cp $classpath WindowsDataProbe fresh $data 6
    if ($LASTEXITCODE -ne 0) { throw 'Old app did not create its expected fresh schema.' }
    & java --enable-native-access=ALL-UNNAMED -cp $classpath WindowsUpgradeDataProbe seed $data
    if ($LASTEXITCODE -ne 0) { throw 'Cannot seed isolated fake data.' }
    $before = & java --enable-native-access=ALL-UNNAMED -cp $classpath WindowsUpgradeDataProbe fingerprint $data
    if ($LASTEXITCODE -ne 0 -or $before -notmatch '^[a-f0-9]{64}$') { throw 'Invalid pre-upgrade fingerprint.' }
    Write-Host 'Fake data seeded and fingerprinted; starting old updater harness.'
    $probeOut = Join-Path $root 'updater-probe.stdout.log'
    $probeErr = Join-Path $root 'updater-probe.stderr.log'
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    $args = @('--add-modules','jdk.httpserver','--enable-native-access=ALL-UNNAMED','-cp',"`"$classpath`"",'com.vncode.app.integration.update.UpdaterMsiProbe',"`"$newMsi`"",$newMsiHash,[string]$newMsiBytes)
    # Start-Process -Wait can wait for the entire descendant tree, including
    # the newly relaunched app. Wait only for this harness PID to exit.
    $probe = Start-Process -FilePath $java -ArgumentList $args -PassThru -RedirectStandardOutput $probeOut -RedirectStandardError $probeErr
    if (-not $probe.WaitForExit(180000)) {
        $probe.Kill($true)
        $probe.WaitForExit()
        throw 'The updater harness did not exit within its deadline.'
    }
    Write-Host 'The old updater harness exited; inspecting its handoff.'
    if ($probe.ExitCode -ne 0 -or -not (Select-String -LiteralPath $probeOut -SimpleMatch 'UPDATER_MSI_HANDOFF_STARTED' -Quiet)) {
        if (Test-Path -LiteralPath $probeErr) { Get-Content -LiteralPath $probeErr -Tail 40 | Write-Host }
        throw 'The installed 1.5.19 updater did not complete its verified handoff.'
    }
    $handoffPath = Join-Path $env:RUNNER_TEMP 'vncode-updater-handoff.properties'
    $handoff = Get-Content -LiteralPath $handoffPath -Raw | ConvertFrom-StringData
    if ($handoff.updaterVersion -cne '1.5.19' -or $handoff.candidateVersion -cne $Version -or
        $handoff.selected -cne 'msi' -or $handoff.sha256 -cne $newMsiHash -or [long]$handoff.bytes -ne $newMsiBytes -or
        $handoff.rejectedIncorrectChecksum -cne 'true' -or $handoff.syntheticSignedManifestVerified -cne 'true' -or
        $handoff.snapshotRequested -cne 'true' -or $handoff.handoffLaunched -cne 'true') {
        throw 'The updater handoff evidence does not match the candidate.'
    }
    $snapshotMeta = @(Get-ChildItem -LiteralPath (Join-Path $data 'snapshots') -Recurse -File -Filter 'snapshot.properties' -ErrorAction SilentlyContinue |
        Where-Object { Select-String -LiteralPath $_.FullName -Pattern '^reason=signed-update-install$' -Quiet })
    if ($snapshotMeta.Count -lt 1) { throw 'Updater did not persist its update rollback snapshot.' }
    Write-Host 'Rollback snapshot persisted; waiting for MSI upgrade and relaunch.'
    $deadline = [DateTime]::UtcNow.AddMinutes(5); $ready = $false; $lastState = ''
    while ([DateTime]::UtcNow -lt $deadline) {
        $registrations = @(Registrations); $running = @(AppProcesses $launcher)
        $versions = @($registrations | ForEach-Object { [string]$_.DisplayVersion }) -join ','
        $msiProcesses = @(Get-Process -Name 'msiexec' -ErrorAction SilentlyContinue).Count
        # A jpackage launcher and its child may share the executable path. Count
        # process trees rather than falsely treating a parent/child pair as two apps.
        $ids = @($running | ForEach-Object { [int]$_.ProcessId })
        $roots = @($running | Where-Object { $ids -notcontains [int]$_.ParentProcessId })
        $relations = @($running | ForEach-Object { "$($_.ProcessId):$($_.ParentProcessId)" }) -join ','
        $state = "registrations=$($registrations.Count); versions=$versions; appProcesses=$($running.Count); appTrees=$($roots.Count); appPidParents=$relations; msiexecProcesses=$msiProcesses"
        if ($state -cne $lastState) { Write-Host "Updater progress: $state"; $lastState = $state }
        if ($registrations.Count -eq 1 -and $registrations[0].DisplayVersion -ceq $Version -and $roots.Count -eq 1) {
            $app = Get-Process -Id ([int]$roots[0].ProcessId) -ErrorAction Stop
            [void](Wait-VncodeWindow -Process $app -Version $Version)
            $ready = $true; break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'Updater did not install and relaunch the expected candidate.' }
    $appIds = @((AppProcesses $launcher) | ForEach-Object { [int]$_.ProcessId })
    foreach ($appId in $appIds) { Stop-Process -Id $appId -Force -ErrorAction SilentlyContinue }
    foreach ($appId in $appIds) { Wait-Process -Id $appId -Timeout 20 -ErrorAction SilentlyContinue }
    $app = $null
    $newClasspath = "$root;$program\app\*;$program\app\lib\*"
    $after = & java --enable-native-access=ALL-UNNAMED -cp $newClasspath WindowsUpgradeDataProbe fingerprint $data
    if ($LASTEXITCODE -ne 0 -or $after -cne $before) { throw 'Existing fake seller data changed during the updater handoff.' }
    & java --enable-native-access=ALL-UNNAMED -cp $newClasspath WindowsUpgradeDataProbe verify-kiz-priority $data
    if ($LASTEXITCODE -ne 0) { throw 'KIZ fixture priority changed during upgrade.' }
    Msi '/x' $newMsi
    $installed = $false
    if (@(Registrations).Count -ne 0 -or -not (Test-Path -LiteralPath (Join-Path $data 'database.db'))) { throw 'Uninstall did not preserve isolated data.' }
    $proof = @{
        result='passed'; fromVersion='1.5.19'; version=$Version; sourceCommit=$ExpectedSource; buildRunId=$ExpectedRunId
        baselineExeSha256=$baselineHash; candidateMsiSha256=$newMsiHash; candidateMsiBytes=$newMsiBytes
        usedInstalledOldUpdater=$true; metadataAndMsiTransport='loopback-synthetic'; syntheticSignedManifestVerified=$true; signedManifestProductionVerified=$false
        msiHandoffAndRelaunch=$true; singleRegistration=$true; dataPreserved=$true; rollbackSnapshotCreated=$true
        realSellerDataUsed=$false; realLicenseActivated=$false; liveMarketplaceMutations=$false
        fingerprint=$after; completedAt=[DateTime]::UtcNow.ToString('o')
    }
    $proof | ConvertTo-Json | Set-Content -LiteralPath $ProofPath -Encoding utf8
} finally {
    if ($app -and -not $app.HasExited) { Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue }
    foreach ($item in @(AppProcesses $launcher)) { Stop-Process -Id ([int]$item.ProcessId) -Force -ErrorAction SilentlyContinue }
    if ($installed) {
        foreach ($entry in @(Registrations)) {
            if ($entry.DisplayVersion -ceq $Version) { Msi '/x' $newMsi -Cleanup }
            elseif ($entry.DisplayVersion -ceq '1.5.19') { Msi '/x' $oldMsi -Cleanup }
        }
    }
    if ($firewallInstalled) { Remove-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue }
}
