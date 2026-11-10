package com.xiaobai.rapidcsac.core.format

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class MessageTimeStyle(
    val todayPattern: String = "{relative} HH:mm",
    val recentPattern: String = "{relative} HH:mm",
    val currentYearPattern: String = "MM-dd HH:mm",
    val otherYearPattern: String = "yyyy-MM-dd HH:mm",
    val recentDays: Int = 2,
    val todayLabel: String = "今天",
    val yesterdayLabel: String = "昨天",
    val dayBeforeLabel: String = "前天",
    val daysAgoLabel: String = "{days}天前",
)

data class MessageDisplayStyle(
    val time: MessageTimeStyle = MessageTimeStyle(),
    val senderPattern: String = "{name}[[ · {title}]]",
)

object MessageDisplayPresets {
    val times =
        listOf(
            "智能日期" to MessageTimeStyle(),
            "简洁日期" to
                MessageTimeStyle(
                    todayPattern = "HH:mm",
                    currentYearPattern = "M月d日 HH:mm",
                    otherYearPattern = "yyyy年M月d日 HH:mm",
                ),
            "完整日期" to
                MessageTimeStyle(
                    todayPattern = "yyyy-MM-dd HH:mm",
                    recentPattern = "yyyy-MM-dd HH:mm",
                    currentYearPattern = "yyyy-MM-dd HH:mm",
                    otherYearPattern = "yyyy-MM-dd HH:mm",
                    recentDays = 0,
                ),
            "精确到秒" to
                MessageTimeStyle(
                    todayPattern = "{relative} HH:mm:ss",
                    recentPattern = "{relative} HH:mm:ss",
                    currentYearPattern = "MM-dd HH:mm:ss",
                    otherYearPattern = "yyyy-MM-dd HH:mm:ss",
                ),
        )
    val senders =
        listOf(
            "昵称 · 称号" to "{name}[[ · {title}]]",
            "称号在前" to "[[【{title}】 ]]{name}",
            "昵称（称号）" to "{name}[[（{title}）]]",
            "仅昵称" to "{name}",
            "昵称与等级" to "{name}[[ · Lv.{level}]]",
        )
}

