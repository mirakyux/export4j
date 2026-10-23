# SPDX-License-Identifier: Apache-2.0
<# Finite concurrent recovery workload. Measurements are process observations, not a proof of no leaks. #>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [ValidateRange(2,8)][int]$Workers = 2,
    [ValidateRange(5,100)][int]$Rounds = 5,
    [ValidateRange(100,100000)][int]$Rows = 1000,
    [ValidateRange(1,30)][int]$TimeoutMinutes = 5
)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$runtimeDirectory = Join-Path $repositoryRoot 'build/verification/streaming-table'
if (-not $SkipBuild) {
    Push-Location $repositoryRoot
    try {
        & .\gradlew.bat -g build/gradle-validation :examples:writeStreamingTableClasspath --offline --no-daemon --console=plain *> (Join-Path $repositoryRoot 'build/verification/stability-compile.log')
        if ($LASTEXITCODE -ne 0) { throw 'Stability fixture compilation failed' }
    } finally { Pop-Location }
}
$classpath = (Get-Content -LiteralPath (Join-Path $runtimeDirectory 'runtime-classpath.txt') -Raw).Trim()
$javaExecutable = (Get-Content -LiteralPath (Join-Path $runtimeDirectory 'java-executable.txt') -Raw).Trim()
if (-not $classpath -or -not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) { throw 'Missing compiled fixture runtime' }
$runDirectory = Join-Path $repositoryRoot ('build/verification/streaming-stability/run-' + [Guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $runDirectory -Force)
function Quote-JavaArgument([string]$Value) { return '"' + $Value.Replace('\','/').Replace('"','\"') + '"' }
$cases = [System.Collections.Generic.List[object]]::new()
foreach ($mode in @('discard','pdf')) {
    $caseDirectory = Join-Path $runDirectory $mode
    $temporary = Join-Path $caseDirectory 'tmp'
    [void](New-Item -ItemType Directory -Path $temporary -Force)
    $argumentFile = Join-Path $caseDirectory 'java.args'
    $arguments = @('-Xms64m','-Xmx256m','-XX:+UseG1GC','-Djava.awt.headless=true',
        (Quote-JavaArgument ('-Djava.io.tmpdir=' + $temporary)), '-cp', (Quote-JavaArgument $classpath),
        'com.mirakyux.export4j.examples.StreamingStabilityExample', $mode, $Workers, $Rounds, $Rows, (Quote-JavaArgument $caseDirectory))
    [IO.File]::WriteAllText($argumentFile, ($arguments -join [Environment]::NewLine) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    $started = [DateTime]::UtcNow
    $process = Start-Process -FilePath $javaExecutable -ArgumentList ('@"' + $argumentFile + '"') -WorkingDirectory $repositoryRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $caseDirectory 'stdout.log') -RedirectStandardError (Join-Path $caseDirectory 'stderr.log')
    [void]$process.Handle
    $samples = [System.Collections.Generic.List[object]]::new()
    try {
        while (-not $process.HasExited) {
            if (([DateTime]::UtcNow - $started).TotalMinutes -gt $TimeoutMinutes) { throw 'Stability JVM exceeded its deadline' }
            $process.Refresh()
            if (-not $process.HasExited) {
                $temporaryFiles = @(Get-ChildItem -LiteralPath $temporary -File -Recurse -ErrorAction SilentlyContinue)
                $atomicFiles = @(Get-ChildItem -LiteralPath $caseDirectory -File -Recurse -Filter '.export4j-*' -ErrorAction SilentlyContinue)
                $temporaryBytes = 0L
                foreach ($file in @($temporaryFiles) + @($atomicFiles)) { $temporaryBytes += $file.Length }
                $samples.Add([pscustomobject]@{milliseconds=([DateTime]::UtcNow-$started).TotalMilliseconds; workingSetBytes=$process.WorkingSet64; privateBytes=$process.PrivateMemorySize64; handles=$process.HandleCount; temporaryBytes=$temporaryBytes})
            }
            Start-Sleep -Milliseconds 100
        }
        $process.WaitForExit()
        if ($null -eq $process.ExitCode -or $process.ExitCode -ne 0) { throw ('Stability JVM failed; inspect ' + $mode + '/stderr.log') }
        $remaining = @(Get-ChildItem -LiteralPath $temporary -File -Recurse) + @(Get-ChildItem -LiteralPath $caseDirectory -File -Recurse -Filter '.export4j-*')
        if ($remaining.Count -ne 0) { throw 'Stability JVM left temporary resources' }
        $samples | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $caseDirectory 'process-samples.json') -Encoding UTF8
        $cases.Add([pscustomobject]@{mode=$mode; exitCode=$process.ExitCode; samples=$samples.Count; peakWorkingSetBytes=($samples | Measure-Object workingSetBytes -Maximum).Maximum; peakPrivateBytes=($samples | Measure-Object privateBytes -Maximum).Maximum; peakHandles=($samples | Measure-Object handles -Maximum).Maximum; peakTemporaryBytes=($samples | Measure-Object temporaryBytes -Maximum).Maximum; exitTemporaryFiles=$remaining.Count})
    } finally {
        if (-not $process.HasExited) { $process.Kill(); [void]$process.WaitForExit(10000) }
        $process.Dispose()
    }
}
[pscustomobject]@{workers=$Workers; rounds=$Rounds; rows=$Rows; cases=$cases} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDirectory 'manifest.json') -Encoding UTF8
[IO.File]::WriteAllText((Join-Path $repositoryRoot 'build/verification/streaming-stability-directory.txt'), $runDirectory)
Write-Output ('Stability workload passed: ' + (2 * $Workers * $Rounds) + ' failure/recovery cycles')
