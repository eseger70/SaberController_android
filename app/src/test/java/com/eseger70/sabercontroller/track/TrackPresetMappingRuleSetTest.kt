package com.eseger70.sabercontroller.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackPresetMappingRuleSetTest {

    @Test
    fun `track context extracts category and album from nested path`() {
        val context = TrackContext.fromPath("tracks/Artist_1/album_d/song_1.wav")

        assertEquals("tracks/artist_1/album_d/song_1.wav", context?.path)
        assertEquals("song_1.wav", context?.fileName)
        assertEquals("artist_1", context?.categoryKey)
        assertEquals("artist_1/album_d", context?.albumKey)
    }

    @Test
    fun `resolve prefers track over album category and default`() {
        val ruleSet = TrackPresetMappingRuleSet(
            defaultPresetIndex = 1,
            categoryPresetIndices = mapOf("artist_1" to 2),
            albumPresetIndices = mapOf("artist_1/album_d" to 3),
            trackPresetIndices = mapOf("tracks/artist_1/album_d/song_1.wav" to 4)
        )

        val resolved = ruleSet.resolve("tracks/Artist_1/album_d/song_1.wav")

        assertEquals(MappingScope.TRACK, resolved?.scope)
        assertEquals(4, resolved?.presetIndex)
    }

    @Test
    fun `resolve falls back to album then category then default`() {
        val ruleSet = TrackPresetMappingRuleSet(
            defaultPresetIndex = 5,
            categoryPresetIndices = mapOf("christmas" to 6),
            albumPresetIndices = mapOf("christmas/album_a" to 7)
        )

        val albumResolved = ruleSet.resolve("tracks/Christmas/album_a/song.wav")
        val categoryResolved = ruleSet.resolve("tracks/Christmas/song.wav")
        val defaultResolved = ruleSet.resolve("tracks/root.wav")

        assertEquals(MappingScope.ALBUM, albumResolved?.scope)
        assertEquals(7, albumResolved?.presetIndex)
        assertEquals(MappingScope.CATEGORY, categoryResolved?.scope)
        assertEquals(6, categoryResolved?.presetIndex)
        assertEquals(MappingScope.DEFAULT, defaultResolved?.scope)
        assertEquals(5, defaultResolved?.presetIndex)
    }

    @Test
    fun `resolve returns null when no mapping applies`() {
        val resolved = TrackPresetMappingRuleSet().resolve("tracks/theme.wav")

        assertNull(resolved)
    }
}
