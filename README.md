# SaberController Android

Android BLE controller app for ProffieOS-based saber control.

## Scope (Current Iteration)

- Scan for BLE device named `FEASYCOM`
- Connect and discover GATT
- Subscribe to notifications on `0xFFF1`
- Write commands to `0xFFF2` with CRLF
- Send `on`, `off`, and `get_on`
- Fetch track paths with `list_tracks`
- Start playback with `play_track <path>`
- Stop playback with `stop_track`
- Query now-playing with `get_track`
- Parse framed output across fragmented BLE packets
- Show connection/log/state in a simple UI
- Show a path-based track list and tap-to-play flow

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
- `list_tracks`
- `play_track <path>`
- `stop_track`
- `get_track`

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

## Usage

1. Tap `Connect` to scan for `FEASYCOM`.
2. Wait for status `READY`.
3. Tap `Refresh Tracks` to load available paths from the saber.
4. Tap a track path in the list to send `play_track <path>`.
5. Use `Stop Track` or `Now Playing` as needed.
6. Tap `ON`, `OFF`, or `GET STATE` for blade control.
7. Inspect the log panel for raw and framed responses.

## Notes

- `get_on` expects a framed response containing `0` or `1`.
- The parser is resilient to notification packet fragmentation.
- Command sending uses a serialized command lock with timeout/retry behavior for awaited responses.
- Track browsing is currently path-based because the current firmware command surface is path-based.

## Troubleshooting

- If status never reaches `READY`, verify the saber exposes characteristics `FFF1` and `FFF2`.
- If no device is found, confirm advertising name is exactly `FEASYCOM`.
- If command writes fail, disconnect and reconnect to re-discover services.
- If pairing prompt appears, use PIN `000000`.
- If `list_tracks` returns no items, verify the SD card contains `tracks/*.wav` or `*/tracks/*.wav`.
