# SaberController Android

Android BLE controller app for ProffieOS-based saber control and music playback.

Validated baseline:

- Android app repo: this repository
- Firmware repo: `C:\Backup\Eric\sandbox\saber\ProffieOS_v7.15`
- Firmware branch: `track_player_000`
- Minimum validated firmware commit: `d9fe674`
- Full baseline notes: [dev_docs/baseline.md](dev_docs/baseline.md)

## Scope (Current Iteration)

- Use paired `FEASYCOM` first, then scan as fallback
- Connect and discover GATT
- Subscribe to notifications on `0xFFF1`
- Write commands to `0xFFF2` with CRLF
- Send `on`, `off`, and `get_on`
- Fetch presets with `list_presets`
- Read current preset with `get_preset`
- Select presets with `set_preset <index>`
- Read and set volume with `get_volume` / `set_volume <value>`
- Fetch track paths with `list_tracks`
- Start playback with `play_track <path>` or `pt <index>`
- Stop playback with `stop_track` or `st`
- Pause and resume playback with `ta` / `tr`
- Query now-playing with `get_track` or `gt`
- Parse framed output across fragmented BLE packets
- Show a 2-tab `Saber` / `Music` UI with bottom-sheet debug and effects surfaces
- Show grouped preset headers using the `_sub_` naming convention
- Show a collapsible grouped track list with folder/category queue playback
- Show elapsed / remaining playback time and progress
- Support shuffle and repeat within the active folder/category queue
- Control firmware-backed track playback modes and track visuals
- Apply app-local visual assignments with precedence:
  - temporary override
  - track
  - album
  - category
  - default
- Send arbitrary raw BLE commands from the debug bottom sheet for firmware testing
- Use a Rey-themed visual system keyed to `Rgb<180,130,0>`

## BLE Protocol

- Device name: `FEASYCOM`
- Write characteristic: `0000fff2-0000-1000-8000-00805f9b34fb`
- Notify characteristic: `0000fff1-0000-1000-8000-00805f9b34fb`
- Command format: ASCII + `\r\n`
- Frame begin marker: `-+=BEGIN_OUTPUT=+-`
- Frame end marker: `-+=END_OUTPUT=+-`

Supported app commands:

- `on`
- `off`
- `get_on`
- `list_presets`
- `get_preset`
- `set_preset <index>`
- `get_volume`
- `set_volume <value>`
- `list_tracks`
- `play_track <path>`
- `stop_track`
- `get_track`
- Short transport aliases used by the app where supported:
  - `lt`
  - `pt <index>`
  - `st`
  - `gt`
  - `ta`
  - `tr`
  - `ttp`
  - `tvl`
  - `tvg`
  - `tvs <id>`
  - `tvc`
  - `tpg`
  - `tps <auto|preserve|visual>`
  - `cl`, `sb`, `fo`, `bt`, `lk`, `dg`, `lb`, `mt`

## Build / Run

1. Open this folder in Android Studio:
   - `C:\Backup\Eric\sandbox\android\SaberController`
2. Let Android Studio sync Gradle.
3. Build and run on an Android device with BLE support.
4. Grant Bluetooth permissions when prompted.

Wrapper files are now included, so CLI builds can use:
- `.\gradlew.bat assembleDebug`
- `.\gradlew.bat test`

The first wrapper run will download Gradle automatically. A local JDK is still required.
GitHub Actions CI is also configured to run unit tests and build a debug APK on pushes and pull requests.
For local CLI builds, Android SDK path must also be configured via Android Studio, `ANDROID_HOME`, or `local.properties`.

UI iteration notes are in [dev_docs/ui_iteration.md](dev_docs/ui_iteration.md).

## ADB Workflow

Use ADB for faster install and log capture:

- install/update the debug build:
  - `adb install -r app\build\outputs\apk\debug\app-debug.apk`
- confirm the device is visible:
  - `adb devices`
- clear old app logs before a test run:
  - `adb logcat -c`
