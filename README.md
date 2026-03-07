# SaberController Android

Android BLE controller app for ProffieOS-based saber control.

## Scope (Current Iteration)

- Scan for BLE device named `FEASYCOM`
- Connect and discover GATT
- Subscribe to notifications on `0xFFF1`
- Write commands to `0xFFF2` with CRLF
- Send `on`, `off`, and `get_on`
- Parse framed output across fragmented BLE packets
- Show connection/log/state in a simple UI

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

## Build / Run

1. Open this folder in Android Studio:
   - `C:\Backup\Eric\sandbox\android\SaberController`
2. Let Android Studio sync Gradle.
3. Build and run on an Android device with BLE support.
4. Grant Bluetooth permissions when prompted.

If you want CLI builds later, generate wrapper files once from Android Studio
or run `gradle wrapper` on a machine with Gradle installed.

## Usage

1. Tap `Connect` to scan for `FEASYCOM`.
2. Wait for status `READY`.
3. Tap `ON`, `OFF`, or `GET STATE`.
4. Inspect the log panel for raw and framed responses.

## Notes

- `get_on` expects a framed response containing `0` or `1`.
- The parser is resilient to notification packet fragmentation.
- Command sending uses a serialized command lock with timeout/retry behavior for awaited responses.

## Troubleshooting

- If status never reaches `READY`, verify the saber exposes characteristics `FFF1` and `FFF2`.
- If no device is found, confirm advertising name is exactly `FEASYCOM`.
- If command writes fail, disconnect and reconnect to re-discover services.
- If pairing prompt appears, use PIN `000000`.
