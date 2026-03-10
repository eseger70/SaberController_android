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
        assertEquals(1, result.size)
    }

    @Test
    fun buildPresetRowsExpandsSelectedHeaderChildren() {
        val entries = listOf(
            SaberCommandResponseParser.PresetEntry(index = 0, name = "_sub_Sith"),
            SaberCommandResponseParser.PresetEntry(index = 1, name = "Vader")
        )

        val result = SaberCommandResponseParser.buildPresetRows(entries, setOf(0))

        assertEquals(2, result.size)
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
    fun buildTrackRowsBuildsNestedHierarchy() {
        val result = SaberCommandResponseParser.buildTrackRows(
            listOf(
                "tracks/mars.wav",
                "tracks/Christmas/album_a/song_1.wav",
                "tracks/Christmas/album_a/song_2.wav",
                "Artist_1/tracks/album_d/song_3.wav"
            )
        )

        assertEquals(
            listOf(
                SaberCommandResponseParser.TrackRow.Header("Tracks", 0),
                SaberCommandResponseParser.TrackRow.Track("tracks/mars.wav", "mars.wav", 0),
                SaberCommandResponseParser.TrackRow.Header("Christmas", 0),
                SaberCommandResponseParser.TrackRow.Header("album a", 1),
                SaberCommandResponseParser.TrackRow.Track(
                    "tracks/Christmas/album_a/song_1.wav",
                    "song_1.wav",
                    2
                ),
                SaberCommandResponseParser.TrackRow.Track(
                    "tracks/Christmas/album_a/song_2.wav",
                    "song_2.wav",
                    2
                ),
                SaberCommandResponseParser.TrackRow.Header("Artist 1", 0),
                SaberCommandResponseParser.TrackRow.Header("album d", 1),
                SaberCommandResponseParser.TrackRow.Track(
                    "Artist_1/tracks/album_d/song_3.wav",
                    "song_3.wav",
                    2
                )
            ),
            result
        )
    }

    @Test
    fun parseTrackVisualOptionsReadsIdsAndNames() {
        val result = SaberCommandResponseParser.parseTrackVisualOptions(
            """
            TRACK_VISUAL=0|None
            TRACK_VISUAL=1|Pulse Amber
            TRACK_VISUAL=2|Blue Pulse
            """.trimIndent()
        )

        assertEquals(
            listOf(
                SaberCommandResponseParser.TrackVisualOption(0, "None"),
                SaberCommandResponseParser.TrackVisualOption(1, "Pulse Amber"),
                SaberCommandResponseParser.TrackVisualOption(2, "Blue Pulse")
            ),
            result
        )
    }

    @Test
    fun parseTrackRuntimeStateReadsPolicyAndVisualFields() {
        val result = SaberCommandResponseParser.parseTrackRuntimeState(
            """
            Playing tracks/mars.wav
            TRACK_ACTIVE=1
            TRACK_POLICY=auto
            TRACK_SESSION_MODE=visual
            TRACK_VISUAL_SELECTED=1
            TRACK_VISUAL_NAME=Pulse Amber
            TRACK_VISUAL_ACTIVE=1
            TRACK_VISUAL_ACTIVE_ID=1
            TRACK_VISUAL_ACTIVE_NAME=Pulse Amber
            """.trimIndent()
        )

        assertEquals("tracks/mars.wav", result?.nowPlaying)
        assertEquals(true, result?.trackActive)
        assertEquals("auto", result?.policy)
        assertEquals("visual", result?.sessionMode)
        assertEquals(1, result?.visualSelectedId)
        assertEquals("Pulse Amber", result?.visualName)
        assertEquals(true, result?.visualActive)
        assertEquals(1, result?.visualActiveId)
        assertEquals("Pulse Amber", result?.visualActiveName)
    }

    @Test
    fun parseTrackRuntimeStateReadsVisualRejection() {
        val result = SaberCommandResponseParser.parseTrackRuntimeState(
            """
            TRACK_VISUAL_REJECTED=blade_on
            TRACK_POLICY=visual
            TRACK_SESSION_MODE=none
            TRACK_VISUAL_SELECTED=1
            TRACK_VISUAL_NAME=Pulse Amber
            TRACK_VISUAL_ACTIVE=0
            """.trimIndent()
        )

        assertEquals("blade_on", result?.visualRejectedReason)
        assertEquals("visual", result?.policy)
        assertEquals("none", result?.sessionMode)
        assertEquals(false, result?.visualActive)
    }

    @Test
    fun parseTrackRuntimeStateReadsPreviewFields() {
        val result = SaberCommandResponseParser.parseTrackRuntimeState(
            """
            TRACK_VISUAL_SELECTED=2
            TRACK_VISUAL_NAME=Blue Pulse
            TRACK_VISUAL_ACTIVE=1
            TRACK_VISUAL_PREVIEW=1
            """.trimIndent()
        )

        assertEquals(2, result?.visualSelectedId)
        assertEquals("Blue Pulse", result?.visualName)
        assertEquals(true, result?.visualActive)
        assertEquals(true, result?.visualPreviewActive)
    }
}
