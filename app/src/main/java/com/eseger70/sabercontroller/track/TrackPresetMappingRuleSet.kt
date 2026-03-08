package com.eseger70.sabercontroller.track

import java.util.Locale

data class TrackContext(
    val path: String,
    val fileName: String,
    val categoryKey: String?,
    val albumKey: String?
) {
    companion object {
        fun fromPath(trackPath: String?): TrackContext? {
            val normalized = TrackPresetMappingRuleSet.normalizeTrackKey(trackPath) ?: return null
            val segments = normalized
                .split('/')
                .filter { it.isNotBlank() }
                .filterNot { it.equals("tracks", ignoreCase = true) }
            if (segments.isEmpty()) return null

            val directories = segments.dropLast(1)
            return TrackContext(
                path = normalized,
                fileName = segments.last(),
                categoryKey = directories.firstOrNull(),
                albumKey = directories.joinToString("/").ifBlank { null }
            )
        }
    }
}

enum class MappingScope {
    DEFAULT,
    CATEGORY,
    ALBUM,
    TRACK
}

data class ResolvedTrackPresetMapping(
    val scope: MappingScope,
    val key: String?,
    val presetIndex: Int
)

data class TrackPresetMappingRuleSet(
    val defaultPresetIndex: Int? = null,
    val categoryPresetIndices: Map<String, Int> = emptyMap(),
    val albumPresetIndices: Map<String, Int> = emptyMap(),
    val trackPresetIndices: Map<String, Int> = emptyMap()
) {
    fun mappingFor(scope: MappingScope, key: String? = null): Int? {
        return when (scope) {
            MappingScope.DEFAULT -> defaultPresetIndex
            MappingScope.CATEGORY -> normalizeTrackKey(key)?.let(categoryPresetIndices::get)
            MappingScope.ALBUM -> normalizeTrackKey(key)?.let(albumPresetIndices::get)
            MappingScope.TRACK -> normalizeTrackKey(key)?.let(trackPresetIndices::get)
        }
    }

    fun resolve(trackPath: String?): ResolvedTrackPresetMapping? {
        val context = TrackContext.fromPath(trackPath)
        val normalizedTrack = normalizeTrackKey(trackPath)

        if (normalizedTrack != null) {
            trackPresetIndices[normalizedTrack]?.let {
                return ResolvedTrackPresetMapping(MappingScope.TRACK, normalizedTrack, it)
            }
        }
        context?.albumKey?.let { albumKey ->
            albumPresetIndices[albumKey]?.let {
                return ResolvedTrackPresetMapping(MappingScope.ALBUM, albumKey, it)
            }
        }
        context?.categoryKey?.let { categoryKey ->
            categoryPresetIndices[categoryKey]?.let {
                return ResolvedTrackPresetMapping(MappingScope.CATEGORY, categoryKey, it)
            }
        }
        defaultPresetIndex?.let {
            return ResolvedTrackPresetMapping(MappingScope.DEFAULT, null, it)
        }
        return null
    }

    companion object {
        fun normalizeTrackKey(raw: String?): String? {
            val cleaned = raw
                ?.replace('\\', '/')
                ?.trim()
                ?.trim('/')
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            return cleaned.lowercase(Locale.US)
        }
    }
}
