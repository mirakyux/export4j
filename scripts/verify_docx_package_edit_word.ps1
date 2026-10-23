# SPDX-License-Identifier: Apache-2.0
# Saves and reopens only generated DOCX preservation fixtures in a bounded private Word instance.
[CmdletBinding()]
param(
    [switch]$Worker,
    [switch]$ExportOnly,
    [switch]$Baseline,
    [ValidateRange(30, 300)][int]$TimeoutSeconds = 90,
    [ValidatePattern('^[a-f0-9]{32}$')][string]$RunId = [guid]::NewGuid().ToString('N')
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$fixtureRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot 'examples/build/docx-package-edit'))
$source = Join-Path $fixtureRoot 'edited.docx'
$updated = Join-Path $fixtureRoot 'word-updated.docx'
$pdf = Join-Path $fixtureRoot 'word.pdf'
$report = Join-Path $fixtureRoot 'word-report.json'
if (!(Test-Path -LiteralPath $source)) { throw 'Generate :examples:docxPackageEdit before running Word verification' }
$evidenceRoot = Join-Path $repositoryRoot 'build/verification/docx-package-edit'
[void](New-Item -ItemType Directory -Path $evidenceRoot -Force)
$ownership = Join-Path $evidenceRoot "word-owned-$RunId.json"
if (!$Worker) {
    $standard = Join-Path $evidenceRoot "word-$RunId.stdout.log"
    $errors = Join-Path $evidenceRoot "word-$RunId.stderr.log"
    $executable = (Get-Command powershell.exe -ErrorAction Stop).Source
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $PSCommandPath + '"'), '-Worker', '-RunId', $RunId)
    if ($ExportOnly) { $arguments += '-ExportOnly' }
    if ($Baseline) { $arguments += '-Baseline' }
    $process = Start-Process -FilePath $executable -ArgumentList $arguments -PassThru -WindowStyle Hidden -RedirectStandardOutput $standard -RedirectStandardError $errors
    # Cache the native process handle before WaitForExit; Windows PowerShell can otherwise lose ExitCode.
    [void]$process.Handle
    if (!$process.WaitForExit($TimeoutSeconds * 1000)) {
        if (Test-Path -LiteralPath $ownership) {
            $owned = Get-Content -LiteralPath $ownership -Raw | ConvertFrom-Json
            $ownedWord = Get-Process -Id $owned.pid -ErrorAction SilentlyContinue
            if ($null -ne $ownedWord -and $ownedWord.ProcessName -eq 'WINWORD' -and $ownedWord.StartTime.ToUniversalTime().ToString('o') -eq $owned.startedUtc -and $owned.runId -eq $RunId) {
                Stop-Process -Id $owned.pid -Force
            }
        }
        if (!$process.WaitForExit(2000)) { Stop-Process -Id $process.Id -Force }
        @{ runId = $RunId; status = 'WORD_TIMEOUT'; timeoutSeconds = $TimeoutSeconds; stdout = $standard; stderr = $errors } |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidenceRoot 'word-timeout.json') -Encoding utf8
        Get-Content -LiteralPath $standard
        if ((Get-Item -LiteralPath $errors).Length -gt 0) { Get-Content -LiteralPath $errors }
        throw "Private generated-fixture Word verification exceeded $TimeoutSeconds seconds; only its recorded Word/worker processes were cleaned up"
    }
    Get-Content -LiteralPath $standard
    if ((Get-Item -LiteralPath $errors).Length -gt 0) { Get-Content -LiteralPath $errors }
    if ($process.ExitCode -ne 0) { throw "Private Word worker failed with exit code $($process.ExitCode)" }
    exit 0
}

