# ADB Debugging Instructions

This document summarizes how to install the Android app over USB and capture live debug logs with ADB.

## Goal

Use ADB from the Windows PC to:

- install or update the APK on the Android phone
- confirm the phone is connected correctly
- capture the app's mirrored BLE/debug logs in real time

## Prerequisites

1. Connect the phone to the PC with a USB cable.
2. Unlock the phone.
3. Set USB mode to `File transfer`.
4. Enable `Developer options` on the phone.
5. Enable `USB debugging`.
6. When prompted on the phone, approve the PC for USB debugging.

If `adb devices` shows `unauthorized`, the approval prompt on the phone has not been accepted yet.

## Open PowerShell

From Windows PowerShell:

```powershell
cd C:\Backup\Eric\sandbox\android\SaberController
```

## Check ADB Connectivity

If `adb` is already on `PATH`:

```powershell
adb devices
```

If `adb` is not on `PATH`, use the repo-local copy:

```powershell
.\.tools\android-sdk\platform-tools\adb.exe devices
```

Expected result:

- the phone appears with state `device`

If no device appears:

1. keep the phone unlocked
2. confirm `USB debugging` is enabled
3. confirm USB mode is `File transfer`
4. reconnect the cable
5. try another cable or USB port

## Install or Update the APK

If `adb` is on `PATH`:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

If using the repo-local copy:

```powershell
.\.tools\android-sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

Notes:

- `-r` means reinstall/update the app while keeping app data when the signing key matches
- the app is still installed and run on the phone, not on the PC

## Clear Old Logs

If `adb` is on `PATH`:

```powershell
adb logcat -c
```

If using the repo-local copy:

```powershell
.\.tools\android-sdk\platform-tools\adb.exe logcat -c
```

## Watch Only the App Logs

If `adb` is on `PATH`:

```powershell
adb logcat -v time SaberCtrl:D SaberCtrlTx:I SaberCtrlRx:I SaberCtrlFrm:I SaberCtrlWarn:W *:S
```

## Screenshot capture and compare

Capture a named UI screenshot directly from the connected phone:

```powershell
cd C:\Backup\Eric\sandbox\android\SaberController
powershell -ExecutionPolicy Bypass -File .\scripts\capture_ui_screenshot.ps1 -Name music_playing
```

Compare a candidate screenshot to a saved baseline:

```powershell
cd C:\Backup\Eric\sandbox\android\SaberController
powershell -ExecutionPolicy Bypass -File .\scripts\compare_ui_screenshots.ps1 `
  -Baseline .\dev_docs\captures\baseline\music_playing.png `
  -Candidate .\dev_docs\captures\candidate\music_playing.png `
  -FailPercentThreshold 0.5
```

If using the repo-local copy:

```powershell
.\.tools\android-sdk\platform-tools\adb.exe logcat -v time SaberCtrl:D SaberCtrlTx:I SaberCtrlRx:I SaberCtrlFrm:I SaberCtrlWarn:W *:S
```

## Log Tags

The app mirrors its in-app log panel to these logcat tags:

- `SaberCtrl`
- `SaberCtrlTx`
- `SaberCtrlRx`
- `SaberCtrlFrm`
- `SaberCtrlWarn`

## Typical Workflow

1. Connect phone by USB.
2. Unlock phone and confirm `File transfer`.
3. Approve USB debugging if prompted.
4. In PowerShell:

```powershell
cd C:\Backup\Eric\sandbox\android\SaberController
.\.tools\android-sdk\platform-tools\adb.exe devices
.\.tools\android-sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
.\.tools\android-sdk\platform-tools\adb.exe logcat -c
.\.tools\android-sdk\platform-tools\adb.exe logcat -v time SaberCtrl:D SaberCtrlTx:I SaberCtrlRx:I SaberCtrlFrm:I SaberCtrlWarn:W *:S
```

5. Leave the logcat window open.
6. Use the app normally on the phone.
7. Copy useful log lines from PowerShell instead of emailing screenshots from the phone.

## Useful Track Visual Test Sequence

After connecting in the app, use the raw command console:

```text
tvs 1
tps auto
off
pt 0
gt
st
gt
```

Expected behavior:

- `pt 0` should report `TRACK_SESSION_MODE=visual`
- while the track is active, `TRACK_VISUAL_ACTIVE=1`
- after `st`, `TRACK_SESSION_MODE=none`
- after `st`, `TRACK_VISUAL_ACTIVE=0`
