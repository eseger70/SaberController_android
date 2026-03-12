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
    Write-Host ''
    Write-Host ("[{0}/{1}] Prepare phone UI for '{2}'." -f ($i + 1), $Names.Count, $name)
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
