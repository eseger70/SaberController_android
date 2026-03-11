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

Use ADB screenshots instead of phone-to-PC email loops:

```powershell
cd C:\Backup\Eric\sandbox\android\SaberController
.\.tools\android-sdk\platform-tools\adb.exe shell screencap -p /sdcard/Download/saber_ui.png
.\.tools\android-sdk\platform-tools\adb.exe pull /sdcard/Download/saber_ui.png .\dev_docs\captures\saber_ui.png
```

If `dev_docs\captures` does not exist yet, create it first.

## 4. Compare before / after states

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

## 6. Recommended automated next step

If UI iteration becomes frequent, add screenshot tests with a JVM screenshot tool such as `Roborazzi`.

Suggested screenshot coverage:

- `Saber` disconnected / ready / blade on
- `Music` empty / playing / paused
- `Music` with shuffle and repeat active
- effects bottom sheet open
- debug bottom sheet open

This repo does not add that dependency yet. The current workflow is:

- XML preview
- Layout Inspector
- ADB screenshots
- ADB logcat

That keeps the build stable while still making iteration much faster.
