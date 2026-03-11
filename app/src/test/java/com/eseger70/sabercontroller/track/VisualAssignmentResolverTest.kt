package com.eseger70.sabercontroller.track

import org.junit.Assert.assertEquals
import org.junit.Test

class VisualAssignmentResolverTest {
    @Test
    fun resolvePrefersSessionOverride() {
        val result = VisualAssignmentResolver.resolve(
            trackPath = "tracks/Christmas/album_a/song_1.wav",
            overrideVisualId = 3,
            rules = listOf(
                VisualAssignmentRule(VisualAssignmentScope.TRACK, "tracks/Christmas/album_a/song_1.wav", 1),
                VisualAssignmentRule(VisualAssignmentScope.DEFAULT, VisualAssignmentResolver.DEFAULT_SCOPE_KEY, 2)
            )
        )

        assertEquals(3, result.visualId)
        assertEquals(null, result.matchedScope)
    }

    @Test
    fun resolveUsesTrackAlbumCategoryDefaultPrecedence() {
        val rules = listOf(
            VisualAssignmentRule(VisualAssignmentScope.DEFAULT, VisualAssignmentResolver.DEFAULT_SCOPE_KEY, 4),
            VisualAssignmentRule(VisualAssignmentScope.CATEGORY, "Christmas", 3),
            VisualAssignmentRule(VisualAssignmentScope.ALBUM, "Christmas/album_a", 2),
            VisualAssignmentRule(
                VisualAssignmentScope.TRACK,
                "tracks/Christmas/album_a/song_1.wav",
                1
            )
        )

        val result = VisualAssignmentResolver.resolve(
            trackPath = "tracks/Christmas/album_a/song_1.wav",
            overrideVisualId = null,
            rules = rules
        )

        assertEquals(1, result.visualId)
        assertEquals(VisualAssignmentScope.TRACK, result.matchedScope)
    }

    @Test
    fun resolveFallsBackToAlbumThenCategoryThenDefault() {
        val rules = listOf(
            VisualAssignmentRule(VisualAssignmentScope.DEFAULT, VisualAssignmentResolver.DEFAULT_SCOPE_KEY, 4),
            VisualAssignmentRule(VisualAssignmentScope.CATEGORY, "Christmas", 3),
            VisualAssignmentRule(VisualAssignmentScope.ALBUM, "Christmas/album_a", 2)
        )

        val albumResult = VisualAssignmentResolver.resolve(
            trackPath = "tracks/Christmas/album_a/song_2.wav",
            overrideVisualId = null,
            rules = rules
        )
        val categoryResult = VisualAssignmentResolver.resolve(
            trackPath = "tracks/Christmas/album_b/song_3.wav",
            overrideVisualId = null,
            rules = rules
        )
        val defaultResult = VisualAssignmentResolver.resolve(
            trackPath = "tracks/root_song.wav",
            overrideVisualId = null,
            rules = rules
        )

        assertEquals(2, albumResult.visualId)
        assertEquals(VisualAssignmentScope.ALBUM, albumResult.matchedScope)
        assertEquals(3, categoryResult.visualId)
        assertEquals(VisualAssignmentScope.CATEGORY, categoryResult.matchedScope)
        assertEquals(4, defaultResult.visualId)
        assertEquals(VisualAssignmentScope.DEFAULT, defaultResult.matchedScope)
    }
}
