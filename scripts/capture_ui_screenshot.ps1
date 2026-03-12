param(
    [Parameter(Mandatory = $true)]
    [string]$Name,
    [string]$OutputDir,
    [string]$AdbPath
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $OutputDir) {
    $OutputDir = Join-Path $repoRoot 'dev_docs\captures'
}
if (-not $AdbPath) {
    $AdbPath = Join-Path $repoRoot '.tools\android-sdk\platform-tools\adb.exe'
}

if (-not (Test-Path $AdbPath)) {
    throw "adb not found at '$AdbPath'."
}

$safeName = ($Name -replace '[^A-Za-z0-9._-]', '_').Trim('_')
if ([string]::IsNullOrWhiteSpace($safeName)) {
    throw 'Name must contain at least one alphanumeric character.'
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$deviceList = & $AdbPath devices
if ($LASTEXITCODE -ne 0) {
    throw 'adb devices failed.'
}
if (-not ($deviceList | Select-String -Pattern '^\S+\s+device$')) {
    throw "No authorized Android device detected. Run '$AdbPath devices' and verify the phone shows as 'device'."
}

$devicePath = "/sdcard/Download/$safeName.png"
$localPath = Join-Path $OutputDir "$safeName.png"

Write-Host "Capturing screenshot to $localPath"
& $AdbPath shell screencap -p $devicePath
if ($LASTEXITCODE -ne 0) {
    throw 'adb shell screencap failed.'
}

& $AdbPath pull $devicePath $localPath
if ($LASTEXITCODE -ne 0) {
    throw 'adb pull failed.'
}

& $AdbPath shell rm $devicePath | Out-Null

Write-Host "Saved screenshot: $localPath"
Write-Output $localPath
