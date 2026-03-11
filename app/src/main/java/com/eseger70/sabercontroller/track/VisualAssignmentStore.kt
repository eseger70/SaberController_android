package com.eseger70.sabercontroller.track

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class VisualAssignmentStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun loadRules(): List<VisualAssignmentRule> {
        val raw = preferences.getString(KEY_RULES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val scopeName = item.optString("scope")
                val scope = runCatching { VisualAssignmentScope.valueOf(scopeName) }.getOrNull() ?: continue
                val scopeKey = item.optString("scopeKey").trim()
                val visualId = item.optInt("visualId", -1)
                if (scopeKey.isBlank() || visualId < 0) continue
                add(
                    VisualAssignmentRule(
                        scope = scope,
                        scopeKey = scopeKey,
                        visualId = visualId
                    )
                )
            }
        }
    }

    fun saveRules(rules: List<VisualAssignmentRule>) {
        val payload = JSONArray().apply {
            rules.forEach { rule ->
                put(
                    JSONObject().apply {
                        put("scope", rule.scope.name)
                        put("scopeKey", rule.scopeKey)
                        put("visualId", rule.visualId)
                    }
                )
            }
        }
        preferences.edit().putString(KEY_RULES, payload.toString()).apply()
    }

    companion object {
        private const val PREF_NAME = "visual_assignments"
        private const val KEY_RULES = "rules"
    }
}
