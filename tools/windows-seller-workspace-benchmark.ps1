param(
    [Parameter(Mandatory=$true)][string] $AppImage,
    [Parameter(Mandatory=$true)][string] $FixtureRoot,
    [Parameter(Mandatory=$true)][string] $OutputDirectory,
    [Parameter(Mandatory=$true)][string] $BuildId,
    [ValidateSet(4,10)][int] $OzonShops=4,
    [ValidateSet('1.0','1.25','1.5')][string] $UiScale='1.0',
    [ValidateRange(1,20)][int] $Cycles=20,
    [switch] $Baseline
)
$ErrorActionPreference='Stop'
if(-not $IsWindows){throw 'Run this benchmark on Windows with JDK 25.'}
$app=Join-Path (Resolve-Path $AppImage) 'app'
$jars=@(Get-ChildItem $app -Filter 'VNcode-*.jar' -File)
if($jars.Count -ne 1){throw 'AppImage must contain exactly one VN code main JAR.'}
$classpath="$($jars[0].FullName);$app\lib\*"
$output=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path $output){throw 'OutputDirectory must be new; previous evidence is never overwritten.'}
New-Item -ItemType Directory -Path $output | Out-Null
$probe=Join-Path $output 'probe-classes'
New-Item -ItemType Directory -Path $probe | Out-Null
$probeSource=Join-Path $PSScriptRoot $(if($Baseline){'SellerWorkspaceBaselineProbe.java'}else{'SellerWorkspaceProbe.java'})
& javac -cp $classpath -d $probe (Join-Path $PSScriptRoot '..\src\test\java\com\vncode\app\performance\SellerWorkspaceFixture.java') $probeSource
if($LASTEXITCODE -ne 0){throw 'Cannot compile workspace probe against the specified build.'}
$fixture=[IO.Path]::GetFullPath($FixtureRoot)
if(-not (Test-Path (Join-Path $fixture 'fixture.json'))){
    & java --enable-native-access=ALL-UNNAMED -cp "$probe;$classpath" com.vncode.app.performance.SellerWorkspaceFixture $fixture $OzonShops
    if($LASTEXITCODE -ne 0){throw 'Fixture creation failed.'}
}
$marker=Get-Content (Join-Path $fixture 'fixture.json') -Raw | ConvertFrom-Json
if(-not $marker.synthetic -or $marker.shops -ne 10 -or $marker.products -ne 100000 -or $marker.orders -ne 10000 -or $marker.dataset -cne $(if($OzonShops -eq 10){'10ozon'}else{'6wb4ozon'})){throw 'Only the matching generated fixture is allowed.'}
# Each build receives its own identical copy; no seller appdata is opened.
$data=Join-Path $output 'data'
New-Item -ItemType Directory -Path $data | Out-Null
Copy-Item (Join-Path $fixture 'database.db') $data
Copy-Item (Join-Path $fixture 'fixture.json') $data
$reportDirectory=Join-Path $output 'results'
if($Baseline){& java --enable-native-access=ALL-UNNAMED "-Dglass.win.uiScale=$UiScale" -cp "$probe;$classpath" SellerWorkspaceBaselineProbe $data $reportDirectory $Cycles *> (Join-Path $output 'probe.log')}
else{& java --enable-native-access=ALL-UNNAMED "-Dglass.win.uiScale=$UiScale" -cp "$probe;$classpath" SellerWorkspaceProbe run $data $reportDirectory $Cycles *> (Join-Path $output 'probe.log')}
if($LASTEXITCODE -ne 0){throw "Workspace probe failed; inspect $output\probe.log."}
$report=Get-Content (Join-Path $reportDirectory 'workspace-probe.json') -Raw | ConvertFrom-Json
if($report.result -cne 'passed'){throw 'Invalid workspace result.'}
if(-not $Baseline -and ($report.readWorkers -ne 3 -or $report.imageWorkers -ne 2 -or $report.readPending -ne 0 -or $report.mutationPending -ne 0 -or $report.screenCacheBytes -gt 67108864)){throw 'Invalid workspace budget report.'}
if([Math]::Abs($report.outputScale-[double]::Parse($UiScale,[Globalization.CultureInfo]::InvariantCulture)) -gt 0.02){throw 'The requested Windows DPI scale was not applied.'}
$computer=Get-CimInstance Win32_ComputerSystem
$os=Get-CimInstance Win32_OperatingSystem
@{
    appName='VN code'; buildId=$BuildId; inputJarSha256=(Get-FileHash $jars[0].FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    datasetId=$marker.dataset; fixtureSha256=(Get-FileHash (Join-Path $fixture 'database.db') -Algorithm SHA256).Hash.ToLowerInvariant()
    hardwareId="$($computer.Manufacturer)/$($computer.Model)"; logicalProcessors=$computer.NumberOfLogicalProcessors; physicalMemoryBytes=$computer.TotalPhysicalMemory
    osVersion=$os.Version; requestedScale=$UiScale; cycles=$Cycles; report=$report
    targetWindowsLowEndMeasured=($computer.NumberOfLogicalProcessors -eq 2 -and $computer.TotalPhysicalMemory -le 4GB)
} | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $output 'benchmark.json') -Encoding utf8
