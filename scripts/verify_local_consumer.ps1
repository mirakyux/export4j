# SPDX-License-Identifier: Apache-2.0
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repositoryRoot
try {
    & .\gradlew.bat -g build/gradle-validation publishLocalBundle sbom --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Local publication failed' }
    # Keep previous outputs available to readers without letting open files block this independent run.
    $consumerBuildDirectory = 'build/validation-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
    & .\gradlew.bat -g build/gradle-validation -p verification/consumer "-PconsumerBuildDirectory=$consumerBuildDirectory" clean build run resolvedLibraries --refresh-dependencies --write-locks --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Isolated consumer verification failed' }
    $consumerAbsoluteDirectory = Join-Path (Join-Path $repositoryRoot 'verification/consumer') $consumerBuildDirectory
    [System.IO.File]::WriteAllText((Join-Path $repositoryRoot 'build/verification/consumer-build-directory.txt'), $consumerAbsoluteDirectory)
} finally {
    Pop-Location
}
