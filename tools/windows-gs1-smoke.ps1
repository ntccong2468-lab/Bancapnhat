param([Parameter(Mandatory=$true)][string] $Version)
$ErrorActionPreference='Stop'
if ($env:GITHUB_ACTIONS -cne 'true' -or $env:RUNNER_OS -cne 'Windows' -or $env:RUNNER_ENVIRONMENT -cne 'github-hosted') {
    throw 'The GS1 native probe requires a disposable GitHub-hosted Windows runner.'
}
$root=Join-Path $env:RUNNER_TEMP ('vncode-gs1-'+[guid]::NewGuid())
New-Item -ItemType Directory -Path $root | Out-Null
$classpath="$PWD\target\VNcode-$Version.jar;$PWD\target\lib\*"
try {
    & javac -cp $classpath -d $root tools\WindowsGs1Probe.java
    if ($LASTEXITCODE -ne 0) { throw 'Cannot compile the GS1 native probe.' }
    $report=& java --enable-native-access=ALL-UNNAMED -cp "$root;$classpath" WindowsGs1Probe $Version (Join-Path $root 'data')
    if ($LASTEXITCODE -ne 0) { throw 'The GS1 DPAPI/storage probe failed.' }
    $json=$report | ConvertFrom-Json
    if ($json.result -cne 'passed' -or $json.version -cne $Version -or $json.liveGS1Mutations) { throw 'Invalid GS1 native probe report.' }
    $json | ConvertTo-Json | Set-Content out\gs1-smoke.json -Encoding utf8
} finally {
    Remove-Item -LiteralPath $root -Recurse -Force
}
