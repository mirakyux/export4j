# SPDX-License-Identifier: Apache-2.0
param(
    [Parameter(Mandatory = $true)][string]$InputPath,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [ValidateRange(30, 600)][int]$TimeoutSeconds = 120,
    [switch]$SkipPdf,
    [switch]$Worker,
    [string]$BaselineWordPids = ''
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $InputPath).Path
$destination = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($destination) | Out-Null
$ownershipFile = Join-Path $destination 'word-ownership.json'
$resultFile = Join-Path $destination 'word-acceptance.json'
$stageFile = Join-Path $destination 'word-stage.json'
function Write-WordStage([string]$stage) { @{ stage = $stage } | ConvertTo-Json | Set-Content -LiteralPath $stageFile -Encoding utf8 }

function Initialize-WordPdfExporter {
    $assemblyPaths = @{}
    foreach ($name in @('Microsoft.Office.Interop.Word', 'office')) {
        $candidates = @(
            (Join-Path ([Environment]::GetFolderPath('Windows')) "assembly/GAC_MSIL/$name"),
            (Join-Path ([Environment]::GetFolderPath('Windows')) "Microsoft.NET/assembly/GAC_MSIL/$name")
        )
        $assembly = @($candidates | Where-Object { Test-Path -LiteralPath $_ } |
            ForEach-Object { Get-ChildItem -LiteralPath $_ -Recurse -Filter '*.dll' } |
            Sort-Object FullName -Descending | Select-Object -First 1)
        if ($assembly.Count -ne 1) { throw "Word PDF verification requires the installed Office interop assembly: $name" }
        $assemblyPaths[$name] = $assembly[0].FullName
    }
    # Invoke the typed Office API with its ref VARIANT extension argument.
    # PowerShell late binding can block here even for Word's own plain document.
    Add-Type -ReferencedAssemblies @($assemblyPaths['Microsoft.Office.Interop.Word'], $assemblyPaths['office']) -TypeDefinition @'
using Word = Microsoft.Office.Interop.Word;
public static class Export4jWordPdf {
    public static void Export(object source, string path) {
        Word.Document document = (Word.Document)source;
        object extension = System.Type.Missing;
        document.ExportAsFixedFormat(path, Word.WdExportFormat.wdExportFormatPDF,
            false, Word.WdExportOptimizeFor.wdExportOptimizeForPrint,
            Word.WdExportRange.wdExportAllDocument, 1, 1,
            Word.WdExportItem.wdExportDocumentContent, true, true,
            Word.WdExportCreateBookmarks.wdExportCreateHeadingBookmarks,
            true, true, false, ref extension);
    }
}
'@
}

