# SPDX-License-Identifier: Apache-2.0
# Isolated process-tree regression; does not launch Gradle or the capacity fixture.
$ErrorActionPreference = 'Stop'
$sourceScript = Join-Path $PSScriptRoot 'measure_report_stress.ps1'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('export4j-stress-cleanup-' + [Guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path (Join-Path $testRoot 'scripts'))
Copy-Item -LiteralPath $sourceScript -Destination (Join-Path $testRoot 'scripts/measure_report_stress.ps1')
$workerScript = Join-Path $testRoot 'worker.ps1'
@'
param([string]$Role, [string]$Directory, [string]$Run, [string]$Mode)
$ErrorActionPreference = 'Stop'
if ($Role -eq 'child') {
    @{pid=$PID;started=(Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('O')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Directory 'child.json')
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline) { [Threading.Thread]::Sleep(25) }
    exit 0
}
$info = [Diagnostics.ProcessStartInfo]::new((Get-Process -Id $PID).Path)
$info.UseShellExecute = $false; $info.CreateNoWindow = $true
foreach ($argument in @('-NoLogo', '-NoProfile', '-NonInteractive', '-File', $PSCommandPath, '-Role', 'child', '-Directory', $Directory)) { $info.ArgumentList.Add($argument) }
$child = [Diagnostics.Process]::Start($info)
$deadline = [DateTime]::UtcNow.AddSeconds(10)
while (-not (Test-Path -LiteralPath (Join-Path $Directory 'child.json'))) {
    if ([DateTime]::UtcNow -gt $deadline -or $child.HasExited) { throw 'Child did not become ready' }
    [Threading.Thread]::Sleep(10)
}
$fixture = Join-Path $Directory 'examples/build/report-stress'
[void](New-Item -ItemType Directory -Force -Path $fixture)
@{run=$Run;pid=$child.Id} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $fixture 'running.json')
Set-Content -LiteralPath (Join-Path $Directory 'ready') -Value 'ready'
if ($Mode -eq 'nonzero') { exit 43 }
$child.WaitForExit()
'@ | Set-Content -LiteralPath $workerScript -Encoding utf8

function New-ControlledProcess([string[]]$Arguments) {
    $info = [Diagnostics.ProcessStartInfo]::new((Get-Process -Id $PID).Path)
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    foreach ($argument in @('-NoLogo', '-NoProfile', '-NonInteractive', '-File', $workerScript) + $Arguments) { $info.ArgumentList.Add($argument) }
    return [Diagnostics.Process]::Start($info)
}

# Replace only process creation and the sampling wait in the copied script's command scope.
# The production timeout, process discovery, identity checks and finally block are unchanged.
function Start-Process {
    param([string]$FilePath, [string[]]$ArgumentList, [string]$WorkingDirectory, [string]$WindowStyle,
          [switch]$PassThru, [string]$RedirectStandardOutput, [string]$RedirectStandardError)
    $context = $global:export4jStressCleanupTest
    $match = [regex]::Match(($ArgumentList -join ' '), 'export4jStressRun=([0-9a-f]+)')
    if (-not $match.Success) { throw 'Expected measurement command was not intercepted' }
    $context.launched = New-ControlledProcess -Arguments @('-Role', 'root', '-Directory', $WorkingDirectory, '-Run', $match.Groups[1].Value, '-Mode', $context.mode)
    $context.root = Get-Process -Id $context.launched.Id
    [void]$context.root.Handle
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while (-not (Test-Path -LiteralPath (Join-Path $WorkingDirectory 'ready'))) {
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Controlled tree did not become ready' }
        [Threading.Thread]::Sleep(10)
    }
    $identity = Get-Content -LiteralPath (Join-Path $WorkingDirectory 'child.json') -Raw | ConvertFrom-Json
    $context.child = Get-Process -Id $identity.pid
    [void]$context.child.Handle
    return $context.launched
}
function Start-Sleep {
    param([int]$Milliseconds)
    if ($global:export4jStressCleanupTest.mode -eq 'sampling') { throw 'simulated sample failure' }
    [Threading.Thread]::Sleep($Milliseconds)
}

$results = [Collections.Generic.List[object]]::new()
$sentinel = $null
try {
    $sentinelDirectory = Join-Path $testRoot 'sentinel'
    [void](New-Item -ItemType Directory -Path $sentinelDirectory)
    $sentinel = New-ControlledProcess -Arguments @('-Role', 'child', '-Directory', $sentinelDirectory)
    [void]$sentinel.Handle
    foreach ($mode in @('timeout', 'sampling', 'nonzero')) {
        $directory = Join-Path $testRoot $mode
        [void](New-Item -ItemType Directory -Path (Join-Path $directory 'scripts'))
        Copy-Item -LiteralPath $sourceScript -Destination (Join-Path $directory 'scripts/measure_report_stress.ps1')
        $global:export4jStressCleanupTest = @{mode=$mode;root=$null;child=$null}
        try {
            $failure = $null
            $timeout = if ($mode -eq 'timeout') { 2 } else { 15 }
            try { & (Join-Path $directory 'scripts/measure_report_stress.ps1') -TimeoutSeconds $timeout -SampleMilliseconds 25 }
            catch { $failure = $_.Exception.Message }
            $expected = switch ($mode) { 'timeout' { 'Stress build exceeded 2 seconds' }; 'sampling' { 'simulated sample failure' }; 'nonzero' { 'Stress build failed' } }
            if (-not $failure -or -not $failure.StartsWith($expected)) { throw "Unexpected $mode failure: $failure" }
            $context = $global:export4jStressCleanupTest
            if (-not $context.root -or -not $context.child) { throw 'Controlled processes were not captured' }
            if (-not $context.root.WaitForExit(5000) -or -not $context.child.WaitForExit(5000)) { throw "Owned process leaked after $mode" }
            $sentinel.Refresh()
            if ($sentinel.HasExited) { throw 'Unrelated sentinel process was terminated' }
            $results.Add([pscustomobject]@{mode=$mode;expectedFailure=$true;rootTerminated=$true;childTerminated=$true;unrelatedProcessPreserved=$true})
        } finally {
            foreach ($process in @($global:export4jStressCleanupTest.root, $global:export4jStressCleanupTest.child)) {
                if (-not $process) { continue }
                try { if (-not $process.HasExited) { $process.Kill($true); [void]$process.WaitForExit(5000) } }
                finally { $process.Dispose() }
            }
        }
    }
    $results | ConvertTo-Json -Depth 4
} finally {
    if ($sentinel) {
        try { if (-not $sentinel.HasExited) { $sentinel.Kill($true); [void]$sentinel.WaitForExit(5000) } }
        finally { $sentinel.Dispose() }
    }
    Remove-Variable -Name export4jStressCleanupTest -Scope Global -ErrorAction SilentlyContinue
    $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
    $temporaryPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedTestRoot.StartsWith($temporaryPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing cleanup outside the temporary directory' }
    Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
}
