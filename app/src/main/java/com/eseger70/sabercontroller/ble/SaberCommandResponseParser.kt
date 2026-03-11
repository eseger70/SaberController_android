package com.eseger70.sabercontroller.ble

object SaberCommandResponseParser {
    data class TrackVisualOption(
        val id: Int,
        val name: String
    )

    data class TrackRuntimeState(
        val nowPlaying: String? = null,
        val trackActive: Boolean? = null,
        val trackPaused: Boolean? = null,
        val positionMs: Long? = null,
        val lengthMs: Long? = null,
        val policy: String? = null,
        val sessionMode: String? = null,
        val visualSelectedId: Int? = null,
        val visualName: String? = null,
        val visualActive: Boolean? = null,
        val visualRejectedReason: String? = null,
        val visualActiveId: Int? = null,
        val visualActiveName: String? = null,
        val visualPreviewActive: Boolean? = null,
        val visualPreviewRejectedReason: String? = null
    )

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
            val entry: PresetEntry,
            val expanded: Boolean,
            val childCount: Int
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
            val key: String,
            val title: String,
            override val level: Int,
            val expanded: Boolean,
            val childCount: Int
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

    fun buildPresetRows(
        entries: List<PresetEntry>,
        expandedHeaderIndices: Set<Int> = emptySet()
    ): List<PresetRow> {
        if (entries.isEmpty()) return emptyList()

        val childCountByHeader = mutableMapOf<Int, Int>()
        var currentHeaderIndex: Int? = null
        for (entry in entries) {
            if (entry.isHeader) {
                currentHeaderIndex = entry.index
                childCountByHeader.putIfAbsent(entry.index, 0)
            } else if (currentHeaderIndex != null) {
                childCountByHeader[currentHeaderIndex] =
                    (childCountByHeader[currentHeaderIndex] ?: 0) + 1
            }
        }

        val rows = mutableListOf<PresetRow>()
        var activeHeaderIndex: Int? = null
        for (entry in entries) {
            if (entry.isHeader) {
                activeHeaderIndex = entry.index
                rows.add(
                    PresetRow.Header(
                        entry = entry,
                        expanded = expandedHeaderIndices.contains(entry.index),
                        childCount = childCountByHeader[entry.index] ?: 0
                    )
                )
            } else {
                if (activeHeaderIndex == null || expandedHeaderIndices.contains(activeHeaderIndex)) {
                    rows.add(PresetRow.Preset(entry))
                }
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

    fun buildTrackRows(
        trackPaths: List<String>,
        expandedHeaderKeys: Set<String> = emptySet()
    ): List<TrackRow> {
        if (trackPaths.isEmpty()) return emptyList()

        var rootTrackCount = 0
        val descendantCountByHeader = mutableMapOf<String, Int>()
        for (path in trackPaths) {
            val segments = visibleTrackSegments(path)
            if (segments.isEmpty()) continue

            val directories = segments.dropLast(1)
            if (directories.isEmpty()) {
                rootTrackCount += 1
            }
            val keySegments = mutableListOf<String>()
            for (directory in directories) {
                keySegments += directory
                val key = keySegments.joinToString("/")
                descendantCountByHeader[key] = (descendantCountByHeader[key] ?: 0) + 1
            }
        }

        val rows = mutableListOf<TrackRow>()
        var emittedRootHeader = false
        val emittedHeaders = mutableSetOf<String>()
        for (path in trackPaths) {
            val segments = visibleTrackSegments(path)
            if (segments.isEmpty()) continue

            val directories = segments.dropLast(1)
            if (directories.isEmpty()) {
                if (!emittedRootHeader) {
                    rows.add(
                        TrackRow.Header(
                            key = ROOT_TRACKS_HEADER_KEY,
                            title = "Tracks",
                            level = 0,
                            expanded = expandedHeaderKeys.contains(ROOT_TRACKS_HEADER_KEY),
                            childCount = rootTrackCount
                        )
                    )
                    emittedRootHeader = true
                }
                if (expandedHeaderKeys.contains(ROOT_TRACKS_HEADER_KEY)) {
                    rows.add(
                        TrackRow.Track(
                            path = path,
                            displayName = path.substringAfterLast('/'),
                            level = 1
                        )
                    )
                }
            } else {
                var parentExpanded = true
                val keySegments = mutableListOf<String>()
                for ((index, directory) in directories.withIndex()) {
                    keySegments += directory
                    val key = keySegments.joinToString("/")
                    if (parentExpanded && emittedHeaders.add(key)) {
                        rows.add(
                            TrackRow.Header(
                                key = key,
                                title = displayDirectoryName(directory),
                                level = index,
                                expanded = expandedHeaderKeys.contains(key),
                                childCount = descendantCountByHeader[key] ?: 0
                            )
                        )
                    }
                    parentExpanded = parentExpanded && expandedHeaderKeys.contains(key)
                }
                if (parentExpanded) {
                    rows.add(
                        TrackRow.Track(
                            path = path,
                            displayName = path.substringAfterLast('/'),
                            level = directories.size
                        )
                    )
                }
            }
        }
        return rows
    }

    fun trackHeaderKeysForPath(trackPath: String?): List<String> {
        if (trackPath.isNullOrBlank()) return emptyList()

        val segments = visibleTrackSegments(trackPath)
        if (segments.isEmpty()) return emptyList()

        val directories = segments.dropLast(1)
        if (directories.isEmpty()) {
            return listOf(ROOT_TRACKS_HEADER_KEY)
        }

        val keys = mutableListOf<String>()
        val keySegments = mutableListOf<String>()
        for (directory in directories) {
            keySegments += directory
            keys += keySegments.joinToString("/")
        }
        return keys
    }

    fun allTrackHeaderKeys(trackPaths: List<String>): Set<String> {
        if (trackPaths.isEmpty()) return emptySet()

        val keys = linkedSetOf<String>()
        for (trackPath in trackPaths) {
            keys.addAll(trackHeaderKeysForPath(trackPath))
        }
        return keys
    }

    fun deepestTrackHeaderKey(trackPath: String?): String? {
        return trackHeaderKeysForPath(trackPath).lastOrNull()
    }

    fun categoryKeyForPath(trackPath: String?): String? {
        val segments = visibleTrackSegments(trackPath.orEmpty())
        val directories = segments.dropLast(1)
        return directories.firstOrNull()
    }

    fun albumKeyForPath(trackPath: String?): String? {
        return deepestTrackHeaderKey(trackPath)
            ?.takeUnless { it == ROOT_TRACKS_HEADER_KEY }
    }

    fun trackHeaderLabel(headerKey: String?): String? {
        if (headerKey.isNullOrBlank()) return null
        if (headerKey == ROOT_TRACKS_HEADER_KEY) return "Tracks"
        return headerKey
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString(" / ") { segment -> displayDirectoryName(segment) }
            .ifBlank { null }
    }

    fun tracksForHeader(
        trackPaths: List<String>,
        headerKey: String?
    ): List<String> {
        if (trackPaths.isEmpty() || headerKey.isNullOrBlank()) return emptyList()

        if (headerKey == ROOT_TRACKS_HEADER_KEY) {
            return trackPaths.filter { visibleTrackSegments(it).dropLast(1).isEmpty() }
        }

        val headerSegments = headerKey
            .split('/')
            .filter { it.isNotBlank() }
        if (headerSegments.isEmpty()) return emptyList()

        return trackPaths.filter { path ->
            val directories = visibleTrackSegments(path).dropLast(1)
            directories.size >= headerSegments.size &&
                directories.take(headerSegments.size) == headerSegments
        }
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

    fun parseTrackVisualOptions(response: String?): List<TrackVisualOption> {
        if (response.isNullOrBlank()) return emptyList()

        return response
            .lineSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                if (!line.startsWith("TRACK_VISUAL=")) return@mapNotNull null
                val payload = line.substringAfter("TRACK_VISUAL=", "").trim()
                val idText = payload.substringBefore('|', "").trim()
                val id = idText.toIntOrNull() ?: return@mapNotNull null
                val name = payload.substringAfter('|', "").trim().ifBlank { "Visual $id" }
                TrackVisualOption(id = id, name = name)
            }
            .distinctBy { it.id }
            .toList()
    }

    fun parseTrackRuntimeState(response: String?): TrackRuntimeState? {
        if (response.isNullOrBlank()) return null

        val fields = parseFieldMap(response)
        val nowPlaying = parseNowPlaying(response)
        val trackActive = fields["TRACK_ACTIVE"]?.toBooleanFlag()
        val trackPaused = fields["TRACK_PAUSED"]?.toBooleanFlag()
        val positionMs = fields["TRACK_POS_MS"]?.toLongOrNull()
        val lengthMs = fields["TRACK_LENGTH_MS"]?.toLongOrNull()
        val policy = fields["TRACK_POLICY"]?.ifBlank { null }
        val sessionMode = fields["TRACK_SESSION_MODE"]?.ifBlank { null }
        val visualSelectedId = fields["TRACK_VISUAL_SELECTED"]?.toIntOrNull()
        val visualName = fields["TRACK_VISUAL_NAME"]?.ifBlank { null }
        val visualActive = fields["TRACK_VISUAL_ACTIVE"]?.toBooleanFlag()
        val visualRejectedReason = fields["TRACK_VISUAL_REJECTED"]?.ifBlank { null }
        val visualActiveId = fields["TRACK_VISUAL_ACTIVE_ID"]?.toIntOrNull()
        val visualActiveName = fields["TRACK_VISUAL_ACTIVE_NAME"]?.ifBlank { null }
        val visualPreviewActive = fields["TRACK_VISUAL_PREVIEW"]?.toBooleanFlag()
        val visualPreviewRejectedReason = fields["TRACK_VISUAL_PREVIEW_REJECTED"]?.ifBlank { null }

        if (
            nowPlaying == null &&
            trackActive == null &&
            trackPaused == null &&
            positionMs == null &&
            lengthMs == null &&
            policy == null &&
            sessionMode == null &&
            visualSelectedId == null &&
            visualName == null &&
            visualActive == null &&
            visualRejectedReason == null &&
            visualActiveId == null &&
            visualActiveName == null &&
            visualPreviewActive == null &&
            visualPreviewRejectedReason == null
        ) {
            return null
        }

        return TrackRuntimeState(
            nowPlaying = nowPlaying,
            trackActive = trackActive,
            trackPaused = trackPaused,
            positionMs = positionMs,
            lengthMs = lengthMs,
            policy = policy,
            sessionMode = sessionMode,
            visualSelectedId = visualSelectedId,
            visualName = visualName,
            visualActive = visualActive,
            visualRejectedReason = visualRejectedReason,
            visualActiveId = visualActiveId,
            visualActiveName = visualActiveName,
            visualPreviewActive = visualPreviewActive,
            visualPreviewRejectedReason = visualPreviewRejectedReason
        )
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

    private fun displayDirectoryName(raw: String): String {
        return raw.replace('_', ' ').ifBlank { raw }
    }

    private fun String.isTrackPath(): Boolean {
        return contains(".wav", ignoreCase = true) &&
            !startsWith("Playing ", ignoreCase = true)
    }

    private fun parseFieldMap(response: String): Map<String, String> {
        return response
            .lineSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                val delimiterIndex = line.indexOf('=')
                if (delimiterIndex <= 0) return@mapNotNull null
                val key = line.substring(0, delimiterIndex).trim()
                val value = line.substring(delimiterIndex + 1).trim()
                if (key.isEmpty()) null else key to value
            }
            .toMap()
    }

    private fun String.toBooleanFlag(): Boolean? {
        return when (trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    private const val ROOT_TRACKS_HEADER_KEY = "__root_tracks__"
    private const val HEADER_PREFIX = "_sub_"
}