$existingWordIds = @(Get-Process WINWORD -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
$word = $null
$document = $null
$ownsWord = $false
Write-Output 'Creating private Word instance'
try {
    $word = New-Object -ComObject Word.Application
    $newWordProcesses = @(Get-Process WINWORD -ErrorAction SilentlyContinue | Where-Object { $existingWordIds -notcontains $_.Id })
    if ($newWordProcesses.Count -ne 1 -or $word.Documents.Count -ne 0) { throw 'Word did not supply one empty private process; existing user instances will not be changed' }
    $ownedWord = $newWordProcesses[0]
    $wordProcessId = $ownedWord.Id
    $ownsWord = $true
    @{ runId = $RunId; pid = $wordProcessId; startedUtc = $ownedWord.StartTime.ToUniversalTime().ToString('o'); source = $source } |
        ConvertTo-Json | Set-Content -LiteralPath $ownership -Encoding utf8
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $word.AutomationSecurity = 3
    if ($Baseline) {
        Write-Output 'Creating a plain private Word baseline document'
        $document = $word.Documents.Add()
        $document.Content.Text = 'Export4j plain Word baseline'
        Write-Output 'Saving plain Word baseline'
        $document.SaveAs2((Join-Path $fixtureRoot 'baseline.docx'), 12, $false, '', $false)
        Write-Output 'Exporting plain Word baseline'
        $document.ExportAsFixedFormat((Join-Path $fixtureRoot 'baseline.pdf'), 17)
        @{ runId = $RunId; saved = $true; pdfExported = $true; wordVersion = $word.Version } | ConvertTo-Json |
            Set-Content -LiteralPath (Join-Path $evidenceRoot 'word-baseline.json') -Encoding utf8
        return
    }
    # ConfirmConversions=false, ReadOnly=true, AddToRecentFiles=false.
    Write-Output 'Opening generated edited.docx read-only'
    $document = $word.Documents.Open($source, $false, $true, $false)
    # Inspect, save, reopen and export separately; do not refresh the preserved TOC/field caches.
    function Read-FixtureState($doc) {
        return @{ text = $doc.Content.Text; comments = $doc.Comments.Count; revisions = $doc.Revisions.Count;
                  controls = $doc.ContentControls.Count; forms = $doc.FormFields.Count; fields = $doc.Fields.Count;
                  primaryHeader = $doc.Sections.Item(1).Headers.Item(1).Range.Text;
                  firstHeader = $doc.Sections.Item(1).Headers.Item(2).Range.Text;
                  evenHeader = $doc.Sections.Item(1).Headers.Item(3).Range.Text }
    }
    $before = Read-FixtureState $document
    $initial = @{ sourceSha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant();
                  wordVersion = $word.Version; before = $before; runId = $RunId; savedAndReopened = $false; pdfExported = $false }
    $initial | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidenceRoot 'word-before-save.json') -Encoding utf8
    if ($ExportOnly) {
        Write-Output 'Exporting generated fixture without a save operation'
        $document.ExportAsFixedFormat($pdf, 17)
        $initial.pdfExported = $true
        $initial | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $report -Encoding utf8
        return
    }
    Write-Output 'Saving generated DOCX copy'
    $document.SaveAs2($updated, 12, $false, '', $false)
    $document.Close(0)
    [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($document)
    $document = $null
    Write-Output 'Reopening saved generated DOCX copy'
    $document = $word.Documents.Open($updated, $false, $true, $false)
    $after = Read-FixtureState $document
    $result = @{ sourceSha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant();
                 savedSha256 = (Get-FileHash -LiteralPath $updated -Algorithm SHA256).Hash.ToLowerInvariant();
                 wordVersion = $word.Version; before = $before; after = $after; runId = $RunId; savedAndReopened = $true }
    $result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $report -Encoding utf8
    Write-Output 'Exporting generated fixture PDF'
    $document.ExportAsFixedFormat($pdf, 17)
    $result.pdfExported = $true
    $result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $report -Encoding utf8
    Write-Output "Word package preservation: saved and reopened, $($after.comments) comments, $($after.revisions) revisions"

} finally {
    if ($null -ne $document) { $document.Close(0); [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($document) }
    if ($null -ne $word) {
        if ($ownsWord) { $word.Quit(0) }
        [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($word)
    }
}
