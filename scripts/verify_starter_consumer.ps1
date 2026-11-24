# SPDX-License-Identifier: Apache-2.0
param(
    [string]$Version = '0.1.0-SNAPSHOT',
    [string]$RepositoryDirectory = '',
    [string]$OutputDirectory = '',
    [ValidateSet('full', 'pdf', 'docx')][string]$Target = 'full'
)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?$') { throw 'Use an explicit publication version' }
function Resolve-VerificationPath([string]$Value, [string]$Default) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $Default)) }
    if ([System.IO.Path]::IsPathRooted($Value)) { return [System.IO.Path]::GetFullPath($Value) }
    return [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $Value))
}
function Read-ArtifactHashes([string]$Path) {
    $hashes = @{}
    foreach ($line in (Get-Content -LiteralPath $Path)) {
        $fields = $line.Split("`t")
        if ($fields.Count -ne 3 -or $fields[0] -notmatch '^[^:]+:[^:]+:[^:]+$' -or
            [string]::IsNullOrWhiteSpace($fields[1]) -or $fields[2] -notmatch '^[0-9a-f]{64}$' -or
            $hashes.ContainsKey($fields[0])) { throw 'Malformed or duplicate runtime artifact hash' }
        $hashes[$fields[0]] = $fields[2]
    }
    if ($hashes.Count -eq 0) { throw 'Runtime artifact hashes are missing' }
    # Gradle metadata may cache SNAPSHOT names while POM resolution uses timestamped names.
    # Coordinates and actual bytes must match regardless of that local filename difference.
    return @($hashes.Keys | Sort-Object | ForEach-Object { "$_`t$($hashes[$_])" })
}
$publicationRepositoryDirectory = Resolve-VerificationPath $RepositoryDirectory 'build/repository'
$defaultEvidence = 'build/verification/starter-single-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$evidenceDirectory = Resolve-VerificationPath $OutputDirectory $defaultEvidence
if (Test-Path -LiteralPath $evidenceDirectory) { throw 'Choose a new evidence directory; previous verification is preserved' }
New-Item -ItemType Directory -Path $evidenceDirectory | Out-Null
Push-Location $repositoryRoot
try {
    $routes = @{}
    foreach ($route in @('gradle', 'pom-only')) {
        $routeDirectory = Join-Path $evidenceDirectory $route
        New-Item -ItemType Directory -Path $routeDirectory | Out-Null
        $arguments = @('-g', 'build/gradle-starter-validation', '-p', 'verification/starter-consumer',
            "-PconsumerBuildDirectory=$routeDirectory", "-Pexport4jRepositoryDirectory=$publicationRepositoryDirectory",
            "-Pexport4jVersion=$Version", "-PstarterTarget=$Target", 'build', 'easyExport', 'resolvedLibraries',
            '--refresh-dependencies', '--write-locks', '--no-daemon', '--console=plain')
        if ($route -eq 'pom-only') { $arguments += '-Pexport4jPomOnly=true' }
        & .\gradlew.bat @arguments *> (Join-Path $evidenceDirectory "$route.log")
        $routeExit = $LASTEXITCODE
        if ($routeExit -ne 0) {
            Get-Content (Join-Path $evidenceDirectory "$route.log") -Tail 30
            throw "Starter-only $route consumer failed (exit $routeExit)"
        }
        $audit = Get-Content (Join-Path $routeDirectory 'runtime-audit.json') -Raw | ConvertFrom-Json
        if ($audit.directDependencies -ne 1 -or $audit.bomDeclared -ne $false -or $audit.duplicateClassProviders -ne 0) {
            throw 'Starter-only dependency audit did not complete'
        }
        $routes[$route] = @{ directory = $routeDirectory; audit = $audit }
    }
    $gradleHashes = Read-ArtifactHashes (Join-Path $routes['gradle'].directory 'resolved-library-sha256.txt')
    $pomHashes = Read-ArtifactHashes (Join-Path $routes['pom-only'].directory 'resolved-library-sha256.txt')
    if (Compare-Object $gradleHashes $pomHashes) { throw 'Gradle metadata and POM-only routes resolved different runtime artifacts' }
    $result = @{ version = $Version; target = $Target; status = 'PASS'; routes = $routes; runtimeArtifactsEqual = $true }
    $result | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $evidenceDirectory 'verification.json') -Encoding utf8
    Write-Output "Verified one declared starter coordinate through both metadata routes: $evidenceDirectory"
} finally {
    Pop-Location
}
