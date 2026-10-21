# SPDX-License-Identifier: Apache-2.0
# Updates only generated local fixtures in a private Word instance. Requires Microsoft Word.
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$fixtureRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot 'examples/build/docx-fields'))
$source = Join-Path $fixtureRoot 'fields.docx'
$updated = Join-Path $fixtureRoot 'word-updated.docx'
$pdf = Join-Path $fixtureRoot 'word.pdf'
$report = Join-Path $fixtureRoot 'word-report.json'
if (!(Test-Path -LiteralPath $source)) { throw 'Generate :examples:docxFields before running Word verification' }
$word = $null
$document = $null
try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $word.AutomationSecurity = 3
    # ConfirmConversions=false, ReadOnly=true, AddToRecentFiles=false.
    $document = $word.Documents.Open($source, $false, $true, $false)
    $document.Repaginate()
    $first = $document.Fields.Update()
    $document.Repaginate()
    $second = $document.Fields.Update()
    $fields = @()
    foreach ($field in $document.Fields) {
        $fields += @{ instruction = $field.Code.Text.Trim(); value = $field.Result.Text; page = $field.Result.Information(3) }
    }
    $pages = $document.ComputeStatistics(2)
    $document.SaveAs2($updated, 16, $false, '', $false)
    $document.ExportAsFixedFormat($pdf, 17)
    @{ sourceSha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant(); wordVersion = $word.Version; pages = $pages; firstUpdateError = $first; secondUpdateError = $second; fields = $fields } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $report -Encoding utf8
    Write-Output "Word verified fixture: $pages pages, $($fields.Count) native fields"
} finally {
    if ($null -ne $document) { $document.Close(0); [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($document) }
    if ($null -ne $word) { $word.Quit(0); [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($word) }
}
