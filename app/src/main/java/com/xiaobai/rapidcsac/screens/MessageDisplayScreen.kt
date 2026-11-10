package com.xiaobai.rapidcsac.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaobai.rapidcsac.AppGraph
import com.xiaobai.rapidcsac.core.format.*
import com.xiaobai.rapidcsac.ui.*
import java.util.Calendar
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class DisplayField(val label: String) {
    TODAY("当天消息"),
    RECENT("近日消息"),
    CURRENT_YEAR("今年内的消息"),
    OTHER_YEAR("其他年份的消息"),
    SENDER("群聊署名");

    fun pattern(style: MessageDisplayStyle): String =
        when (this) {
            TODAY -> style.time.todayPattern
            RECENT -> style.time.recentPattern
            CURRENT_YEAR -> style.time.currentYearPattern
            OTHER_YEAR -> style.time.otherYearPattern
            SENDER -> style.senderPattern
        }

    fun update(style: MessageDisplayStyle, value: String): MessageDisplayStyle =
        when (this) {
            TODAY -> style.copy(time = style.time.copy(todayPattern = value))
            RECENT -> style.copy(time = style.time.copy(recentPattern = value))
            CURRENT_YEAR -> style.copy(time = style.time.copy(currentYearPattern = value))
            OTHER_YEAR -> style.copy(time = style.time.copy(otherYearPattern = value))
            SENDER -> style.copy(senderPattern = value)
        }

    fun preview(style: MessageDisplayStyle, pattern: String, clock: MessageDateContext): String {
        if (this == SENDER) {
            val formatter = GroupSenderFormatter(pattern)
            return formatter.format("小明", "青铜", 12345, "5") +
                "\n无称号时  " +
                formatter.format("小明", "", 12345, "")
        }
        val sample =
            Calendar.getInstance(clock.zone, clock.locale).apply {
                timeInMillis = clock.nowMillis
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                when (this@DisplayField) {
                    RECENT -> add(Calendar.DAY_OF_YEAR, -1)
                    CURRENT_YEAR -> {
                        set(Calendar.DAY_OF_MONTH, 1)
                        set(Calendar.MONTH, Calendar.JANUARY)
                    }
                    OTHER_YEAR -> add(Calendar.YEAR, -1)
                    else -> Unit
                }
            }
        val time =
            style.time.copy(
                todayPattern = pattern,
                recentPattern = pattern,
                currentYearPattern = pattern,
                otherYearPattern = pattern,
            )
        return MessageTimeFormatter(time, clock.nowMillis, clock.zone, clock.locale)
            .format(sample.timeInMillis / 1000)
    }
}

