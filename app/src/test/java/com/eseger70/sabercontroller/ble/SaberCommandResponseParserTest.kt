package com.eseger70.sabercontroller.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaberCommandResponseParserTest {
    @Test
    fun parseBladeStateReadsOneAsOn() {
        val result = SaberCommandResponseParser.parseBladeState("1")

        assertEquals(true, result)
    }

    @Test
    fun parseBladeStateReadsZeroAsOff() {
        val result = SaberCommandResponseParser.parseBladeState("0")

        assertEquals(false, result)
    }

    @Test
    fun parseTrackPathsFiltersNonTrackLines() {
        val result = SaberCommandResponseParser.parseTrackPaths(
            """
            tracks/alpha.wav
            Playing tracks/beta.wav
            misc.txt
            Fonts/ObiWan/tracks/theme.wav
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "tracks/alpha.wav",
                "Fonts/ObiWan/tracks/theme.wav"
            ),
            result
        )
    }

    @Test
    fun parseNowPlayingReturnsPathFromPlayingLine() {
        val result = SaberCommandResponseParser.parseNowPlaying("Playing tracks/theme.wav")

        assertEquals("tracks/theme.wav", result)
    }

    @Test
    fun parseNowPlayingReturnsNullForBlankResponse() {
        val result = SaberCommandResponseParser.parseNowPlaying("")

        assertNull(result)
    }

    @Test
    fun parseNowPlayingIgnoresNonTrackStatusLines() {
        val result = SaberCommandResponseParser.parseNowPlaying("No available WAV players.")

        assertNull(result)
    }
}
