# SPDX-License-Identifier: Apache-2.0
# Uses only the generated XML-section fixture in a private Microsoft Word instance.
param([switch]$ExportPdf)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$fixtureRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot 'examples/build/xml-sections'))
$source = Join-Path $fixtureRoot 'report.docx'
$pdf = Join-Path $fixtureRoot 'word.pdf'
$report = Join-Path $fixtureRoot 'word-report.json'
if (!(Test-Path -LiteralPath $source)) { throw 'Generate :examples:xmlSections before Word verification' }
$word = $null
$document = $null
try {
    Write-Output 'Creating private Word instance'
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $word.AutomationSecurity = 3
    Write-Output 'Opening generated DOCX'
    $document = $word.Documents.Open($source, $false, $true, $false)
    Write-Output 'DOCX opened'

    for ($pass = 0; $pass -lt 2; $pass++) {
        $document.Repaginate()
        [void]$document.Fields.Update()
        foreach ($section in $document.Sections) {
            [void]$section.Headers.Item(1).Range.Fields.Update()
            [void]$section.Footers.Item(1).Range.Fields.Update()
        }
    }
    Write-Output 'Fields updated'

    $sections = @()
    foreach ($section in $document.Sections) {
        $header = $section.Headers.Item(1)
        $footer = $section.Footers.Item(1)
        $sections += @{
            headerText = $header.Range.Text
            headerInlineImages = $header.Range.InlineShapes.Count
            footerTables = $footer.Range.Tables.Count
            footerFields = $footer.Range.Fields.Count
            pageStart = $section.Footers.Item(1).PageNumbers.StartingNumber
        }
    }
    $pages = $document.ComputeStatistics(2)
    $result = @{ sourceSha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant(); wordVersion = $word.Version; pages = $pages; sections = $sections; pdfExported = $false }
    $result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $report -Encoding utf8
    if ($ExportPdf) {
        Write-Output 'Exporting Word PDF'
        $document.ExportAsFixedFormat($pdf, 17)
        $result.pdfExported = $true
        $result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $report -Encoding utf8
    }

    Write-Output "Word page regions: $pages pages, $($sections.Count) sections"
} finally {
    if ($null -ne $document) { $document.Close(0); [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($document) }
    if ($null -ne $word) { $quitSaveChanges = 0; $word.Quit([ref]$quitSaveChanges); [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($word) }
}
