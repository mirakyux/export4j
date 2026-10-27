# SPDX-License-Identifier: Apache-2.0
# Opens only the generated DOCX fixture in a private Microsoft Word instance.
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$fixtureRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot 'examples/build/embedded-zip-recovery/docx'))
$source = Join-Path $fixtureRoot 'original.docx'
$report = Join-Path $fixtureRoot 'word-report-current.json'
if (!(Test-Path -LiteralPath $source)) { throw 'Generate :examples:embeddedZipRecovery before Word verification' }

$word = $null
$document = $null
$content = $null
try {
    Write-Output 'Starting private Word instance'
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $word.AutomationSecurity = 3
    Write-Output 'Opening generated DOCX'
    $document = $word.Documents.Open($source, $false, $true, $false)
    Write-Output 'Checking visible text'
    $content = $document.Content
    $visibleText = $content.Text
    if (!$visibleText.Contains('ORIGINAL EMBEDDED-ZIP') -or !$visibleText.Contains('UNCHANGED-CONTROL')) {
        throw 'Word did not display the expected generated text'
    }
    @{
        sourceSha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
        wordVersion = $word.Version
    } | ConvertTo-Json | Set-Content -LiteralPath $report -Encoding utf8
    Write-Output 'Word opened generated embedded DOCX and displayed expected text'
} finally {
    if ($null -ne $content) { [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($content) }
    if ($null -ne $document) { $document.Close(0); [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($document) }
    if ($null -ne $word) { $quitSaveChanges = 0; $word.Quit([ref]$quitSaveChanges); [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($word) }
}
