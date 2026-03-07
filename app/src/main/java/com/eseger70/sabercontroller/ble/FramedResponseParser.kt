package com.eseger70.sabercontroller.ble

class FramedResponseParser(
    private val beginMarker: String = "-+=BEGIN_OUTPUT=+-",
    private val endMarker: String = "-+=END_OUTPUT=+-",
    private val maxBufferChars: Int = 64 * 1024
) {
    private val buffer = StringBuilder()

    fun consume(chunk: String): List<String> {
        if (chunk.isEmpty()) return emptyList()
        buffer.append(chunk)

        val frames = mutableListOf<String>()
        while (true) {
            val start = buffer.indexOf(beginMarker)
            if (start < 0) {
                trimIfTooLarge()
                break
            }

            val afterStart = start + beginMarker.length
            val end = buffer.indexOf(endMarker, afterStart)
            if (end < 0) {
                if (start > 0) {
                    buffer.delete(0, start)
                }
                trimIfTooLarge()
                break
            }

            val frame = buffer.substring(afterStart, end).trim()
            frames.add(frame)
            buffer.delete(0, end + endMarker.length)
        }

        return frames
    }

    fun clear() {
        buffer.clear()
    }

    private fun trimIfTooLarge() {
        if (buffer.length <= maxBufferChars) return
        val keepFrom = buffer.length - maxBufferChars
        buffer.delete(0, keepFrom)
    }
}

