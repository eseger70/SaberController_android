package com.eseger70.sabercontroller.track

import android.content.Context

class TrackPresetMappingStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadRuleSet(): TrackPresetMappingRuleSet {
        val categoryPresetIndices = linkedMapOf<String, Int>()
        val albumPresetIndices = linkedMapOf<String, Int>()
        val trackPresetIndices = linkedMapOf<String, Int>()
        var defaultPresetIndex: Int? = null

        for ((key, value) in preferences.all) {
            val presetIndex = (value as? Number)?.toInt() ?: continue
            when {
                key == KEY_DEFAULT -> defaultPresetIndex = presetIndex
                key.startsWith(PREFIX_CATEGORY) -> {
                    categoryPresetIndices[key.removePrefix(PREFIX_CATEGORY)] = presetIndex
                }
                key.startsWith(PREFIX_ALBUM) -> {
                    albumPresetIndices[key.removePrefix(PREFIX_ALBUM)] = presetIndex
                }
                key.startsWith(PREFIX_TRACK) -> {
                    trackPresetIndices[key.removePrefix(PREFIX_TRACK)] = presetIndex
                }
            }
        }

        return TrackPresetMappingRuleSet(
            defaultPresetIndex = defaultPresetIndex,
            categoryPresetIndices = categoryPresetIndices,
            albumPresetIndices = albumPresetIndices,
            trackPresetIndices = trackPresetIndices
        )
    }

    fun setMapping(scope: MappingScope, key: String? = null, presetIndex: Int?) {
        val editor = preferences.edit()
        val storageKey = storageKey(scope, key) ?: return
        if (presetIndex == null) {
            editor.remove(storageKey)
        } else {
            editor.putInt(storageKey, presetIndex)
        }
        editor.apply()
    }

    private fun storageKey(scope: MappingScope, key: String?): String? {
        return when (scope) {
            MappingScope.DEFAULT -> KEY_DEFAULT
            MappingScope.CATEGORY -> TrackPresetMappingRuleSet.normalizeTrackKey(key)?.let { PREFIX_CATEGORY + it }
            MappingScope.ALBUM -> TrackPresetMappingRuleSet.normalizeTrackKey(key)?.let { PREFIX_ALBUM + it }
            MappingScope.TRACK -> TrackPresetMappingRuleSet.normalizeTrackKey(key)?.let { PREFIX_TRACK + it }
        }
    }

    companion object {
        private const val PREFS_NAME = "track_preset_mappings"
        private const val KEY_DEFAULT = "default_preset_index"
        private const val PREFIX_CATEGORY = "category::"
        private const val PREFIX_ALBUM = "album::"
        private const val PREFIX_TRACK = "track::"
    }
}
