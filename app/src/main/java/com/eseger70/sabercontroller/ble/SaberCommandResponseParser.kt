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
        abstract val level: Int
        abstract val label: String

        data class Header(
            val title: String,
            override val level: Int
        ) : TrackRow() {
            override val label: String = title
        }

        data class Track(
            val path: String,
            val displayName: String,
            override val level: Int
        ) : TrackRow() {
            override val label: String = displayName
        }
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
        var previousDirectories: List<String> = emptyList()
        var emittedRootHeader = false
        for (path in trackPaths) {
            val segments = visibleTrackSegments(path)
            if (segments.isEmpty()) continue

            val directories = segments.dropLast(1)
            if (directories.isEmpty()) {
                if (!emittedRootHeader || previousDirectories.isNotEmpty()) {
                    rows.add(TrackRow.Header(title = "Tracks", level = 0))
                    emittedRootHeader = true
                }
            } else {
                emittedRootHeader = false
                val sharedDepth = sharedPrefixLength(previousDirectories, directories)
                for (index in sharedDepth until directories.size) {
                    rows.add(
                        TrackRow.Header(
                            title = displayDirectoryName(directories[index]),
                            level = index
                        )
                    )
                }
            }
            rows.add(
                TrackRow.Track(
                    path = path,
                    displayName = path.substringAfterLast('/'),
                    level = directories.size
                )
            )
            previousDirectories = directories
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

    private fun visibleTrackSegments(path: String): List<String> {
        return path
            .split('/')
            .filter { it.isNotBlank() }
            .filterNot { it.equals("tracks", ignoreCase = true) }
    }

    private fun sharedPrefixLength(first: List<String>, second: List<String>): Int {
        val max = minOf(first.size, second.size)
        for (index in 0 until max) {
            if (!first[index].equals(second[index], ignoreCase = true)) {
                return index
            }
        }
        return max
    }

    private fun displayDirectoryName(raw: String): String {
        return raw.replace('_', ' ').ifBlank { raw }
    }

    private fun String.isTrackPath(): Boolean {
        return contains(".wav", ignoreCase = true) &&
            !startsWith("Playing ", ignoreCase = true)
    }

    private const val HEADER_PREFIX = "_sub_"
}
