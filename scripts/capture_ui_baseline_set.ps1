param(
    [string[]]$Names = @(
        'saber_ready',
        'saber_blade_on',
        'music_idle',
        'music_playing',
        'music_paused',
        'music_visuals_sheet',
        'effects_sheet'
    ),
    [string]$OutputDir,
    [string]$AdbPath
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $OutputDir) {
    $OutputDir = Join-Path $repoRoot 'dev_docs\captures\baseline'
}
if (-not $AdbPath) {
    $AdbPath = Join-Path $repoRoot '.tools\android-sdk\platform-tools\adb.exe'
}

$captureScript = Join-Path $PSScriptRoot 'capture_ui_screenshot.ps1'
if (-not (Test-Path $captureScript)) {
    throw "Capture script not found at '$captureScript'."
}

function Get-StateDescription {
    param(
        [Parameter(Mandatory = $true)]
        [string]$StateName
    )

    switch ($StateName) {
        'saber_ready' {
            return @(
                'Open the Saber tab.',
                'Phone is connected to FEASYCOM.',
                'Blade is off.',
                'No bottom sheet is open.',
                'Use a normal selectable preset, not a _sub_ header preset.'
            )
        }
        'saber_blade_on' {
            return @(
                'Open the Saber tab.',
                'Phone is connected to FEASYCOM.',
                'Blade is physically on.',
                'No bottom sheet is open.',
                'Use a normal selectable preset, not a _sub_ header preset.'
            )
        }
        'music_idle' {
            return @(
                'Open the Music tab.',
                'Phone is connected to FEASYCOM.',
                'No track is playing or paused.',
                'Track list is visible.',
                'No Visuals or Debug sheet is open.'
            )
        }
        'music_playing' {
            return @(
                'Open the Music tab.',
                'A track is actively playing.',
                'Now Playing card is visible.',
                'Progress/time row is visible.',
                'No Visuals or Debug sheet is open.'
            )
        }
        'music_paused' {
            return @(
                'Open the Music tab.',
                'A track has been paused, not stopped.',
                'Now Playing card is still visible.',
                'Progress/time row is visible and should be frozen.',
                'No Visuals or Debug sheet is open.'
            )
        }
        'music_visuals_sheet' {
            return @(
                'Open the Music tab.',
                'Open the Visuals bottom sheet.',
                'At least one dropdown/spinner should be visible.',
                'Capture the sheet in a readable resting state.',
                'Do not leave a dropdown list expanded unless you are intentionally testing dropdown contrast.'
            )
        }
        'effects_sheet' {
            return @(
                'Open the Saber tab.',
                'Open the Effects bottom sheet.',
                'Instant and sustained effect controls should be visible.',
                'Capture the sheet in a readable resting state.'
            )
        }
        default {
            return @(
                'Put the phone on the target screen for this named state.',
                'Make sure the important controls are visible.',
                'Close unrelated bottom sheets unless the state name implies one should be open.'
            )
        }
    }
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

Write-Host ''
Write-Host 'Baseline screenshot capture'
Write-Host "Output directory: $OutputDir"
Write-Host 'States to capture:'
foreach ($name in $Names) {
    Write-Host " - $name"
}
Write-Host ''

for ($i = 0; $i -lt $Names.Count; $i++) {
    $name = $Names[$i]
    $details = Get-StateDescription -StateName $name
    Write-Host ''
    Write-Host ("[{0}/{1}] Prepare phone UI for '{2}'." -f ($i + 1), $Names.Count, $name)
    foreach ($detail in $details) {
        Write-Host ("  - {0}" -f $detail)
    }
    $response = Read-Host "Press Enter to capture, 's' to skip, or 'q' to quit"

    if ($response -eq 'q') {
        Write-Host 'Stopping baseline capture.'
        break
    }
    if ($response -eq 's') {
        Write-Host "Skipped: $name"
        continue
    }

    & powershell -ExecutionPolicy Bypass -File $captureScript `
        -Name $name `
        -OutputDir $OutputDir `
        -AdbPath $AdbPath

    if ($LASTEXITCODE -ne 0) {
        throw "Failed while capturing '$name'."
    }
}

Write-Host ''
Write-Host 'Baseline capture finished.'
