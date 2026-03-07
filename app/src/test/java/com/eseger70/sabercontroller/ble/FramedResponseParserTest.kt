package com.eseger70.sabercontroller.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramedResponseParserTest {
    @Test
    fun consumeReassemblesFragmentedFrame() {
        val parser = FramedResponseParser()

        val first = parser.consume("-+=BEGIN_OUTPUT=+-\ntracks/a")
        val second = parser.consume(".wav\n-+=END_OUTPUT=+-\n")

        assertTrue(first.isEmpty())
        assertEquals(listOf("tracks/a.wav"), second)
    }

    @Test
    fun consumeReturnsMultipleFramesInOrder() {
        val parser = FramedResponseParser()

        val result = parser.consume(
            """
            -+=BEGIN_OUTPUT=+-
            1
            -+=END_OUTPUT=+-
            -+=BEGIN_OUTPUT=+-
            tracks/theme.wav
            -+=END_OUTPUT=+-
            """.trimIndent()
        )

        assertEquals(listOf("1", "tracks/theme.wav"), result)
    }
}

