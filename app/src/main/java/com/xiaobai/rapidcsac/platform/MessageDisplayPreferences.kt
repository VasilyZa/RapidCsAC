package com.xiaobai.rapidcsac.platform

import android.content.SharedPreferences
import androidx.core.content.edit
import com.xiaobai.rapidcsac.core.format.GroupSenderFormatter
import com.xiaobai.rapidcsac.core.format.MessageDisplayStyle
import com.xiaobai.rapidcsac.core.format.MessageTimeFormatter
import com.xiaobai.rapidcsac.core.format.MessageTimeStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class MessageDisplayPreferences(private val preferences: SharedPreferences) {
    private val mutableStyle = MutableStateFlow(read())
    val style = mutableStyle.asStateFlow()

    fun update(style: MessageDisplayStyle) {
        validate(style)
        val time = style.time
        val value =
            JSONObject()
                .put("today", time.todayPattern)
                .put("recent", time.recentPattern)
                .put("current_year", time.currentYearPattern)
                .put("other_year", time.otherYearPattern)
                .put("recent_days", time.recentDays)
                .put("today_label", time.todayLabel)
                .put("yesterday_label", time.yesterdayLabel)
                .put("day_before_label", time.dayBeforeLabel)
                .put("days_ago_label", time.daysAgoLabel)
                .put("sender", style.senderPattern)
        preferences.edit { putString("message_display", value.toString()) }
        mutableStyle.value = style
    }

    private fun read(): MessageDisplayStyle = runCatching {
        val defaults = MessageDisplayStyle()
        val value = JSONObject(preferences.getString("message_display", "{}") ?: "{}")
        MessageDisplayStyle(
                time =
                    MessageTimeStyle(
                        todayPattern = value.optString("today", defaults.time.todayPattern),
                        recentPattern = value.optString("recent", defaults.time.recentPattern),
                        currentYearPattern =
                            value.optString("current_year", defaults.time.currentYearPattern),
                        otherYearPattern =
                            value.optString("other_year", defaults.time.otherYearPattern),
                        recentDays = value.optInt("recent_days", defaults.time.recentDays),
                        todayLabel = value.optString("today_label", defaults.time.todayLabel),
                        yesterdayLabel =
                            value.optString("yesterday_label", defaults.time.yesterdayLabel),
                        dayBeforeLabel =
                            value.optString("day_before_label", defaults.time.dayBeforeLabel),
                        daysAgoLabel =
                            value.optString("days_ago_label", defaults.time.daysAgoLabel),
                    ),
                senderPattern = value.optString("sender", defaults.senderPattern),
            )
            .also(::validate)
    }
        .getOrDefault(MessageDisplayStyle())

    private fun validate(style: MessageDisplayStyle) {
        val time = style.time
        require(time.recentDays in 0..365) { "相对日期天数需为 0–365" }
        listOf(time.todayLabel, time.yesterdayLabel, time.dayBeforeLabel, time.daysAgoLabel)
            .forEach {
                require(it.length <= 64) { "相对日期文字最多 64 个字符" }
            }
        listOf(
                time.todayPattern,
                time.recentPattern,
                time.currentYearPattern,
                time.otherYearPattern,
            )
            .forEach {
                val error = MessageTimeFormatter.patternError(it)
                require(error == null) { error.orEmpty() }
            }
        val error = GroupSenderFormatter.patternError(style.senderPattern)
        require(error == null) { error.orEmpty() }
    }
}
