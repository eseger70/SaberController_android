package com.eseger70.sabercontroller.ble

object SaberCommandResponseParser {
    data class PresetEntry(
        val index: Int,
        val name: String,
        val font: String? = null,
        val track: String? = null
    ) {
        val isHeader: Boolean = name.startsWith(HEADER_PREFIX)
        val displayName: String = if (isHeader) {
            name.removePrefix(HEADER_PREFIX)
                .replace('_', ' ')
                .ifBlank { name }
        } else {
            name
        }
    }

    sealed class PresetRow {
        abstract val presetIndex: Int
        abstract val label: String

        data class Header(
            val entry: PresetEntry
        ) : PresetRow() {
            override val presetIndex: Int = entry.index
            override val label: String = entry.displayName
        }

        data class Preset(
            val entry: PresetEntry
        ) : PresetRow() {
            override val presetIndex: Int = entry.index
            override val label: String = entry.displayName
        }
    }

    sealed class TrackRow {
        data class Header(val title: String) : TrackRow()
        data class Track(val path: String, val displayName: String) : TrackRow()
    }

    fun parseBladeState(response: String?): Boolean? {
        val normalized = response
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it == "0" || it == "1" }
            ?: return null

        return normalized == "1"
    }

    fun parsePresetEntries(response: String?): List<PresetEntry> {
        if (response.isNullOrBlank()) return emptyList()

        val presets = mutableListOf<PresetEntry>()
        var font: String? = null
        var track: String? = null
        var name: String? = null

        response.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                when {
                    line.startsWith("FONT=") -> font = parseQuotedValue(line)
                    line.startsWith("TRACK=") -> track = parseQuotedValue(line)
                    line.startsWith("NAME=") -> name = parseQuotedValue(line)
                    line.startsWith("VARIATION=") -> {
                        val presetName = name ?: return@forEach
                        presets.add(
                            PresetEntry(
                                index = presets.size,
                                name = presetName,
                                font = font,
                                track = track
                            )
                        )
                        font = null
                        track = null
                        name = null
                    }
                }
            }

        return presets
    }

    fun buildPresetRows(entries: List<PresetEntry>): List<PresetRow> {
        if (entries.isEmpty()) return emptyList()

        val rows = mutableListOf<PresetRow>()
        for (entry in entries) {
            if (entry.isHeader) {
                rows.add(PresetRow.Header(entry))
            } else {
                rows.add(PresetRow.Preset(entry))
            }
        }
        return rows
    }

    fun parseCurrentPresetIndex(response: String?): Int? {
        return response
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.matches(Regex("-?\\d+")) }
            ?.toIntOrNull()
    }

    fun parseVolume(response: String?): Int? {
        return response
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.matches(Regex("\\d+")) }
            ?.toIntOrNull()
    }

    fun parseTrackPaths(response: String?): List<String> {
        if (response.isNullOrBlank()) return emptyList()

        return response
            .lineSequence()
            .map { it.trim() }
            .filter { it.isTrackPath() }
            .distinct()
            .toList()
    }

    fun buildTrackRows(trackPaths: List<String>): List<TrackRow> {
        if (trackPaths.isEmpty()) return emptyList()

        val rows = mutableListOf<TrackRow>()
        var lastHeader: String? = null
        for (path in trackPaths) {
            val header = trackGroupLabel(path)
            if (header != lastHeader) {
                rows.add(TrackRow.Header(header))
                lastHeader = header
            }
            rows.add(TrackRow.Track(path = path, displayName = path.substringAfterLast('/')))
        }
        return rows
    }

    fun parseNowPlaying(response: String?): String? {
        if (response.isNullOrBlank()) return null

        val firstLine = response
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null

        return when {
            firstLine.startsWith("Playing ", ignoreCase = true) -> {
                firstLine.substringAfter("Playing ").trim().ifBlank { null }
            }
            firstLine.isTrackPath() -> firstLine
            else -> null
        }
    }

    private fun parseQuotedValue(line: String): String? {
        val rawValue = line.substringAfter('=', "")
            .trim()
            .removeSurrounding("\"")
            .replace("\\\"", "\"")
        return rawValue.ifBlank { null }
    }

    private fun trackGroupLabel(path: String): String {
        val directory = path.substringBeforeLast('/', "")
        if (directory.isBlank()) return "Tracks"
        if (directory.equals("tracks", ignoreCase = true)) return "Tracks"
        if (directory.endsWith("/tracks", ignoreCase = true)) {
            return directory.substringBeforeLast("/tracks")
                .ifBlank { "Tracks" }
        }
        return directory
    }

    private fun String.isTrackPath(): Boolean {
        return contains(".wav", ignoreCase = true) &&
            !startsWith("Playing ", ignoreCase = true)
    }

    private const val HEADER_PREFIX = "_sub_"
}