function Write-WordPages($value, [string]$savedPath) {
    Add-Type -AssemblyName System.Drawing
    $value.Repaginate()
    $pageCount = $value.ComputeStatistics(2)
    $pages = $value.ActiveWindow.Panes.Item(1).Pages
    if ($pages.Count -ne $pageCount -or $pageCount -lt 1) { throw 'Word page rendering and pagination counts differ' }
    $rendered = @()
    for ($index = 1; $index -le $pageCount; $index++) {
        $emf = Join-Path $destination "word-page-$index.emf"
        $png = Join-Path $destination "word-page-$index.png"
        [IO.File]::WriteAllBytes($emf, [byte[]]$pages.Item($index).EnhMetaFileBits)
        $image = [Drawing.Image]::FromFile($emf)
        try {
            $width = 1240
            $height = [int][Math]::Round($image.Height * $width / $image.Width)
            $bitmap = New-Object Drawing.Bitmap($width, $height)
            try {
                $graphics = [Drawing.Graphics]::FromImage($bitmap)
                try {
                    $graphics.Clear([Drawing.Color]::White)
                    $graphics.DrawImage($image, 0, 0, $width, $height)
                    $bitmap.Save($png, [Drawing.Imaging.ImageFormat]::Png)
                }
                finally { $graphics.Dispose() }
            }
            finally { $bitmap.Dispose() }
        }
        finally { $image.Dispose() }
        $rendered += @{
            page = $index; emf = [IO.Path]::GetFileName($emf); png = [IO.Path]::GetFileName($png)
            emfSha256 = (Get-FileHash -LiteralPath $emf -Algorithm SHA256).Hash.ToLowerInvariant()
            pngSha256 = (Get-FileHash -LiteralPath $png -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    @{
        renderer = 'Word Page.EnhMetaFileBits'; pages = $pageCount; files = $rendered
        sourceSha256 = (Get-FileHash -LiteralPath $savedPath -Algorithm SHA256).Hash.ToLowerInvariant()
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $destination 'word-page-rendering.json') -Encoding utf8
    return $pageCount
}

if (-not $Worker) {
    $baseline = @(Get-Process -Name WINWORD -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
    $started = Get-Date
    if (Test-Path -LiteralPath $ownershipFile) { Remove-Item -LiteralPath $ownershipFile }
    if (Test-Path -LiteralPath $resultFile) { Remove-Item -LiteralPath $resultFile }
    function Quote-Argument([string]$value) { return '"' + $value.Replace('"', '\"') + '"' }
    $arguments = @('-NoProfile', '-Sta', '-File', (Quote-Argument $PSCommandPath), '-InputPath', (Quote-Argument $source), '-OutputDirectory', (Quote-Argument $destination), '-Worker', '-BaselineWordPids', (Quote-Argument ($baseline -join ',')))
    if ($SkipPdf) { $arguments += '-SkipPdf' }
    $child = Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $destination 'word-worker.stdout.log') -RedirectStandardError (Join-Path $destination 'word-worker.stderr.log')
    $childHandle = $child.Handle
    try {
        if (-not $child.WaitForExit($TimeoutSeconds * 1000)) { throw "Word verification exceeded $TimeoutSeconds seconds" }
        $child.Refresh()
        if ($child.ExitCode -ne 0) { throw "Word verification worker failed: $($child.ExitCode)" }
        if (-not (Test-Path -LiteralPath $resultFile)) { throw 'Word verification did not produce a receipt' }
        if (-not (Get-Content -LiteralPath $resultFile -Raw | ConvertFrom-Json).passed) { throw 'Word verification receipt reports a failure' }
        Get-Content -LiteralPath $resultFile
    }
    finally {
        if (-not $child.HasExited) { Stop-Process -Id $child.Id -Force -ErrorAction SilentlyContinue }
        if (Test-Path -LiteralPath $ownershipFile) {
            $owned = Get-Content -LiteralPath $ownershipFile -Raw | ConvertFrom-Json
            $process = Get-Process -Id $owned.wordPid -ErrorAction SilentlyContinue
            if ($process -and $baseline -notcontains $process.Id -and $process.ProcessName -eq 'WINWORD' -and $process.StartTime -ge $started.AddSeconds(-2)) {
                Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            }
        }
    }
    exit
}

$word = $null
$document = $null
$reopened = $null
$identityDocument = $null
$wordPid = $null
try {
    if (-not $SkipPdf) { Initialize-WordPdfExporter }
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class Export4jWordProcess {
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
}
'@
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $word.AutomationSecurity = 3
    # Word exposes the HWND on its document window, rather than on Application in every version.
    $identityDocument = $word.Documents.Add()
    [uint32]$createdPid = 0
    [Export4jWordProcess]::GetWindowThreadProcessId([IntPtr]$word.ActiveWindow.Hwnd, [ref]$createdPid) | Out-Null
    $original = @($BaselineWordPids.Split(',', [StringSplitOptions]::RemoveEmptyEntries) | ForEach-Object { [int]$_ })
    if ($createdPid -eq 0 -or $original -contains [int]$createdPid) { throw 'A private Word process could not be established; existing instances are preserved' }
    $wordPid = [int]$createdPid
    @{ wordPid = $wordPid; workerPid = $PID } | ConvertTo-Json | Set-Content -LiteralPath $ownershipFile -Encoding utf8
    $identityDocument.Close(0)
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($identityDocument) | Out-Null
    $identityDocument = $null
    $saved = Join-Path $destination 'word-saved.docx'
    $pdf = Join-Path $destination 'word-rendered.pdf'
    Copy-Item -LiteralPath $source -Destination $saved
    $document = $word.Documents.Open($saved, $false, $false, $false)
    if ($document.ReadOnly) { throw 'The private Word copy opened read-only' }
    $repeated = $document.SelectContentControlsByTag('repeat')
    $repeatingItems = if ($repeated.Count -eq 1) { $repeated.Item(1).RepeatingSectionItems.Count } else { 0 }
    $before = @{ sections = $document.Sections.Count; comments = $document.Comments.Count; controls = $document.ContentControls.Count; repeatingSectionItems = $repeatingItems; shapes = $document.Shapes.Count; revisions = $document.Revisions.Count; contents = $document.TablesOfContents.Count }
    $inventory = @()
    foreach ($control in $document.ContentControls) { $inventory += @{ type = $control.Type; tag = $control.Tag; title = $control.Title } }
    @{ counts = $before; controls = $inventory } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $destination 'word-native-inventory.json') -Encoding utf8
    if ($before.sections -ne 2 -or $before.comments -ne 1 -or $before.controls -lt 9 -or $before.repeatingSectionItems -ne 2 -or $before.shapes -ne 2 -or $before.contents -ne 1) { throw "Word native object counts differ: $($before | ConvertTo-Json -Compress)" }
    $expectedTypes = @{ plain = 1; rich = 0; checkbox = 8; choice = 4; combo = 3; date = 6; picture = 2; group = 7; repeat = 9 }
    foreach ($entry in $expectedTypes.GetEnumerator()) {
        $found = $document.SelectContentControlsByTag($entry.Key)
        if ($found.Count -ne 1 -or $found.Item(1).Type -ne $entry.Value) { throw "Word control kind differs: $($entry.Key)" }
    }
    function Assert-LegacyValues($value) {
        $forms = @{}
        foreach ($form in $value.FormFields) { $forms[$form.Name] = $form }
        if ($forms.Count -ne 3 -or $forms['legacy-text'].Result -ne 'FORM-READY' -or $forms['legacy-text'].TextInput.Default -ne 'DEFAULT' -or
                $forms['legacy-list'].Result -ne 'Second' -or $forms['legacy-list'].DropDown.Default -ne 1 -or $forms['legacy-check'].CheckBox.Value -ne $true) {
            throw 'Word changed a legacy form current value or its separate default'
        }
    }
    Assert-LegacyValues $document
    $document.TrackRevisions = $false
    Write-WordStage 'edit-control'
    $plain = $document.SelectContentControlsByTag('plain')
    if ($plain.Count -ne 1) { throw 'Editable plain content control missing' }
    $plain.Item(1).Range.Text = 'WORD-EDITED-NAME'
    Write-WordStage 'edit-textbox'
    $changedTextbox = $false
    foreach ($shape in $document.Shapes) {
        if ($shape.TextFrame.HasText -ne 0 -and $shape.TextFrame.TextRange.Text.Contains('TEXTBOX-READY')) {
            $find = $shape.TextFrame.TextRange.Find
            $find.ClearFormatting()
            $find.Replacement.ClearFormatting()
            $changedTextbox = $find.Execute('TEXTBOX-READY', $false, $false, $false, $false, $false, $true, 1, $false, 'WORD-TEXTBOX-READY', 2)
        }
    }
    if (-not $changedTextbox) { throw 'Word could not edit the real textbox content' }
    Write-WordStage 'update-contents'
    foreach ($contents in $document.TablesOfContents) { $contents.Update() }
    Write-WordStage 'update-fields'
    $script:computedFieldsUpdated = 0
    $script:inputFieldsPreserved = 0
    function Update-ComputedFields($range) {
        foreach ($field in $range.Fields) {
            if ($field.Code.Text.Trim() -match '^FORM(TEXT|CHECKBOX|DROPDOWN)(\s|$)') { $script:inputFieldsPreserved++; continue }
            if (-not $field.Update()) { throw "Word computed field update failed: $($field.Code.Text.Trim())" }
            $script:computedFieldsUpdated++
        }
    }
    Update-ComputedFields $document.Content
    foreach ($section in $document.Sections) {
        foreach ($band in $section.Headers) { if ($band.Exists) { Update-ComputedFields $band.Range } }
        foreach ($band in $section.Footers) { if ($band.Exists) { Update-ComputedFields $band.Range } }
    }
    if ($script:inputFieldsPreserved -ne 3 -or $script:computedFieldsUpdated -lt 1) { throw 'Expected calculated fields and three preserved user input fields' }
    Write-WordStage 'save-docx'
    $document.Save()
    $document.Close(0)
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($document) | Out-Null
    $document = $null
    Write-WordStage 'reopen-docx'
    $reopened = $word.Documents.Open($saved, $false, $true, $false)
    $savedRepeated = $reopened.SelectContentControlsByTag('repeat')
    $savedRepeatingItems = if ($savedRepeated.Count -eq 1) { $savedRepeated.Item(1).RepeatingSectionItems.Count } else { 0 }
    $after = @{ sections = $reopened.Sections.Count; comments = $reopened.Comments.Count; controls = $reopened.ContentControls.Count; repeatingSectionItems = $savedRepeatingItems; shapes = $reopened.Shapes.Count; contents = $reopened.TablesOfContents.Count }
    if ($after.sections -ne 2 -or $after.comments -ne 1 -or $after.controls -lt 9 -or $after.repeatingSectionItems -ne 2 -or $after.shapes -ne 2 -or $after.contents -ne 1 -or -not $reopened.Content.Text.Contains('WORD-EDITED-NAME')) { throw 'Word saved/reopened native state differs' }
    $savedTextbox = $false
    foreach ($shape in $reopened.Shapes) {
        if ($shape.TextFrame.HasText -ne 0 -and $shape.TextFrame.TextRange.Text.Contains('WORD-TEXTBOX-READY')) {
            if (-not $shape.TextFrame.TextRange.Text.Contains('Box table') -or $shape.TextFrame.TextRange.Text.Contains('TEXTBOX-OLD')) { throw 'Word saved textbox lost its edited blocks or real table' }
            $savedTextbox = $true
        }
    }
    if (-not $savedTextbox -or -not $reopened.Content.Text.Contains('DEEP-READY') -or $reopened.Content.Text.Contains('DEEP-OLD') -or $reopened.Content.Text.Contains('READY-NAME') -or -not $reopened.Comments.Item(1).Range.Text.Contains('COMMENT-READY: a real editable comment range.')) { throw 'Word saved/reopened edited text differs' }
    Assert-LegacyValues $reopened
    Write-WordStage 'render-native-pages'
    $after.pages = Write-WordPages $reopened $saved
    $receipt = @{ passed = [bool]$SkipPdf; before = $before; after = $after; actualControlEdit = $true; actualTextboxEdit = $true; deepTableTextPreserved = $true; commentTextPreserved = $true; legacyCurrentAndDefaultPreserved = $true; computedFieldsUpdated = $script:computedFieldsUpdated; inputFieldsPreserved = $script:inputFieldsPreserved; savedAndReopened = $true; nativePagesRendered = $true; wordVersion = $word.Version; wordBuild = $word.Build; pdfRequested = -not [bool]$SkipPdf; pdfExported = $false }
    $receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $resultFile -Encoding utf8
    if (-not $SkipPdf) {
        Write-WordStage 'export-pdf'
        [Export4jWordPdf]::Export($reopened, $pdf)
        if (-not (Test-Path -LiteralPath $pdf)) { throw 'Word did not produce a PDF' }
        $receipt.passed = $true
        $receipt.pdfExported = $true
        $receipt.pdfExportMethod = 'Office typed interop'
        $receipt.pdfSha256 = (Get-FileHash -LiteralPath $pdf -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    Write-WordStage 'passed'
    $receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $resultFile -Encoding utf8
}
catch {
    $failure = if (Test-Path -LiteralPath $resultFile) { Get-Content -LiteralPath $resultFile -Raw | ConvertFrom-Json } else { [pscustomobject]@{} }
    $failure | Add-Member -NotePropertyName passed -NotePropertyValue $false -Force
    $failure | Add-Member -NotePropertyName message -NotePropertyValue $_.Exception.Message -Force
    $failure | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $resultFile -Encoding utf8
    throw
}
finally {
    if ($identityDocument) { $identityDocument.Close(0); [Runtime.InteropServices.Marshal]::FinalReleaseComObject($identityDocument) | Out-Null }
    if ($reopened) { $reopened.Close(0); [Runtime.InteropServices.Marshal]::FinalReleaseComObject($reopened) | Out-Null }
    if ($document) { $document.Close(0); [Runtime.InteropServices.Marshal]::FinalReleaseComObject($document) | Out-Null }
    if ($word -and $wordPid) { $word.Quit(); [Runtime.InteropServices.Marshal]::FinalReleaseComObject($word) | Out-Null }
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
