# SPDX-License-Identifier: Apache-2.0
# Samples only the fixture JVM, not Gradle or the whole machine. No forced GC or leak claim.
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$measurementDirectory = Join-Path $repositoryRoot 'build/verification/report-stress'
$fixtureDirectory = Join-Path $repositoryRoot 'examples/build/report-stress'
[void](New-Item -ItemType Directory -Force -Path $measurementDirectory)
$runId = [Guid]::NewGuid().ToString('N')
$started = [DateTime]::UtcNow
$stdout = Join-Path $measurementDirectory 'gradle.log'
$stderr = Join-Path $measurementDirectory 'gradle-error.log'
$command = ".\gradlew.bat -g build/gradle-validation :examples:stressReports -Pexport4jStressRun=$runId --no-daemon --console=plain"
$buildProcess = Start-Process -FilePath $env:ComSpec -ArgumentList @('/d', '/c', $command) -WorkingDirectory $repositoryRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
$samples = [System.Collections.Generic.List[object]]::new()
while (-not $buildProcess.HasExited) {
    if (([DateTime]::UtcNow - $started).TotalMinutes -gt 10) { throw 'Stress build exceeded ten minutes' }
    $runningFile = Join-Path $fixtureDirectory 'running.json'
    if (Test-Path -LiteralPath $runningFile) {
        try { $running = Get-Content -LiteralPath $runningFile -Raw | ConvertFrom-Json } catch { $running = $null }
        if ($running -and $running.run -eq $runId) {
            $fixtureProcess = Get-Process -Id $running.pid -ErrorAction SilentlyContinue
            if ($fixtureProcess -and $fixtureProcess.StartTime.ToUniversalTime() -ge $started.AddSeconds(-1)) {
                $fixtureProcess.Refresh()
                $samples.Add([pscustomobject]@{ milliseconds = [math]::Round(([DateTime]::UtcNow - $started).TotalMilliseconds); workingSetBytes = $fixtureProcess.WorkingSet64; privateBytes = $fixtureProcess.PrivateMemorySize64; handles = $fixtureProcess.HandleCount; threads = $fixtureProcess.Threads.Count })
            }
        }
    }
    Start-Sleep -Milliseconds 250
    $buildProcess.Refresh()
}
$buildProcess.WaitForExit()
if ($buildProcess.ExitCode -ne 0) { throw "Stress build failed; see $stdout and $stderr" }
$report = Get-Content -LiteralPath (Join-Path $fixtureDirectory 'report.json') -Raw | ConvertFrom-Json
if ($report.run -ne $runId -or $samples.Count -eq 0) { throw 'Missing fresh stress report or JVM process samples' }
$samples | Export-Csv -LiteralPath (Join-Path $measurementDirectory 'process.csv') -NoTypeInformation -Encoding utf8
$summary = [ordered]@{ run = $runId; platform = [Environment]::OSVersion.VersionString; samplingMilliseconds = 250; samples = $samples.Count; peakWorkingSetBytes = ($samples | Measure-Object workingSetBytes -Maximum).Maximum; peakPrivateBytes = ($samples | Measure-Object privateBytes -Maximum).Maximum; peakHandles = ($samples | Measure-Object handles -Maximum).Maximum; firstHandles = $samples[0].handles; lastHandles = $samples[$samples.Count - 1].handles; fixture = $report }
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $measurementDirectory 'process-report.json') -Encoding utf8
$summary | ConvertTo-Json -Depth 8
