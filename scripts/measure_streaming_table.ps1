# SPDX-License-Identifier: Apache-2.0
<#
Compiles once, then runs six independent fixture JVMs: layout-discard/PDF at 1k/10k/100k rows.
Only the fixture PID is sampled. Heap is observed by the Java fixture; Windows working set,
private bytes and handles are independent process measurements. Temporary disk covers the
isolated java.io.tmpdir and active atomic output file, excluding the final PDF. PDFBox's page
tree still grows. These finite runs do not prove constant memory, a leak-free service or capacity.
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$IncludeSmallHeapControl,
    [switch]$RichText,
    [ValidateRange(1, 100000)][int[]]$RowCounts = @(1000, 10000, 100000),
    [ValidateRange(1, 100000)][int]$SmallHeapRows = 100000,
    [ValidateRange(25, 1000)][int]$SampleMilliseconds = 100,
    [ValidateRange(1, 30)][int]$TimeoutMinutes = 12
)

$ErrorActionPreference = 'Stop'
if ($RowCounts.Count -eq 0 -or @($RowCounts | Select-Object -Unique).Count -ne $RowCounts.Count) {
    throw 'RowCounts must contain at least one distinct positive workload size'
}
$variant = if ($RichText) { 'rich' } else { 'plain' }
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$measurementRoot = Join-Path $repositoryRoot 'build/verification/streaming-table'
[void](New-Item -ItemType Directory -Force -Path $measurementRoot)
if (-not $SkipBuild) {
    Push-Location $repositoryRoot
    try {
        & .\gradlew.bat -g build/gradle-validation :examples:writeStreamingTableClasspath --no-daemon --console=plain *> (Join-Path $measurementRoot 'capacity-compile.log')
        if ($LASTEXITCODE -ne 0) { throw 'Streaming capacity compilation failed; see capacity-compile.log' }
    } finally { Pop-Location }
}
$classpath = (Get-Content -LiteralPath (Join-Path $measurementRoot 'runtime-classpath.txt') -Raw).Trim()
$javaExecutable = (Get-Content -LiteralPath (Join-Path $measurementRoot 'java-executable.txt') -Raw).Trim()
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf) -or -not $classpath) { throw 'Missing Java executable or runtime classpath' }
$hostSystem = Get-CimInstance -ClassName Win32_ComputerSystem
$hostProcessors = @(Get-CimInstance -ClassName Win32_Processor)
$fontNames = @('LiberationSans-Regular.ttf')
if ($RichText) { $fontNames += @('LiberationSans-Bold.ttf', 'LiberationSans-Italic.ttf') }
$fontRecords = [System.Collections.Generic.List[object]]::new()
foreach ($fontName in $fontNames) {
    $fontFile = Join-Path $repositoryRoot ('examples/build/resources/main/fonts/' + $fontName)
    $sourceFontFile = Join-Path $repositoryRoot ('assets/fonts/' + $fontName)
    $fontHash = (Get-FileHash -LiteralPath $fontFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($fontHash -ne (Get-FileHash -LiteralPath $sourceFontFile -Algorithm SHA256).Hash.ToLowerInvariant()) {
        throw 'Runtime capacity font differs from its explicitly bundled source font; rebuild first'
    }
    $fontRecords.Add([pscustomobject]@{ file = ('fonts/' + $fontName); resource = $fontName; sha256 = $fontHash; bytes = (Get-Item -LiteralPath $fontFile).Length })
}
$runId = [Guid]::NewGuid().ToString('N')
$runDirectory = Join-Path $measurementRoot ('capacity-' + $runId)
[void](New-Item -ItemType Directory -Path $runDirectory)
$results = [System.Collections.Generic.List[object]]::new()
$workloads = [System.Collections.Generic.List[object]]::new()
foreach ($workloadMode in @('layout-discard', 'pdf')) {
    foreach ($workloadRows in $RowCounts) {
        $workloads.Add([pscustomobject]@{ mode = $workloadMode; rows = $workloadRows; heapMiB = 512 })
    }
}
if ($IncludeSmallHeapControl) {
    $workloads.Add([pscustomobject]@{ mode = 'layout-discard'; rows = $SmallHeapRows; heapMiB = 64 })
}

function ConvertTo-JavaArgument([string]$Value) {
    # The Java argument-file grammar uses backslash escapes; use forward-slash Windows paths.
    return '"' + $Value.Replace('\', '/').Replace('"', '\"') + '"'
}

function Get-TemporaryBytes([string]$TemporaryDirectory, [string]$OutputDirectory) {
    $bytes = 0L
    foreach ($file in @(Get-ChildItem -LiteralPath $TemporaryDirectory -File -Recurse -ErrorAction SilentlyContinue)) {
        $bytes += $file.Length
    }
    foreach ($file in @(Get-ChildItem -LiteralPath $OutputDirectory -File -Filter '.export4j-*' -ErrorAction SilentlyContinue)) {
        $bytes += $file.Length
    }
    return $bytes
}

function Get-TemporaryFileCount([string]$TemporaryDirectory, [string]$OutputDirectory) {
    return @(Get-ChildItem -LiteralPath $TemporaryDirectory -File -Recurse -ErrorAction SilentlyContinue).Count +
        @(Get-ChildItem -LiteralPath $OutputDirectory -File -Filter '.export4j-*' -ErrorAction SilentlyContinue).Count
}

foreach ($workload in $workloads) {
        $mode = $workload.mode
        $rows = $workload.rows
        $heapMiB = $workload.heapMiB
        $caseName = $mode + '-' + $rows + '-heap' + $heapMiB + 'm'
        if ($RichText) { $caseName = 'rich-' + $caseName }
        $caseDirectory = Join-Path $runDirectory $caseName
        $temporaryDirectory = Join-Path $caseDirectory 'tmp'
        [void](New-Item -ItemType Directory -Path $temporaryDirectory)
        $argumentFile = Join-Path $caseDirectory 'java.args'
        $arguments = @('-Xms64m', ('-Xmx' + $heapMiB + 'm'), '-XX:+UseG1GC', '-Djava.awt.headless=true',
            (ConvertTo-JavaArgument ('-Djava.io.tmpdir=' + $temporaryDirectory)), '-cp',
            (ConvertTo-JavaArgument $classpath), 'com.mirakyux.export4j.examples.StreamingTableCapacityExample',
            $mode, $rows.ToString(), (ConvertTo-JavaArgument $caseDirectory), $runId, $variant)
        [IO.File]::WriteAllText($argumentFile, ($arguments -join [Environment]::NewLine) + [Environment]::NewLine,
            [Text.UTF8Encoding]::new($false))
        $started = [DateTime]::UtcNow
        $processOptions = @{
            FilePath = $javaExecutable; ArgumentList = ('@"' + $argumentFile + '"')
            WorkingDirectory = $repositoryRoot; WindowStyle = 'Hidden'; PassThru = $true
            RedirectStandardOutput = (Join-Path $caseDirectory 'stdout.log')
            RedirectStandardError = (Join-Path $caseDirectory 'stderr.log')
        }
        $process = Start-Process @processOptions
        # Windows PowerShell 5.1 otherwise loses the native exit code after Refresh/HasExited.
        # Cache the handle while the child is alive; preserve explicit failure detection.
        [void]$process.Handle
        $samples = [System.Collections.Generic.List[object]]::new()
        try {
            while (-not $process.HasExited) {
                if (([DateTime]::UtcNow - $started).TotalMinutes -gt $TimeoutMinutes) {
                    $process.Kill()
                    $process.WaitForExit()
                    throw "Streaming capacity $mode/$rows exceeded its timeout"
                }
                try {
                    $process.Refresh()
                    if (-not $process.HasExited) {
                        $samples.Add([pscustomobject]@{
                            milliseconds = [math]::Round(([DateTime]::UtcNow - $started).TotalMilliseconds)
                            workingSetBytes = $process.WorkingSet64
                            privateBytes = $process.PrivateMemorySize64
                            handles = $process.HandleCount
                            threads = $process.Threads.Count
                            temporaryBytes = (Get-TemporaryBytes $temporaryDirectory $caseDirectory)
                        })
                    }
                } catch [System.InvalidOperationException] { }
                Start-Sleep -Milliseconds $SampleMilliseconds
                $process.Refresh()
            }
            $process.WaitForExit()
            $exitCode = $process.ExitCode
            $fixturePid = $process.Id
        } finally {
            if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
            $process.Dispose()
            if ($samples.Count -gt 0) {
                $samples | Export-Csv -LiteralPath (Join-Path $caseDirectory 'process.csv') -NoTypeInformation -Encoding utf8
            }
        }
        if ($exitCode -ne 0) { throw "Streaming capacity $mode/$rows failed with exit $exitCode; see $caseDirectory" }
        $fixture = Get-Content -LiteralPath (Join-Path $caseDirectory 'report.json') -Raw | ConvertFrom-Json
        if ($fixture.run -ne $runId -or $fixture.pid -ne $fixturePid -or $fixture.rows -ne $rows -or
                $fixture.mode -ne $mode -or $fixture.variant -ne $variant -or $samples.Count -eq 0) { throw 'Missing fresh capacity report or JVM samples' }
        $expectedInlines = if ($RichText) { 6L * $rows + 3 } else { 0L }
        if ($fixture.sourceInlineRuns -ne $expectedInlines -or @($fixture.runtimeFonts).Count -ne $fontRecords.Count) {
            throw 'Capacity source run count or runtime font count differs'
        }
        for ($fontIndex = 0; $fontIndex -lt $fontRecords.Count; $fontIndex++) {
            $actualFont = $fixture.runtimeFonts[$fontIndex]
            $expectedFont = $fontRecords[$fontIndex]
            if ($actualFont.resource -ne $expectedFont.resource -or $actualFont.sha256 -ne $expectedFont.sha256 -or $actualFont.bytes -ne $expectedFont.bytes) {
                throw 'Capacity JVM font digest differs from the verified runtime resource'
            }
        }
        $remainingTemporaryBytes = Get-TemporaryBytes $temporaryDirectory $caseDirectory
        $remainingTemporaryFiles = Get-TemporaryFileCount $temporaryDirectory $caseDirectory
        $result = [ordered]@{
            mode = $mode; variant = $variant; rows = $rows; maxHeapMiB = $heapMiB; samplingMilliseconds = $SampleMilliseconds; samples = $samples.Count
            peakWorkingSetBytes = ($samples | Measure-Object workingSetBytes -Maximum).Maximum
            peakPrivateBytes = ($samples | Measure-Object privateBytes -Maximum).Maximum
            peakHandles = ($samples | Measure-Object handles -Maximum).Maximum
            firstHandles = $samples[0].handles; lastHandles = $samples[$samples.Count - 1].handles
            peakTemporaryBytes = ($samples | Measure-Object temporaryBytes -Maximum).Maximum
            remainingTemporaryBytes = $remainingTemporaryBytes; remainingTemporaryFiles = $remainingTemporaryFiles; fixture = $fixture
        }
        $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $caseDirectory 'process-report.json') -Encoding utf8
        if ($remainingTemporaryFiles -ne 0) { throw "Streaming capacity $mode/$rows left temporary files; see $caseDirectory" }
        $results.Add([pscustomobject]$result)
        Write-Output "$variant / $mode / $rows rows / $heapMiB MiB heap: $($fixture.pages) pages, $($fixture.rowsPerSecond) rows/s"
}
$summary = [ordered]@{
    run = $runId; platform = [Environment]::OSVersion.VersionString; independentJvmRuns = $results.Count
    cpu = @($hostProcessors | ForEach-Object { $_.Name.Trim() })
    logicalProcessors = [int]$hostSystem.NumberOfLogicalProcessors
    physicalMemoryBytes = [long]$hostSystem.TotalPhysicalMemory
    javaExecutable = $javaExecutable; primaryMaxHeap = '512 MiB'; smallHeapControl = [bool]$IncludeSmallHeapControl; samplingMilliseconds = $SampleMilliseconds
    variant = $variant; rowCounts = $RowCounts; smallHeapRows = $SmallHeapRows; fonts = $fontRecords.ToArray()
    scope = 'Fixed one-way synthetic rows; layout-discard and PDF measured separately. Sampled heap is not exact peak. PDFBox page/COS objects accumulate; no constant-memory or leak claim.'
    timingScope = 'Fixture elapsedMilliseconds and throughput start inside main after argument validation and output-directory creation; include font initialization, row generation/layout, PDF finish/save and atomic move. Exclude JVM startup/class initialization before main, external process launch/sampling and final report writing.'
    samplingScope = 'Only each independent fixture JVM PID is sampled from process launch through observed exit, including startup/shutdown. Windows working set, private bytes, handles and temporary bytes are finite-interval observations, not exact lifetime maxima. Heap samples are inside main at source/page checkpoints; no forced GC or memory-pool peak summation.'
    temporaryDiskScope = 'Isolated java.io.tmpdir plus active atomic output temporary file; final PDF excluded.'
    cases = $results.ToArray()
}
$summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $runDirectory 'report.json') -Encoding utf8
[IO.File]::WriteAllText((Join-Path $measurementRoot 'capacity-success-directory.txt'), $runDirectory + [Environment]::NewLine,
    [Text.UTF8Encoding]::new($false))
$summary | ConvertTo-Json -Depth 10
