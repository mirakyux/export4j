# SPDX-License-Identifier: Apache-2.0
# Saves only a generated fixture in a private, bounded Microsoft Word process.
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$Find = 'ORIGINAL',
    [string]$Replacement = 'WORD-EDITED',
    [switch]$Worker
)
$ErrorActionPreference = 'Stop'
$sourceFile = (Resolve-Path -LiteralPath $Source).Path
$outputRoot = [System.IO.Path]::GetFullPath($OutputDirectory)
[void][System.IO.Directory]::CreateDirectory($outputRoot)
$target = Join-Path $outputRoot 'word-resaved.docx'
$report = Join-Path $outputRoot 'word-report.json'
$ownedPidFile = Join-Path $outputRoot 'word-process.txt'
if ($sourceFile -eq $target) { throw 'Source and generated target must differ' }
if (!$Worker) {
    $existing = @(Get-Process WINWORD -ErrorAction SilentlyContinue | ForEach-Object Id)
    Remove-Item -LiteralPath $ownedPidFile, $report -ErrorAction SilentlyContinue
    function Quote-Argument([string]$Value) { return '"' + $Value.Replace('"', '\"') + '"' }
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Quote-Argument $PSCommandPath),
        '-Source', (Quote-Argument $sourceFile), '-OutputDirectory', (Quote-Argument $outputRoot),
        '-Find', (Quote-Argument $Find), '-Replacement', (Quote-Argument $Replacement), '-Worker')
    $workerProcess = Start-Process (Get-Process -Id $PID).Path -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $outputRoot 'word-stdout.log') -RedirectStandardError (Join-Path $outputRoot 'word-stderr.log')
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        while (!$workerProcess.WaitForExit(500)) {
            if ($timer.Elapsed.TotalSeconds -ge 90) { throw 'Private Word save exceeded its 90 second budget' }
        }
        if ($workerProcess.ExitCode -ne 0 -or !(Test-Path -LiteralPath $report)) { throw 'Private Word save failed; inspect ignored logs' }
        Get-Content -LiteralPath $report
    } finally {
        if (!$workerProcess.HasExited) { $workerProcess.Kill() }
        if (Test-Path -LiteralPath $ownedPidFile) {
            $ownedWordPid = [int](Get-Content -LiteralPath $ownedPidFile)
            if ($ownedWordPid -gt 0 -and $existing -notcontains $ownedWordPid) {
                $ownedWord = Get-Process -Id $ownedWordPid -ErrorAction SilentlyContinue
                if ($null -ne $ownedWord -and $ownedWord.ProcessName -eq 'WINWORD') { $ownedWord.Kill() }
            }
        }
    }
    exit
}

$word = $null; $document = $null; $content = $null; $search = $null
try {
    Copy-Item -LiteralPath $sourceFile -Destination $target -Force
    Write-Output 'Starting private Word instance'
    $existingWord = @(Get-Process WINWORD -ErrorAction SilentlyContinue | ForEach-Object Id)
    $word = New-Object -ComObject Word.Application
    $createdWord = @(Get-Process WINWORD -ErrorAction SilentlyContinue | Where-Object { $existingWord -notcontains $_.Id })
    if ($createdWord.Count -ne 1) { throw 'Cannot identify a private Word process; existing applications are not eligible' }
    $ownedWordPid = $createdWord[0].Id
    [IO.File]::WriteAllText($ownedPidFile, [string]$ownedWordPid)
    $word.Visible = $false; $word.DisplayAlerts = 0; $word.AutomationSecurity = 3
    $word.Options.SaveNormalPrompt = $false
    Write-Output 'Opening generated writable copy'
    $document = $word.Documents.Open($target, $false, $false, $false)
    if ($document.ReadOnly) { throw 'Generated copy opened read-only' }
    $content = $document.Content
    if (!$content.Text.Contains($Find)) { throw 'Generated copy does not contain expected edit source' }
    $search = $content.Find
    [void]$search.Execute($Find, $true, $false, $false, $false, $false, $true, 1, $false, $Replacement, 2)
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($search); $search = $null
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($content); $content = $null
    # Headers/footers are separate stories; edits must not leave an old repeated-band value behind.
    $storyCount = 0
    foreach ($rootStory in $document.StoryRanges) {
        $story = $rootStory
        while ($null -ne $story) {
            if (++$storyCount -gt 1000) { throw 'Generated Word story traversal budget exceeded' }
            $search = $story.Find
            [void]$search.Execute($Find, $true, $false, $false, $false, $false, $true, 1, $false, $Replacement, 2)
            [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($search); $search = $null
            $next = $story.NextStoryRange
            [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($story)
            $story = $next
        }
    }
    Write-Output 'Saving current document through Word'
    $document.Save()
    $saveChanges = 0
    $document.Close([ref]$saveChanges); [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($document); $document = $null
    Write-Output 'Reopening Word-saved document'
    $document = $word.Documents.Open($target, $false, $true, $false)
    $content = $document.Content
    if (!$content.Text.Contains($Replacement) -or $content.Text.Contains($Find)) { throw 'Word save/reopen did not preserve the actual edit' }
    @{
        wordVersion = $word.Version; sourceSha256 = (Get-FileHash -LiteralPath $sourceFile -Algorithm SHA256).Hash.ToLowerInvariant()
        savedSha256 = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
        edited = $true; saved = $true; reopened = $true; stories = $storyCount
    } | ConvertTo-Json | Set-Content -LiteralPath $report -Encoding utf8
    Write-Output 'Word edit, save and reopen verified'
} catch {
    Write-Output $_.ToString()
    Write-Output $_.ScriptStackTrace
    throw
} finally {
    if ($null -ne $search) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($search) }
    if ($null -ne $content) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($content) }
    $saveChanges = 0
    if ($null -ne $document) { $document.Close([ref]$saveChanges); [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($document) }
    if ($null -ne $word) { $word.Quit([ref]$saveChanges); [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($word) }
}
