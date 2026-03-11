package com.eseger70.sabercontroller.track

import com.eseger70.sabercontroller.ble.SaberCommandResponseParser

object VisualAssignmentResolver {
    data class MatchResult(
        val visualId: Int?,
        val matchedScope: VisualAssignmentScope?,
        val matchedScopeKey: String?
    )

    fun resolve(
        trackPath: String?,
        overrideVisualId: Int?,
        rules: List<VisualAssignmentRule>
    ): MatchResult {
        if ((overrideVisualId ?: 0) > 0) {
            return MatchResult(
                visualId = overrideVisualId,
                matchedScope = null,
                matchedScopeKey = null
            )
        }

        val normalizedPath = trackPath?.trim().orEmpty()
        if (normalizedPath.isBlank()) {
            return resolveDefault(rules)
        }

        val candidates = listOfNotNull(
            VisualAssignmentScope.TRACK to normalizedPath,
            SaberCommandResponseParser.albumKeyForPath(normalizedPath)?.let {
                VisualAssignmentScope.ALBUM to it
            },
            SaberCommandResponseParser.categoryKeyForPath(normalizedPath)?.let {
                VisualAssignmentScope.CATEGORY to it
            },
            VisualAssignmentScope.DEFAULT to DEFAULT_SCOPE_KEY
        )

        for ((scope, key) in candidates) {
            val match = rules.firstOrNull { it.scope == scope && it.scopeKey == key }
            if (match != null) {
                return MatchResult(
                    visualId = match.visualId,
                    matchedScope = scope,
                    matchedScopeKey = key
                )
            }
        }

        return MatchResult(
            visualId = null,
            matchedScope = null,
            matchedScopeKey = null
        )
    }

    fun scopeKeyForTrack(scope: VisualAssignmentScope, trackPath: String?): String? {
        val normalizedPath = trackPath?.trim().orEmpty()
        if (normalizedPath.isBlank()) return null

        return when (scope) {
            VisualAssignmentScope.TRACK -> normalizedPath
            VisualAssignmentScope.ALBUM -> SaberCommandResponseParser.albumKeyForPath(normalizedPath)
            VisualAssignmentScope.CATEGORY -> SaberCommandResponseParser.categoryKeyForPath(normalizedPath)
            VisualAssignmentScope.DEFAULT -> DEFAULT_SCOPE_KEY
        }
    }

    private fun resolveDefault(rules: List<VisualAssignmentRule>): MatchResult {
        val match = rules.firstOrNull {
            it.scope == VisualAssignmentScope.DEFAULT && it.scopeKey == DEFAULT_SCOPE_KEY
        }
        return MatchResult(
            visualId = match?.visualId,
            matchedScope = match?.scope,
            matchedScopeKey = match?.scopeKey
        )
    }

    const val DEFAULT_SCOPE_KEY = "__default__"
}