@Composable
fun MessageDisplayScreen(graph: AppGraph, ui: UiController, nav: Navigation) {
    val style by graph.messageDisplay.style.collectAsStateWithLifecycle()
    val clock = rememberMessageDateContext()
    var field by rememberSaveable { mutableStateOf<DisplayField?>(null) }
    var editorVisible by rememberSaveable { mutableStateOf(false) }
    fun edit(value: DisplayField) {
        field = value
        editorVisible = true
    }
    val timePresets = MessageDisplayPresets.times
    val senderPresets = MessageDisplayPresets.senders
    val timeIndex =
        timePresets.indexOfFirst { it.second == style.time }.takeIf { it >= 0 } ?: timePresets.size
    val senderIndex =
        senderPresets.indexOfFirst { it.second == style.senderPattern }.takeIf { it >= 0 }
            ?: senderPresets.size
    val previews =
        remember(style, clock) {
            DisplayField.entries.associateWith { it.preview(style, it.pattern(style), clock) }
        }
    Page("消息显示样式", nav::back) { padding ->
        PageList(padding) {
            item {
                SectionLabel("消息日期与时间")
                Card {
                    OverlaySpinnerPreference(
                        items =
                            (timePresets.map { it.first } + "自定义").map { DropdownItem(text = it) },
                        selectedIndex = timeIndex,
                        title = "时间样式",
                        onSelectedIndexChange = { index ->
                            if (index < timePresets.size)
                                graph.messageDisplay.update(
                                    style.copy(time = timePresets[index].second)
                                )
                            else edit(DisplayField.TODAY)
                        },
                    )
                    DisplayField.entries
                        .filter { it != DisplayField.SENDER }
                        .forEach { target ->
                            MenuRow(target.label, previews[target]) { edit(target) }
                        }
                    BasicComponent(summary = "使用设备时区 ${clock.zone.id}。点按任一日期样式可编辑格式并预览。")
                }
            }
            item {
                SectionLabel("相对日期")
                Card {
                    MenuRow(
                        "相对日期范围",
                        if (style.time.recentDays == 0) "往日消息使用日期"
                        else "过去 ${style.time.recentDays} 天使用近日格式",
                    ) {
                        ui.edit(
                            "相对日期范围",
                            listOf(
                                InputField(
                                    "days",
                                    "天数，0–365",
                                    style.time.recentDays.toString(),
                                    numeric = true,
                                )
                            ),
                        ) { params ->
                            val days = params.optString("days").toIntOrNull()
                            require(days != null && days in 0..365) {
                                "请输入 0–365 之间的整数，0 表示往日消息使用日期"
                            }
                            graph.messageDisplay.update(
                                style.copy(time = style.time.copy(recentDays = days))
                            )
                        }
                    }
                    MenuRow(
                        "相对日期文字",
                        listOf(
                                style.time.todayLabel,
                                style.time.yesterdayLabel,
                                style.time.dayBeforeLabel,
                                style.time.daysAgoLabel,
                            )
                            .joinToString(" / "),
                    ) {
                        ui.edit(
                            "相对日期文字",
                            listOf(
                                InputField("today", "当天", style.time.todayLabel),
                                InputField("yesterday", "前一天", style.time.yesterdayLabel),
                                InputField("day_before", "前两天", style.time.dayBeforeLabel),
                                InputField(
                                    "days_ago",
                                    "更早的天数，用 {days} 代表数字",
                                    style.time.daysAgoLabel,
                                ),
                            ),
                        ) { params ->
                            graph.messageDisplay.update(
                                style.copy(
                                    time =
                                        style.time.copy(
                                            todayLabel = params.optString("today"),
                                            yesterdayLabel = params.optString("yesterday"),
                                            dayBeforeLabel = params.optString("day_before"),
                                            daysAgoLabel = params.optString("days_ago"),
                                        )
                                )
                            )
                        }
                    }
                    BasicComponent(
                        summary = "近日格式优先于年份分档。格式中使用 {relative} 显示上述文字，使用 {days} 显示相隔天数。"
                    )
                }
            }
            item {
                SectionLabel("群聊对方的昵称与称号")
                Card {
                    OverlaySpinnerPreference(
                        items =
                            (senderPresets.map { it.first } + "自定义").map {
                                DropdownItem(text = it)
                            },
                        selectedIndex = senderIndex,
                        title = "署名样式",
                        onSelectedIndexChange = { index ->
                            if (index < senderPresets.size)
                                graph.messageDisplay.update(
                                    style.copy(senderPattern = senderPresets[index].second)
                                )
                            else edit(DisplayField.SENDER)
                        },
                    )
                    MenuRow("自定义署名格式", style.senderPattern) { edit(DisplayField.SENDER) }
                    BasicComponent(title = "预览", summary = previews[DisplayField.SENDER])
                }
            }
        }
    }
    field?.let { target ->
        key(target) {
            DisplayFormatEditor(
                target,
                style,
                clock,
                editorVisible,
                onDismiss = { editorVisible = false },
                onDismissFinished = { if (!editorVisible) field = null },
                save = { graph.messageDisplay.update(target.update(style, it)) },
            )
        }
    }
}

@Composable
private fun DisplayFormatEditor(
    target: DisplayField,
    style: MessageDisplayStyle,
    clock: MessageDateContext,
    visible: Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    save: (String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf(target.pattern(style)) }
    val error =
        remember(draft, target) {
            if (target == DisplayField.SENDER) GroupSenderFormatter.patternError(draft)
            else MessageTimeFormatter.patternError(draft)
        }
    val preview =
        remember(draft, error, clock, style) {
            if (error == null) target.preview(style, draft, clock) else ""
        }
    OverlayDialog(
        show = visible,
        title = "${target.label}格式",
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
    ) {
        Column(Modifier.heightIn(max = LocalWindowInfo.current.containerDpSize.height * .7f)) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextField(
                    draft,
                    { draft = it },
                    label = "格式与附加文字",
                    minLines = 2,
                    maxLines = 4,
                )
                Text("预览", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (error != null) Text(error, color = MiuixTheme.colorScheme.error)
                else Text(preview)
                Text(
                    if (target == DisplayField.SENDER)
                        "{name} 昵称，{title} 称号，{uid} 用户编号，{level} 等级。\n[[…]] 内任一字段为空时，整个片段会省略。\n例如 {name}[[ · {title}]]。附加文字直接输入。"
                    else
                        "yyyy 年，MM 月，dd 日，HH 24 小时，hh 12 小时，mm 分钟，ss 秒，EEE 星期，a 上午或下午。\n日期中的小写 mm 也可表示月份，例如 yyyy.mm.dd。\n{relative} 相对日期，{days} 相隔天数。附加文字直接输入，单引号内原样显示。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onDismiss, Modifier.weight(1f)) { Text("取消") }
                Button(
                    {
                        save(draft)
                        onDismiss()
                    },
                    Modifier.weight(1f),
                    enabled = error == null,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("保存")
                }
            }
        }
    }
}