- watch only the app's mirrored debug logs:
  - `adb logcat -v time SaberCtrl:D SaberCtrlTx:I SaberCtrlRx:I SaberCtrlFrm:I SaberCtrlWarn:W *:S`

The app mirrors its in-app log panel to logcat under these tags:

- `SaberCtrl`
- `SaberCtrlTx`
- `SaberCtrlRx`
- `SaberCtrlFrm`
- `SaberCtrlWarn`

## UX Layout

Top-level navigation is now:

- `Saber`
- `Music`

Secondary surfaces:

- `Effects` opens from the `Saber` tab as a bottom sheet
- `Debug` opens from the header icon as a bottom sheet

## Usage

1. Tap `Connect`.
2. The app will try the bonded `FEASYCOM` device first, then fall back to BLE scan.
3. Wait for status `READY`. The app will automatically sync blade state, presets, tracks, and volume.
4. On the `Saber` tab:
   - use `ON` / `OFF`
   - review grouped preset categories
   - tap a category header to expand or collapse its presets
   - tap a non-header preset to select it
   - use the volume refresh button or slider
   - open `Effects` for clash / force / lockup style commands
5. On the `Music` tab:
   - tap `Refresh` to reload available track paths
   - tap folder/category headers to expand or collapse them
   - tap a folder/category header to make it the active queue scope
   - use `Play Folder` to queue and start the active scope
   - tap a track row to play that track directly
   - use icon transport controls for:
     - previous
     - play / pause
     - next
     - stop
     - shuffle
     - repeat
   - watch elapsed / remaining time and the progress bar
   - use the `Track Visuals` section to:
     - choose playback mode: `Auto`, `Saber`, or `Music`
     - refresh the firmware-defined visual list
     - apply or clear the selected firmware visual
     - preview or stop preview while idle
     - set a temporary override for the current session
     - assign visuals by track / album / category / default
6. Open the header debug icon when you need:
   - the raw command box
   - the selectable log
   - copy-log or copy-frame actions

## Notes

- `get_on` expects a framed response containing `0` or `1`.
- The parser is resilient to notification packet fragmentation.
- Command sending uses a serialized command lock with timeout/retry behavior for awaited responses.
- Track browsing is currently path-based because the current firmware command surface is path-based.
- Folder playback queues are app-driven. The app advances to the next track in the selected folder/category by polling `gt` silently while queued playback is active.
- Shuffle is app-driven within the active queue scope only.
- Track pause/resume is firmware-backed via bookmark-based `ta` / `tr`.
- Elapsed / remaining time comes from `TRACK_POS_MS` and `TRACK_LENGTH_MS`.
- Track visuals are implemented firmware-side. The app no longer switches presets before playback.
- `Auto` preserves saber behavior while the blade is on and uses the selected track visual while the blade is off.
- `Music` requires the blade to be off. If the blade is on, playback is rejected with `TRACK_VISUAL_REJECTED=blade_on`.
- Visual assignment precedence is:
  - temporary override
  - track
  - album
  - category
  - default
- Visual assignments are app-local and are not persisted into firmware.
- Presets whose names start with `_sub_` are treated as non-selectable category headers in the UI.
- If a header preset is currently selected, the app blocks ignition until a real preset is chosen.
- The debug bottom-sheet raw command console uses the same BLE transport and framed response parsing as the rest of the app, so it is the preferred surface for testing new firmware commands before dedicated UI is added.
- Pause/resume baseline is bookmark-based:
  - `ta` stores file + position and frees the player
  - `tr` reopens the track from the saved position
- Theme accent color matches Rey's saber gold:
  - `Rgb<180,130,0>`
  - `#B48200`

## Troubleshooting

- If status never reaches `READY`, verify the saber exposes characteristics `FFF1` and `FFF2`.
- If no device is found in scan results but the phone is already paired, the app can still connect through the bonded `FEASYCOM` entry.
- If command writes fail, disconnect and reconnect to re-discover services.
- If pairing prompt appears, use PIN `000000`.
- If `list_tracks` returns no items, verify the SD card contains `tracks/*.wav` or `*/tracks/*.wav`.
