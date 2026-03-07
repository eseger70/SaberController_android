package com.eseger70.sabercontroller.ble

object SaberCommandResponseParser {
    fun parseBladeState(response: String?): Boolean? {
        val normalized = response
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it == "0" || it == "1" }
            ?: return null

        return normalized == "1"
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

    private fun String.isTrackPath(): Boolean {
        return contains(".wav", ignoreCase = true) &&
            !startsWith("Playing ", ignoreCase = true)
    }
}
