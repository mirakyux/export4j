# SPDX-License-Identifier: Apache-2.0
# Samples only the fixture JVM, not Gradle or the whole machine. No forced GC or leak claim.
[CmdletBinding()]
param(
    [ValidateRange(1,3600)][int]$TimeoutSeconds = 600,
    [ValidateRange(25,1000)][int]$SampleMilliseconds = 250
)
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
$buildProcess = $null
$ownedProcesses = [System.Collections.Generic.Dictionary[int,object]]::new()
function Register-OwnedChildren {
    $processes = @(Get-CimInstance Win32_Process -Property ProcessId,ParentProcessId,CreationDate)
    do {
        $added = $false
        foreach ($entry in $processes) {
            $childId = [int]$entry.ProcessId
            if ($ownedProcesses.ContainsKey($childId) -or -not $ownedProcesses.ContainsKey([int]$entry.ParentProcessId)) { continue }
            $process = Get-Process -Id $childId -ErrorAction SilentlyContinue
            if (-not $process) { continue }
            try {
                [void]$process.Handle
                $created = $process.StartTime.ToUniversalTime()
                if ($created -lt $started -or ($created - $entry.CreationDate.ToUniversalTime()).Duration().TotalMilliseconds -gt 1) { $process.Dispose(); continue }
                $ownedProcesses.Add($childId, [pscustomobject]@{process=$process;started=$created})
                $added = $true
            } catch [System.InvalidOperationException] { $process.Dispose() }
        }
    } while ($added)
}
$samples = [System.Collections.Generic.List[object]]::new()
try {
    $buildProcess = Start-Process -FilePath $env:ComSpec -ArgumentList @('/d', '/c', $command) -WorkingDirectory $repositoryRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    [void]$buildProcess.Handle
    $ownedProcesses.Add($buildProcess.Id, [pscustomobject]@{process=$buildProcess;started=$buildProcess.StartTime.ToUniversalTime()})
    while (-not $buildProcess.HasExited) {
        Register-OwnedChildren
        if (([DateTime]::UtcNow - $started).TotalSeconds -gt $TimeoutSeconds) { throw "Stress build exceeded $TimeoutSeconds seconds" }
        $runningFile = Join-Path $fixtureDirectory 'running.json'
        if (Test-Path -LiteralPath $runningFile) {
            try { $running = Get-Content -LiteralPath $runningFile -Raw | ConvertFrom-Json } catch { $running = $null }
            if ($running -and $running.run -eq $runId) {
                $fixtureProcess = Get-Process -Id $running.pid -ErrorAction SilentlyContinue
                try {
                    if ($fixtureProcess -and $ownedProcesses.ContainsKey([int]$running.pid) -and
                            $fixtureProcess.StartTime.ToUniversalTime() -eq $ownedProcesses[[int]$running.pid].started) {
                        $fixtureProcess.Refresh()
                        $samples.Add([pscustomobject]@{ milliseconds = [math]::Round(([DateTime]::UtcNow - $started).TotalMilliseconds); workingSetBytes = $fixtureProcess.WorkingSet64; privateBytes = $fixtureProcess.PrivateMemorySize64; handles = $fixtureProcess.HandleCount; threads = $fixtureProcess.Threads.Count })
                    }
                } finally { if ($fixtureProcess) { $fixtureProcess.Dispose() } }
            }
        }
        Start-Sleep -Milliseconds $SampleMilliseconds
        $buildProcess.Refresh()
    }
    $buildProcess.WaitForExit()
    if ($buildProcess.ExitCode -ne 0) { throw "Stress build failed; see $stdout and $stderr" }
    $report = Get-Content -LiteralPath (Join-Path $fixtureDirectory 'report.json') -Raw | ConvertFrom-Json
    if ($report.run -ne $runId -or $samples.Count -eq 0) { throw 'Missing fresh stress report or JVM process samples' }
    $samples | Export-Csv -LiteralPath (Join-Path $measurementDirectory 'process.csv') -NoTypeInformation -Encoding utf8
    $summary = [ordered]@{ run = $runId; platform = [Environment]::OSVersion.VersionString; samplingMilliseconds = $SampleMilliseconds; samples = $samples.Count; peakWorkingSetBytes = ($samples | Measure-Object workingSetBytes -Maximum).Maximum; peakPrivateBytes = ($samples | Measure-Object privateBytes -Maximum).Maximum; peakHandles = ($samples | Measure-Object handles -Maximum).Maximum; firstHandles = $samples[0].handles; lastHandles = $samples[$samples.Count - 1].handles; fixture = $report }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $measurementDirectory 'process-report.json') -Encoding utf8
    $summary | ConvertTo-Json -Depth 8
} finally {
    try { if ($buildProcess) { Register-OwnedChildren } } catch { Write-Warning 'Final process inventory unavailable; cleaning already owned processes' }
    foreach ($entry in @($ownedProcesses.Values | Sort-Object started)) {
        $process = $entry.process
        try {
            $process.Refresh()
            if (-not $process.HasExited -and $process.StartTime.ToUniversalTime() -eq $entry.started) {
                try { $process.Kill($true) } catch [System.Management.Automation.MethodException] { $process.Kill() }
                if (-not $process.WaitForExit(10000)) { Write-Error 'Owned stress process did not exit after termination' -ErrorAction Continue }
            }
        } catch [System.InvalidOperationException] {
            # A process can exit between Refresh and Kill; its pinned handle is still disposed below.
        } catch {
            Write-Error "Could not terminate owned stress process $($process.Id): $_" -ErrorAction Continue
        } finally { $process.Dispose() }
    }
}
