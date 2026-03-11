# Validated Baseline

Date validated: `2026-03-10`

This document records the current known-good Android app + firmware baseline for BLE saber control and music playback.

## Repositories

- Android app repo:
  - `C:\Backup\Eric\sandbox\android\SaberController`
- Firmware repo:
  - `C:\Backup\Eric\sandbox\saber\ProffieOS_v7.15`

## Required Firmware Baseline

- Branch: `track_player_000`
- Minimum validated commit: `d9fe674`
- Meaning:
  - track visuals are firmware-backed
  - idle track-visual preview is supported
  - pause/resume uses bookmark-based restart instead of keeping a paused decoder alive

## Android Baseline

Use the current `main` branch of the Android app repo together with firmware `track_player_000` at or after `d9fe674`.

Current app capabilities rely on the following firmware-side command surface.

## BLE Transport Baseline

- Device name: `FEASYCOM`
- Notify characteristic: `FFF1`
- Write characteristic: `FFF2`
- Commands are ASCII with `CRLF`
- Responses are framed between:
  - `-+=BEGIN_OUTPUT=+-`
  - `-+=END_OUTPUT=+-`

The app prefers short aliases when available because they are more reliable over the BLE bridge.

## Validated Command Baseline

### Core saber control

- `on`
- `off`
- `get_on`
- `list_presets`
- `get_preset`
- `set_preset <index>`
- `get_volume`
- `set_volume <value>`

### Track playback

- `lt`
- `pt <index>`
- `st`
- `gt`
- `ta`
- `tr`
- `ttp`

### Track visuals

- `tvl`
- `tvg`
- `tvs <id>`
- `tvc`
- `tvp <id>`
- `tvx`
- `tpg`
- `tps <auto|preserve|visual>`

### Effects

- `cl`
- `sb`
- `fo`
- `bt`
- `lk`
- `dg`
- `lb`
- `mt`

## Validated Behavior

### Presets

- Preset names starting with `_sub_` are treated as category headers in the Android UI.
- Header categories expand/collapse to reveal real presets.
- Header presets are non-selectable and non-ignitable by design.

### Tracks

- Track browsing is path-based.
- Track folders/categories expand and collapse in the Android UI.
- `Play Folder`, `Next`, `Previous`, `Play / Pause`, `Stop`, and `Repeat Folder` are app-supported.
- Folder queueing is app-driven.

### Track visuals

- Track visuals are firmware-defined styles, not presets.
- They only activate:
  - during track playback when policy resolves to visual mode
  - during explicit idle preview via `tvp`
- They do not ignite the blade and do not switch the active preset.

### Playback policy

- `auto`
  - blade off + selected visual -> visual playback
  - blade on -> preserve normal saber behavior
- `preserve`
  - normal saber behavior is preserved
- `visual`
  - requires blade off
  - blade on rejects playback with `TRACK_VISUAL_REJECTED=blade_on`

### Pause / resume

- Pause and resume are firmware-backed.
- Current baseline behavior:
  - `ta` saves track file and playback position, then frees the player
  - `tr` allocates a fresh player and resumes from the saved position
- This replaced the earlier “keep decoder paused in place” approach because that approach became unreliable on longer pauses.

## Known-Good Validation Sequence

### Track visual playback

```text
tvs 1
tps auto
off
pt 0
gt
st
gt
```

Expected:

- while playing with blade off:
  - `TRACK_SESSION_MODE=visual`
  - `TRACK_VISUAL_ACTIVE=1`
- after stop:
  - `TRACK_SESSION_MODE=none`
  - `TRACK_VISUAL_ACTIVE=0`

### Idle preview

```text
tvs 1
tvp 1
tvg
tvx
tvg
```

Expected:

- preview on:
  - `TRACK_VISUAL_PREVIEW=1`
  - `TRACK_VISUAL_ACTIVE=1`
- preview off:
  - `TRACK_VISUAL_PREVIEW=0`
  - `TRACK_VISUAL_ACTIVE=0`

### Pause / resume

```text
pt 0
ta
gt
tr
gt
```

Expected:

- after `ta`:
  - `TRACK_ACTIVE=1`
  - `TRACK_PAUSED=1`
  - `TRACK_PLAYER=-1`
  - `TRACK_FILE` preserved
  - `TRACK_POS_MS` preserved
- after `tr`:
  - `TRACK_ACTIVE=1`
  - `TRACK_PAUSED=0`
  - `TRACK_PLAYER>=0`
- later `gt`:
  - `TRACK_POS_MS` continues increasing

## Notes For Future Work

- If future firmware work touches track playback internals, do not regress the bookmark-based pause/resume model without a concrete reason.
- Continue to prefer short aliases for frequently used BLE commands.
- Treat this baseline as the rollback target if new music-player work destabilizes behavior.