/** Immutable compiled templates can be shared across readers. Date formatters stay thread-local. */
class MessageTimeFormatter(
    private val style: MessageTimeStyle,
    nowMillis: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
    locale: Locale = Locale.getDefault(),
) {
    private val zone = timeZone.clone() as TimeZone
    private val today = localDay(nowMillis, zone)
    private val yearStart =
        Calendar.getInstance(zone, locale).apply {
            timeInMillis = nowMillis
            set(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    private val startMillis = yearStart.timeInMillis
    private val endMillis =
        (yearStart.clone() as Calendar).apply { add(Calendar.YEAR, 1) }.timeInMillis
    private val todayFormat = TimeTemplate(style.todayPattern, zone, locale)
    private val recentFormat = TimeTemplate(style.recentPattern, zone, locale)
    private val currentYearFormat = TimeTemplate(style.currentYearPattern, zone, locale)
    private val otherYearFormat = TimeTemplate(style.otherYearPattern, zone, locale)

    fun format(epochSeconds: Long): String {
        if (epochSeconds <= 0 || epochSeconds > 253_402_300_799L) return "时间未知"
        val millis = epochSeconds * 1000
        val days = today - localDay(millis, zone)
        val label =
            when (days) {
                0L -> style.todayLabel
                1L -> style.yesterdayLabel
                2L -> style.dayBeforeLabel
                else -> style.daysAgoLabel.replace("{days}", days.toString())
            }
        val template =
            when {
                days == 0L -> todayFormat
                days in 1..style.recentDays.toLong() -> recentFormat
                millis >= startMillis && millis < endMillis -> currentYearFormat
                else -> otherYearFormat
            }
        return template.format(Date(millis), label, days).trim()
    }

    companion object {
        fun localDay(millis: Long, zone: TimeZone): Long =
            Math.floorDiv(millis + zone.getOffset(millis), 86_400_000L)

        fun patternError(pattern: String): String? = runCatching {
            TimeTemplate(pattern, TimeZone.getTimeZone("UTC"), Locale.ROOT)
        }.exceptionOrNull()?.message
    }
}

private sealed interface TimeToken {
    data class Code(val value: String) : TimeToken

    data class Literal(val value: String) : TimeToken

    data class Variable(val value: String) : TimeToken
}

private val dateCodes =
    Regex("yyyy|yy|MMMM|MMM|MM|M|mm|m|dd|d|HH|H|hh|h|SSS|ss|s|EEEE|EEE|EE|E|a|XXX|XX|X|Z")

private fun validateLength(pattern: String) {
    require(pattern.isNotBlank()) { "请输入格式" }
    require(pattern.length <= 256) { "格式最多 256 个字符" }
}

/** Unknown words are literal text. Lowercase m means a month unless beside a clock field. */
private fun timeTokens(pattern: String): List<TimeToken> {
    validateLength(pattern)
    val tokens = mutableListOf<TimeToken>()
    var index = 0
    while (index < pattern.length) {
        val char = pattern[index]
        when {
            char == '\'' -> {
                if (pattern.getOrNull(index + 1) == '\'') {
                    tokens += TimeToken.Literal("'")
                    index += 2
                    continue
                }
                val literal = StringBuilder()
                var closed = false
                index++
                while (index < pattern.length) {
                    if (pattern[index] == '\'') {
                        if (pattern.getOrNull(index + 1) == '\'') {
                            literal.append('\'')
                            index += 2
                            continue
                        }
                        closed = true
                        index++
                        break
                    }
                    literal.append(pattern[index++])
                }
                require(closed) { "单引号尚未闭合" }
                tokens += TimeToken.Literal(literal.toString())
            }
            char == '{' -> {
                val end = pattern.indexOf('}', index + 1)
                require(end >= 0) { "占位符尚未闭合" }
                val variable = pattern.substring(index + 1, end)
                require(variable in setOf("relative", "days")) { "支持的占位符为 {relative} 和 {days}" }
                tokens += TimeToken.Variable(variable)
                index = end + 1
            }
            char == '}' -> throw IllegalArgumentException("存在多余的右花括号")
            char in 'a'..'z' || char in 'A'..'Z' -> {
                val start = index
                while (
                    index < pattern.length &&
                        (pattern[index] in 'a'..'z' || pattern[index] in 'A'..'Z')
                ) index++
                val word = pattern.substring(start, index)
                val codes = dateCodes.findAll(word).map { it.value }.toList()
                if (codes.joinToString("") == word) tokens += codes.map { TimeToken.Code(it) }
                else tokens += TimeToken.Literal(word)
            }
            else -> {
                tokens += TimeToken.Literal(char.toString())
                index++
            }
        }
    }
    return tokens
}

private sealed interface TimePart {
    class Pattern(pattern: String, zone: TimeZone, locale: Locale) : TimePart {
        private val prototype = SimpleDateFormat(pattern, locale).apply { timeZone = zone }
        val formats =
            object : ThreadLocal<SimpleDateFormat>() {
                override fun initialValue() = prototype.clone() as SimpleDateFormat
            }
    }

    data class Variable(val name: String) : TimePart
}

private class TimeTemplate(pattern: String, zone: TimeZone, locale: Locale) {
    private val parts = mutableListOf<TimePart>()

    init {
        val tokens = timeTokens(pattern)
        val code = StringBuilder()
        val literal = StringBuilder()
        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                code.append('\'').append(literal.toString().replace("'", "''")).append('\'')
                literal.clear()
            }
        }
        fun flushPattern() {
            flushLiteral()
            if (code.isNotEmpty()) {
                parts += TimePart.Pattern(code.toString(), zone, locale)
                code.clear()
            }
        }
        var previousCode = ""
        tokens.forEachIndexed { index, token ->
            when (token) {
                is TimeToken.Literal -> literal.append(token.value)
                is TimeToken.Variable -> {
                    flushPattern()
                    parts += TimePart.Variable(token.value)
                }
                is TimeToken.Code -> {
                    flushLiteral()
                    val nextCode =
                        tokens
                            .drop(index + 1)
                            .filterIsInstance<TimeToken.Code>()
                            .firstOrNull()
                            ?.value
                    val minute =
                        previousCode.startsWith('H') ||
                            previousCode.startsWith('h') ||
                            nextCode?.startsWith('s') == true
                    code.append(
                        if (token.value in setOf("m", "mm") && !minute)
                            token.value.uppercase(Locale.ROOT)
                        else token.value
                    )
                    previousCode = token.value
                }
            }
        }
        flushPattern()
    }

    fun format(date: Date, relative: String, days: Long): String = buildString {
        parts.forEach { part ->
            when (part) {
                is TimePart.Pattern -> append(requireNotNull(part.formats.get()).format(date))
                is TimePart.Variable ->
                    append(if (part.name == "relative") relative else days.toString())
            }
        }
    }
}

private val senderFields = Regex("\\{(name|title|uid|level)\\}")
private val optionalSender = Regex("\\[\\[(.*?)\\]\\]", RegexOption.DOT_MATCHES_ALL)

class GroupSenderFormatter(pattern: String) {
    private data class Part(val text: String, val optional: Boolean)

    private val parts = mutableListOf<Part>()

    init {
        require(patternError(pattern) == null) { patternError(pattern).orEmpty() }
        var end = 0
        optionalSender.findAll(pattern).forEach { block ->
            parts += Part(pattern.substring(end, block.range.first), false)
            parts += Part(block.groupValues[1], true)
            end = block.range.last + 1
        }
        parts += Part(pattern.substring(end), false)
    }

    fun format(name: String, title: String, uid: Long, level: String = ""): String {
        val values =
            mapOf(
                "name" to name.ifBlank { "UID $uid" },
                "title" to title,
                "uid" to uid.toString(),
                "level" to level,
            )
        return parts
            .joinToString("") { part ->
                if (
                    part.optional &&
                        senderFields.findAll(part.text).any {
                            values[it.groupValues[1]].isNullOrBlank()
                        }
                )
                    ""
                else senderFields.replace(part.text) { values[it.groupValues[1]].orEmpty() }
            }
            .trim()
    }

    companion object {
        fun patternError(pattern: String): String? = runCatching {
            validateLength(pattern)
            val remaining =
                optionalSender.replace(pattern) { block ->
                    require(!block.groupValues[1].contains("[[")) { "可选片段不支持嵌套" }
                    block.groupValues[1]
                }
            require(!remaining.contains("[[") && !remaining.contains("]]")) {
                "可选片段的 [[ 和 ]] 需要成对出现"
            }
            val literal = senderFields.replace(remaining, "")
            require(!literal.contains('{') && !literal.contains('}')) {
                "支持的占位符为 {name}、{title}、{uid} 和 {level}"
            }
        }
            .exceptionOrNull()
            ?.message
    }
}
