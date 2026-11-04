# SPDX-License-Identifier: Apache-2.0
param(
    [Parameter(Mandatory = $true)][string]$InputPath,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [ValidateRange(30, 600)][int]$TimeoutSeconds = 120,
    [switch]$Worker,
    [string]$BaselineWordPids = ''
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $InputPath).Path
$destination = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($destination) | Out-Null
$ownershipFile = Join-Path $destination 'word-ownership.json'
$resultFile = Join-Path $destination 'word-math-acceptance.json'
$stageFile = Join-Path $destination 'word-stage.json'
$saved = Join-Path $destination 'word-saved.docx'
$pdf = Join-Path $destination 'word-rendered.pdf'
if ($source.Equals($saved, [StringComparison]::OrdinalIgnoreCase)) { throw 'The input must differ from the private Word working copy' }
function Write-WordStage([string]$stage) { @{ stage = $stage } | ConvertTo-Json | Set-Content -LiteralPath $stageFile -Encoding utf8 }

function Initialize-WordMathInterop {
    $assemblyPaths = @{}
    foreach ($name in @('Microsoft.Office.Interop.Word', 'office')) {
        $candidates = @(
            (Join-Path ([Environment]::GetFolderPath('Windows')) "assembly/GAC_MSIL/$name"),
            (Join-Path ([Environment]::GetFolderPath('Windows')) "Microsoft.NET/assembly/GAC_MSIL/$name")
        )
        $assembly = @($candidates | Where-Object { Test-Path -LiteralPath $_ } |
            ForEach-Object { Get-ChildItem -LiteralPath $_ -Recurse -Filter '*.dll' } |
            Sort-Object FullName -Descending | Select-Object -First 1)
        if ($assembly.Count -ne 1) { throw "Word math verification requires the installed Office interop assembly: $name" }
        $assemblyPaths[$name] = $assembly[0].FullName
        [Reflection.Assembly]::LoadFrom($assembly[0].FullName) | Out-Null
    }
    $references = @($assemblyPaths['Microsoft.Office.Interop.Word'], $assemblyPaths['office'])
    # PowerShell 7 needs its .NET Framework facade for the installed Office collection interfaces.
    if (Test-Path -LiteralPath (Join-Path $PSHOME 'ref/mscorlib.dll')) {
        $references += @('mscorlib.dll', 'System.Runtime.dll', 'System.Collections.NonGeneric.dll') | ForEach-Object { Join-Path $PSHOME "ref/$_" }
    }
    Add-Type -ReferencedAssemblies $references -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using Word = Microsoft.Office.Interop.Word;
public static class Export4jWordMath {
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
    private static Word.Range Denominator(object source) {
        Word.Document document = (Word.Document)source;
        Word.OMath first = document.OMaths[1];
        for (int index = 1; index <= first.Functions.Count; index++) {
            Word.OMathFunction function = first.Functions[index];
            if (function.Type == Word.WdOMathFunctionType.wdOMathFunctionFrac) {
                return function.Frac.Den.Range;
            }
        }
        throw new InvalidOperationException("The first native equation has no editable fraction");
    }
    public static string ReadDenominator(object source) { return Denominator(source).Text.Normalize(System.Text.NormalizationForm.FormKC); }
    public static void EditDenominator(object source) {
        Word.Range range = Denominator(source);
        if (range.Text.Normalize(System.Text.NormalizationForm.FormKC) != "2a") { throw new InvalidOperationException("The original native denominator is not 2a"); }
        range.Text = "5a";
    }
    public static string[] Equations(object source) {
        Word.Document document = (Word.Document)source;
        string[] result = new string[document.OMaths.Count];
        for (int index = 1; index <= result.Length; index++) { result[index - 1] = document.OMaths[index].Range.Text; }
        return result;
    }
    public static void ExportPdf(object source, string path) {
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

if (-not $Worker) {
    $baseline = @(Get-Process -Name WINWORD -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
    $started = Get-Date
    if (Test-Path -LiteralPath $ownershipFile) { Remove-Item -LiteralPath $ownershipFile }
    if (Test-Path -LiteralPath $resultFile) { Remove-Item -LiteralPath $resultFile }
    function Quote-Argument([string]$value) { return '"' + $value.Replace('"', '\"') + '"' }
    $arguments = @('-NoProfile', '-Sta', '-File', (Quote-Argument $PSCommandPath), '-InputPath', (Quote-Argument $source), '-OutputDirectory', (Quote-Argument $destination), '-Worker', '-BaselineWordPids', (Quote-Argument ($baseline -join ',')))
    $child = Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $destination 'word-worker.stdout.log') -RedirectStandardError (Join-Path $destination 'word-worker.stderr.log')
    $childHandle = $child.Handle
    try {
        if (-not $child.WaitForExit($TimeoutSeconds * 1000)) { throw "Word math verification exceeded $TimeoutSeconds seconds" }
        $child.Refresh()
        if ($child.ExitCode -ne 0) { throw "Word math verification worker failed: $($child.ExitCode)" }
        if (-not (Test-Path -LiteralPath $resultFile)) { throw 'Word math verification did not produce a receipt' }
        if (-not (Get-Content -LiteralPath $resultFile -Raw | ConvertFrom-Json).passed) { throw 'Word math verification receipt reports a failure' }
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
    Initialize-WordMathInterop
    Write-WordStage 'launch-private-word'
    $word = New-Object -ComObject Word.Application
    $identityDocument = $word.Documents.Add()
    [uint32]$createdPid = 0
    [Export4jWordMath]::GetWindowThreadProcessId([IntPtr]$word.ActiveWindow.Hwnd, [ref]$createdPid) | Out-Null
    $original = @($BaselineWordPids.Split(',', [StringSplitOptions]::RemoveEmptyEntries) | ForEach-Object { [int]$_ })
    if ($createdPid -eq 0 -or $original -contains [int]$createdPid) { throw 'A private Word process could not be established; existing instances are preserved' }
    $wordPid = [int]$createdPid
    @{ wordPid = $wordPid; workerPid = $PID } | ConvertTo-Json | Set-Content -LiteralPath $ownershipFile -Encoding utf8
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $word.AutomationSecurity = 3
    $identityDocument.Close(0)
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($identityDocument) | Out-Null
    $identityDocument = $null
    Copy-Item -LiteralPath $source -Destination $saved
    Write-WordStage 'open-native-equations'
    $document = $word.Documents.Open($saved, $false, $false, $false)
    if ($document.ReadOnly) { throw 'The private Word copy opened read-only' }
    $before = [Export4jWordMath]::Equations($document)
    if ($before.Count -ne 8) { throw "Expected eight native Word equations; found $($before.Count)" }
    $beforeDenominator = [Export4jWordMath]::ReadDenominator($document)
    @{ equations = $before; denominator = $beforeDenominator; denominatorUtf16 = @($beforeDenominator.ToCharArray() | ForEach-Object { [int]$_ }) } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $destination 'word-native-inventory.json') -Encoding utf8
    Write-WordStage 'edit-native-denominator'
    $document.TrackRevisions = $false
    [Export4jWordMath]::EditDenominator($document)
    $edited = [Export4jWordMath]::Equations($document)
    if ($edited.Count -ne 8 -or [Export4jWordMath]::ReadDenominator($document) -ne '5a' -or $edited[0] -eq $before[0]) { throw 'Word did not retain the native fraction after editing its denominator' }
    for ($index = 1; $index -lt 8; $index++) { if ($edited[$index] -ne $before[$index]) { throw 'Editing the first equation changed another native equation' } }
    Write-WordStage 'save-docx'
    $document.Save()
    $document.Close(0)
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($document) | Out-Null
    $document = $null
    Write-WordStage 'reopen-docx'
    $reopened = $word.Documents.Open($saved, $false, $true, $false)
    $after = [Export4jWordMath]::Equations($reopened)
    if ($after.Count -ne 8 -or [Export4jWordMath]::ReadDenominator($reopened) -ne '5a') { throw 'Word saved/reopened native fraction state differs' }
    for ($index = 0; $index -lt 8; $index++) { if ($after[$index] -ne $edited[$index]) { throw 'Word saved/reopened native equation content differs' } }
    Write-WordStage 'export-pdf'
    $reopened.Repaginate()
    $pages = $reopened.ComputeStatistics(2)
    [Export4jWordMath]::ExportPdf($reopened, $pdf)
    if (-not (Test-Path -LiteralPath $pdf)) { throw 'Word did not produce a PDF' }
    @{
        passed = $true; acceptance = 'Installed Microsoft Word application automation'; humanUiAcceptance = $false
        wordVersion = $word.Version; wordBuild = $word.Build; beforeNativeEquations = $before.Count; afterNativeEquations = $after.Count
        denominatorBefore = $beforeDenominator; denominatorAfter = '5a'; actualNativeEquationEdit = $true; otherEquationsUnchanged = $true
        savedAndReopened = $true; pdfExported = $true; pdfExportMethod = 'Office typed interop'; pages = $pages
        inputSha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
        savedSha256 = (Get-FileHash -LiteralPath $saved -Algorithm SHA256).Hash.ToLowerInvariant()
        pdfSha256 = (Get-FileHash -LiteralPath $pdf -Algorithm SHA256).Hash.ToLowerInvariant()
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $resultFile -Encoding utf8
    Write-WordStage 'passed'
}
catch {
    @{ passed = $false; message = $_.Exception.Message } | ConvertTo-Json | Set-Content -LiteralPath $resultFile -Encoding utf8
    throw
}
finally {
    if ($identityDocument) { $identityDocument.Close(0); [Runtime.InteropServices.Marshal]::FinalReleaseComObject($identityDocument) | Out-Null }
    if ($reopened) { $reopened.Close(0); [Runtime.InteropServices.Marshal]::FinalReleaseComObject($reopened) | Out-Null }
    if ($document) { $document.Close(0); [Runtime.InteropServices.Marshal]::FinalReleaseComObject($document) | Out-Null }
    if ($word) {
        if ($wordPid) { $word.Quit() }
        [Runtime.InteropServices.Marshal]::FinalReleaseComObject($word) | Out-Null
    }
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
