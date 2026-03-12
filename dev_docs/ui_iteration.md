# UI Iteration Workflow

Use this workflow for faster UI changes without repeated blind device tweaking.

## 1. Local build gate

Run the normal local build before checking the phone:

```powershell
cd C:\Backup\Eric\sandbox\android\SaberController
.\gradlew.bat testDebugUnitTest assembleDebug
```

## 2. Install and log with ADB

Install the current APK and watch the app-specific logs:

```powershell
cd C:\Backup\Eric\sandbox\android\SaberController
.\.tools\android-sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
.\.tools\android-sdk\platform-tools\adb.exe logcat -c
.\.tools\android-sdk\platform-tools\adb.exe logcat -v time SaberCtrl:D SaberCtrlTx:I SaberCtrlRx:I SaberCtrlFrm:I SaberCtrlWarn:W *:S
```

## 3. Capture device screenshots

Use the repo-local capture script instead of manual phone-to-PC loops:

```powershell
cd C:\Backup\Eric\sandbox\android\SaberController
powershell -ExecutionPolicy Bypass -File .\scripts\capture_ui_screenshot.ps1 -Name music_playing
```

By default this writes to `dev_docs\captures`.

## 4. Compare before / after states

Use the compare script to generate a diff image and a pixel-difference summary:

```powershell
cd C:\Backup\Eric\sandbox\android\SaberController
powershell -ExecutionPolicy Bypass -File .\scripts\compare_ui_screenshots.ps1 `
  -Baseline .\dev_docs\captures\baseline\music_playing.png `
  -Candidate .\dev_docs\captures\candidate\music_playing.png `
  -FailPercentThreshold 0.5
```

This creates a `_diff.png` artifact next to the candidate screenshot unless `-DiffOutput` is provided.

For repeatable visual checks, keep named screenshots for the major states:

- `saber_disconnected`
- `saber_ready`
- `saber_blade_on`
- `music_empty`
- `music_selected_folder`
- `music_playing`
- `music_paused`
- `music_shuffle_repeat`
- `effects_sheet`
- `debug_sheet`

Suggested directory structure:

```text
dev_docs/captures/baseline/
dev_docs/captures/candidate/
dev_docs/captures/diff/
```

Use a consistent naming scheme:

```text
dev_docs/captures/2026-03-10_music_playing.png
```

## 5. Android Studio tools

Use these before making another round of layout tweaks:

- Layout Editor:
  - verify XML hierarchy, margins, and sizing before device install
- Layout Inspector:
  - inspect the running screen for actual bounds, clipping, and nested container waste
- Color picker / theme preview:
  - verify the Rey gold token and contrast on chips, cards, and transport icons

## 6. Current automation baseline

The current automated workflow is:

- XML preview
- Layout Inspector
- ADB logcat
- `capture_ui_screenshot.ps1`
- `compare_ui_screenshots.ps1`

Suggested screenshot coverage:

- `Saber` disconnected / ready / blade on
- `Music` empty / playing / paused
- `Music` with shuffle and repeat active
- effects bottom sheet open
- debug bottom sheet open

This keeps the build stable while still making iteration much faster.
