# SPDX-License-Identifier: Apache-2.0
# Updates only generated local fixtures in a private Word instance. Requires Microsoft Word.
[CmdletBinding()]
param(
    [switch]$Worker,
    [ValidateRange(30, 300)][int]$TimeoutSeconds = 90,
    [ValidatePattern('^[a-f0-9]{32}$')][string]$RunId = [guid]::NewGuid().ToString('N')
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$fixtureRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot 'examples/build/column-spans'))
$source = Join-Path $fixtureRoot 'spans.docx'
$updated = Join-Path $fixtureRoot 'word-updated.docx'
$pdf = Join-Path $fixtureRoot 'word.pdf'
$report = Join-Path $fixtureRoot 'word-report.json'
if (!(Test-Path -LiteralPath $source)) { throw 'Generate :examples:columnSpans before running Word verification' }
$evidenceRoot = Join-Path $repositoryRoot 'build/verification/column-spans'
[void](New-Item -ItemType Directory -Path $evidenceRoot -Force)
$ownership = Join-Path $evidenceRoot "word-owned-$RunId.json"
if (!$Worker) {
    $standard = Join-Path $evidenceRoot "word-$RunId.stdout.log"
    $errors = Join-Path $evidenceRoot "word-$RunId.stderr.log"
    $executable = (Get-Command powershell.exe -ErrorAction Stop).Source
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $PSCommandPath + '"'), '-Worker', '-RunId', $RunId)
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
    # ConfirmConversions=false, ReadOnly=true, AddToRecentFiles=false.
    Write-Output 'Opening generated spans.docx read-only'
    $document = $word.Documents.Open($source, $false, $true, $false)
    Write-Output 'Repaginating generated fixture'
    $document.Repaginate()
    Write-Output 'Updating native fields (first pass)'
    $first = $document.Fields.Update()
    $document.Repaginate()
    Write-Output 'Updating native fields (second pass)'
    $second = $document.Fields.Update()
    $fields = @()
    foreach ($field in $document.Fields) {
        $fields += @{ instruction = $field.Code.Text.Trim(); value = $field.Result.Text; page = $field.Result.Information(3) }
    }
    Write-Output 'Computing final page count'
    $pages = $document.ComputeStatistics(2)
    $result = @{ sourceSha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant(); wordVersion = $word.Version;
                 pages = $pages; firstUpdateError = $first; secondUpdateError = $second; fields = $fields; runId = $RunId }
    $result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidenceRoot 'word-before-save.json') -Encoding utf8
    Write-Output 'Exporting generated fixture PDF'
    $document.ExportAsFixedFormat($pdf, 17)
    Write-Output 'Saving generated DOCX copy with explicit wdFormatXMLDocument (12)'
    $document.SaveAs2($updated, 12, $false, '', $false)
    $result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $report -Encoding utf8
    Write-Output "Word column spans: $pages pages, $($fields.Count) native fields"
} finally {
    if ($null -ne $document) { $document.Close(0); [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($document) }
    if ($null -ne $word) {
        if ($ownsWord) { $word.Quit(0) }
        [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($word)
    }
}
