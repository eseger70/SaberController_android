# SaberController Android

Android BLE controller app for ProffieOS-based saber control.

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
- Start playback with `play_track <path>`
- Stop playback with `stop_track`
- Query now-playing with `get_track`
- Parse framed output across fragmented BLE packets
- Show a two-tab UI with shared connection/log state
- Show grouped preset headers using the `_sub_` naming convention
- Show a grouped track list with tap-to-play flow
- Auto-apply preset mappings for track, album, category, or default playback rules
- Send arbitrary raw BLE commands from the `Log` tab for firmware testing

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

## Usage

1. Tap `Connect`.
2. The app will try the bonded `FEASYCOM` device first, then fall back to BLE scan.
3. Wait for status `READY`. The app will automatically sync blade state, presets, tracks, and volume.
4. On the `Saber` tab:
   - use `ON` / `OFF`
   - review the grouped preset list
   - tap a non-header preset to select it
   - use the volume refresh button or slider
5. On the `Tracks` tab:
    - tap `Refresh Tracks` to reload available paths
    - tap a track row to play it
    - use `Now Playing` and `Stop Track` as needed
    - use the volume refresh button or slider
6. On the `Styles` tab:
   - select a preset on the `Saber` tab
   - bind that preset to the selected track, its album, its category, or the default fallback
   - track playback will resolve mappings in this order: track, album, category, default
7. Inspect the shared log panel for `TX`, `RX`, and framed responses.
8. On the `Log` tab, use the raw command box to send firmware commands such as `tpg`, `tvg`, `tvl`, `tvs 1`, or `tps auto`.

## Notes

- `get_on` expects a framed response containing `0` or `1`.
- The parser is resilient to notification packet fragmentation.
- Command sending uses a serialized command lock with timeout/retry behavior for awaited responses.
- Track browsing is currently path-based because the current firmware command surface is path-based.
- Track style associations are implemented app-side as preset mappings. The app applies `set_preset <index>` before playback when a matching rule exists.
- Presets whose names start with `_sub_` are treated as non-selectable category headers in the UI.
- If a header preset is currently selected, the app blocks ignition until a real preset is chosen.
- The `Log` tab raw command console uses the same BLE transport and framed response parsing as the rest of the app, so it is the preferred surface for testing new firmware commands before dedicated UI is added.

## Troubleshooting

- If status never reaches `READY`, verify the saber exposes characteristics `FFF1` and `FFF2`.
- If no device is found in scan results but the phone is already paired, the app can still connect through the bonded `FEASYCOM` entry.
- If command writes fail, disconnect and reconnect to re-discover services.
- If pairing prompt appears, use PIN `000000`.
- If `list_tracks` returns no items, verify the SD card contains `tracks/*.wav` or `*/tracks/*.wav`.
