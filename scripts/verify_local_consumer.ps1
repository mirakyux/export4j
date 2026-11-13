# SPDX-License-Identifier: Apache-2.0
param(
    [string]$VerificationPython = 'python',
    [string]$RepositoryDirectory = ''
)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repositoryRoot
try {
    $verificationPythonCommand = $VerificationPython
    if (Test-Path -LiteralPath $VerificationPython) {
        $verificationPythonCommand = (Resolve-Path -LiteralPath $VerificationPython).Path
    }
    $publicationRepositoryDirectory = if ([string]::IsNullOrWhiteSpace($RepositoryDirectory)) {
        Join-Path $repositoryRoot 'build/repository'
    } elseif ([System.IO.Path]::IsPathRooted($RepositoryDirectory)) {
        [System.IO.Path]::GetFullPath($RepositoryDirectory)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $RepositoryDirectory))
    }
    & .\gradlew.bat -g build/gradle-validation "-Pexport4jRepositoryDirectory=$publicationRepositoryDirectory" "-Pexport4jVerificationPython=$verificationPythonCommand" publishLocalBundle sbom --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Local publication failed' }
    # Keep previous outputs available to readers without letting open files block this independent run.
    $consumerBuildDirectory = 'build/validation-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
    & .\gradlew.bat -g build/gradle-validation -p verification/consumer "-PconsumerBuildDirectory=$consumerBuildDirectory" "-Pexport4jRepositoryDirectory=$publicationRepositoryDirectory" clean build run math fontCache contentBlocks richContent easyExport resolvedLibraries --refresh-dependencies --write-locks --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Isolated consumer verification failed' }
    $consumerAbsoluteDirectory = Join-Path (Join-Path $repositoryRoot 'verification/consumer') $consumerBuildDirectory
    $pomBuildDirectory = $consumerBuildDirectory + '-pom-only'
    & .\gradlew.bat -g build/gradle-validation -p verification/consumer "-PconsumerBuildDirectory=$pomBuildDirectory" "-Pexport4jRepositoryDirectory=$publicationRepositoryDirectory" -Pexport4jPomOnly=true clean build run math fontCache contentBlocks richContent easyExport resolvedLibraries --refresh-dependencies --write-locks --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'POM-only isolated consumer verification failed' }
    $pomAbsoluteDirectory = Join-Path (Join-Path $repositoryRoot 'verification/consumer') $pomBuildDirectory
    [System.IO.File]::WriteAllText((Join-Path $repositoryRoot 'build/verification/consumer-build-directory.txt'), $consumerAbsoluteDirectory)
    [System.IO.File]::WriteAllText((Join-Path $repositoryRoot 'build/verification/consumer-pom-only-build-directory.txt'), $pomAbsoluteDirectory)
} finally {
    Pop-Location
}
