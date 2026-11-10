package com.xiaobai.rapidcsac.platform

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class PersonalizationPreferences(private val preferences: SharedPreferences) {
    private val mutableStyle =
        MutableStateFlow(
            runCatching {
                    Personalization.fromJson(
                        JSONObject(preferences.getString("personalization", "{}") ?: "{}")
                    )
                }
                .getOrDefault(Personalization())
        )
    val style = mutableStyle.asStateFlow()

    @Synchronized
    fun update(change: (Personalization) -> Personalization) {
        val next = change(mutableStyle.value)
        next.validate()
        preferences.edit { putString("personalization", next.toJson().toString()) }
        mutableStyle.value = next
    }
}
