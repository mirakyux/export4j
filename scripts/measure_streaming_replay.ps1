# SPDX-License-Identifier: Apache-2.0
<# Independent Java 21 / 64 MiB fixture processes. Measures ordinary layout, real disk-backed PDF,
explicit semantic replay, repeated failure/cancel recovery and two concurrent PDF JVMs.
Only observed finite workloads are asserted; process memory and sampled heap are distinct metrics. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ClasspathFile,
    [Parameter(Mandatory=$true)][string]$JavaExecutable,
    [ValidateRange(1,1000000)][int[]]$RowCounts = @(1000,10000,100000),
    [ValidateSet('layout','pdf','replay')][string[]]$Modes = @('layout','pdf','replay'),
    [switch]$SkipLifecycle,
    [switch]$RequirePublishedJars,
    [ValidateRange(25,1000)][int]$SampleMilliseconds = 100,
    [ValidateRange(1,30)][int]$TimeoutMinutes = 15
)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$classpath = (Get-Content -LiteralPath $ClasspathFile -Raw).Trim()
if (-not $classpath -or -not (Test-Path -LiteralPath $JavaExecutable -PathType Leaf)) { throw 'Missing fixture classpath or Java executable' }
$javaVersion = (& $JavaExecutable '--version' | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch '(?m)^(?:openjdk|java) 21(?:\.|\s)') { throw 'Streaming capacity verification requires Java 21' }
$runtimeArtifacts = @(
    foreach ($entry in $classpath.Split([IO.Path]::PathSeparator)) {
        $item = Get-Item -LiteralPath $entry
        if ($item.PSIsContainer) {
            if ($RequirePublishedJars) { throw 'Published capacity verification cannot use class directories' }
            [pscustomobject]@{name=$item.Name;kind='class-directory';version=$null;sha256=$null}
        } else {
            if ($item.Extension -ne '.jar') { throw 'Capacity runtime entries must be JARs or class directories' }
            $baseName = $item.BaseName -replace '-natives-(windows|linux|macos)(-.+)?$', ''
            $version = if ($baseName -match '-(?<version>\d[0-9A-Za-z.+_-]*)$') { $Matches.version } else { $null }
            [pscustomobject]@{name=$item.Name;kind='jar';version=$version;sha256=(Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
        }
    }
)
$classpathSha256 = (Get-FileHash -LiteralPath $ClasspathFile -Algorithm SHA256).Hash.ToLowerInvariant()
$runId = [Guid]::NewGuid().ToString('N')
$runRoot = Join-Path $repositoryRoot ('build/verification/streaming-replay/capacity-' + $runId)
[void](New-Item -ItemType Directory -Path $runRoot -Force)
function Quote-Java([string]$value) { return '"' + $value.Replace('\','/').Replace('"','\"') + '"' }
$script:sharedHandleFallbacks = 0L
function Refreshed-FileBytes([string]$path) {
    $script:sharedHandleFallbacks++
    $info = [IO.FileInfo]::new($path)
    $info.Refresh()
    if ($info.Exists) { return $info.Length }
    return 0L
}
function Current-FileBytes([string]$path) {
    # Directory enumeration may report the last closed size of a currently open Windows file.
    # Query an independent shared handle so the active atomic destination is included.
    try {
        $stream = [IO.File]::Open($path,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
        try { return $stream.Length } finally { $stream.Dispose() }
    } catch [IO.FileNotFoundException] { return 0L }
      catch [IO.DirectoryNotFoundException] { return 0L }
      catch [UnauthorizedAccessException] { return (Refreshed-FileBytes $path) }
      catch [IO.IOException] {
          $failure = $_.Exception
          while ($failure.InnerException) { $failure = $failure.InnerException }
          if (($failure.HResult -band 65535) -in @(32,33)) { return (Refreshed-FileBytes $path) }
          throw
      }
}
function Temporary-Bytes([string]$directory) {
    $total = 0L
    foreach ($item in @(Get-ChildItem -LiteralPath $directory -Force -ErrorAction SilentlyContinue)) {
        if ($item.Name.StartsWith('.export4j')) {
            if ($item.PSIsContainer) { foreach ($file in @(Get-ChildItem -LiteralPath $item.FullName -File -Recurse -Force -ErrorAction SilentlyContinue)) { $total += (Current-FileBytes $file.FullName) } }
            else { $total += (Current-FileBytes $item.FullName) }
        }
    }
    return $total
}
$groups = [System.Collections.Generic.List[object]]::new()
foreach ($mode in $Modes) {
    foreach ($rows in $RowCounts) { $groups.Add(@([pscustomobject]@{name="$mode-$rows";mode=$mode;rows=$rows})) }
}
if (-not $SkipLifecycle) {
    $groups.Add(@([pscustomobject]@{name='stability';mode='stability';rows=1000}))
    $groups.Add(@([pscustomobject]@{name='concurrent-a';mode='pdf';rows=10000},[pscustomobject]@{name='concurrent-b';mode='pdf';rows=10000}))
}
$results = [System.Collections.Generic.List[object]]::new()
foreach ($group in $groups) {
    $jobs = [System.Collections.Generic.List[object]]::new()
    foreach ($workload in $group) {
        $case = Join-Path $runRoot $workload.name; [void](New-Item -ItemType Directory -Path $case)
        $arguments = @('-Xms32m','-Xmx64m','-XX:+UseG1GC','-Djava.awt.headless=true',
            (Quote-Java ('-Dconsumer.outputDirectory=' + $case)), '-cp', (Quote-Java $classpath),
            'consumer.StreamingReplayConsumer','--capacity',$workload.rows.ToString(),$workload.mode)
        $argumentFile = Join-Path $case 'java.args'
        [IO.File]::WriteAllText($argumentFile,($arguments -join [Environment]::NewLine),[Text.UTF8Encoding]::new($false))
        $process = Start-Process -FilePath $JavaExecutable -ArgumentList ('@"' + $argumentFile + '"') -WorkingDirectory (Join-Path $repositoryRoot 'verification/consumer') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $case 'stdout.log') -RedirectStandardError (Join-Path $case 'stderr.log')
        [void]$process.Handle
        $jobs.Add([pscustomobject]@{process=$process;case=$case;workload=$workload;started=[DateTime]::UtcNow;samples=[System.Collections.Generic.List[object]]::new()})
    }
    try {
        do {
            $active = $false
            foreach ($job in $jobs) {
                $process = $job.process; $process.Refresh()
                if ($process.HasExited) { continue }
                $active = $true
                if (([DateTime]::UtcNow - $job.started).TotalMinutes -gt $TimeoutMinutes) { throw 'Streaming replay capacity timeout' }
                try {
                    $job.samples.Add([pscustomobject]@{milliseconds=[math]::Round(([DateTime]::UtcNow-$job.started).TotalMilliseconds);workingSetBytes=$process.WorkingSet64;privateBytes=$process.PrivateMemorySize64;handles=$process.HandleCount;temporaryBytes=(Temporary-Bytes (Join-Path $job.case 'streaming-replay'))})
                } catch [System.InvalidOperationException] { }
            }
            if ($active) { Start-Sleep -Milliseconds $SampleMilliseconds }
        } while ($active)
        foreach ($job in $jobs) {
            $job.process.WaitForExit()
            if ($job.process.ExitCode -ne 0) { throw ('Streaming capacity failed: ' + $job.workload.name + '; see ' + $job.case) }
            $data = Join-Path $job.case 'streaming-replay'
            $fixtureFile = if ($job.workload.mode -eq 'stability') { 'stability.json' } else { 'capacity-' + $job.workload.mode + '-' + $job.workload.rows + '.json' }
            $fixture = Get-Content -LiteralPath (Join-Path $data $fixtureFile) -Raw | ConvertFrom-Json
            $temporary = @(Get-ChildItem -LiteralPath $data -Force | Where-Object { $_.Name.StartsWith('.export4j') })
            if ($temporary.Count -ne 0 -or $job.samples.Count -eq 0) { throw 'Streaming capacity missing process samples or left temporary files' }
            if ($job.workload.mode -ne 'stability') {
                if ($fixture.reads -ne $fixture.passes * ($fixture.rows + 1) -or $fixture.closes -ne $fixture.passes -or $fixture.maxHeapBytes -gt 67108864) { throw 'Streaming capacity source or heap counters differ' }
            }
            $job.samples | Export-Csv -LiteralPath (Join-Path $job.case 'process.csv') -NoTypeInformation -Encoding utf8
            $results.Add([pscustomobject]@{case=$job.workload.name;mode=$job.workload.mode;rows=$job.workload.rows;samples=$job.samples.Count;peakWorkingSetBytes=($job.samples|Measure-Object workingSetBytes -Maximum).Maximum;peakPrivateBytes=($job.samples|Measure-Object privateBytes -Maximum).Maximum;peakHandles=($job.samples|Measure-Object handles -Maximum).Maximum;peakTemporaryBytes=($job.samples|Measure-Object temporaryBytes -Maximum).Maximum;remainingTemporaryFiles=0;fixture=$fixture})
            Write-Output ('Completed ' + $job.workload.name)
        }
    } finally {
        foreach ($job in $jobs) { if (-not $job.process.HasExited) { $job.process.Kill(); $job.process.WaitForExit() }; $job.process.Dispose() }
    }
}
$runtimeIndex = 0
foreach ($entry in $classpath.Split([IO.Path]::PathSeparator)) {
    if ($runtimeArtifacts[$runtimeIndex].kind -eq 'jar' -and (Get-FileHash -LiteralPath $entry -Algorithm SHA256).Hash.ToLowerInvariant() -ne $runtimeArtifacts[$runtimeIndex].sha256) {
        throw 'Capacity runtime JAR changed during measurement'
    }
    $runtimeIndex++
}
$report = [pscustomobject]@{run=$runId;java='21';javaVersion=$javaVersion;heapMiB=64;classpathSha256=$classpathSha256;runtimeVersionSource='jar-file-name';runtimeArtifacts=$runtimeArtifacts;sharedHandleFallbacks=$script:sharedHandleFallbacks;fontSha256=(Get-FileHash -LiteralPath (Join-Path $repositoryRoot 'assets/fonts/LiberationSans-Regular.ttf') -Algorithm SHA256).Hash.ToLowerInvariant();results=$results}
$report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $runRoot 'report.json') -Encoding utf8
[IO.File]::WriteAllText((Join-Path $repositoryRoot 'build/verification/streaming-replay-capacity-directory.txt'),$runRoot)
Write-Output $runRoot
