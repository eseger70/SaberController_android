package com.eseger70.sabercontroller.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun parsePresetEntriesBuildsHeaderAndChildPresets() {
        val result = SaberCommandResponseParser.parsePresetEntries(
            """
            FONT="Header"
            TRACK="tracks/header.wav"
            NAME="_sub_Prequel_Era"
            VARIATION=0
            FONT="ObiWan"
            TRACK="tracks/obi.wav"
            NAME="Obi-Wan"
            VARIATION=0
            FONT="Anakin"
            TRACK="tracks/ani.wav"
            NAME="Anakin"
            VARIATION=0
            """.trimIndent()
        )

        assertEquals(3, result.size)
        assertTrue(result[0].isHeader)
        assertEquals("Prequel Era", result[0].displayName)
        assertEquals("Obi-Wan", result[1].displayName)
        assertEquals(1, result[1].index)
    }

    @Test
    fun buildPresetRowsPreservesHeaderRows() {
        val entries = listOf(
            SaberCommandResponseParser.PresetEntry(index = 0, name = "_sub_Sith"),
            SaberCommandResponseParser.PresetEntry(index = 1, name = "Vader")
        )

        val result = SaberCommandResponseParser.buildPresetRows(entries)

        assertTrue(result[0] is SaberCommandResponseParser.PresetRow.Header)
        assertTrue(result[1] is SaberCommandResponseParser.PresetRow.Preset)
    }

    @Test
    fun parseCurrentPresetIndexReadsIntegerLine() {
        val result = SaberCommandResponseParser.parseCurrentPresetIndex(
            """
            12
            """.trimIndent()
        )

        assertEquals(12, result)
    }

    @Test
    fun parseVolumeReadsNumericLine() {
        val result = SaberCommandResponseParser.parseVolume("2048")

        assertEquals(2048, result)
    }

    @Test
    fun buildTrackRowsGroupsTracksByFolder() {
        val result = SaberCommandResponseParser.buildTrackRows(
            listOf(
                "tracks/mars.wav",
                "tracks/duel.wav",
                "Fonts/ObiWan/tracks/theme.wav"
            )
        )

        assertEquals(
            listOf(
                SaberCommandResponseParser.TrackRow.Header("Tracks"),
                SaberCommandResponseParser.TrackRow.Track("tracks/mars.wav", "mars.wav"),
                SaberCommandResponseParser.TrackRow.Track("tracks/duel.wav", "duel.wav"),
                SaberCommandResponseParser.TrackRow.Header("Fonts/ObiWan"),
                SaberCommandResponseParser.TrackRow.Track("Fonts/ObiWan/tracks/theme.wav", "theme.wav")
            ),
            result
        )
    }
}
