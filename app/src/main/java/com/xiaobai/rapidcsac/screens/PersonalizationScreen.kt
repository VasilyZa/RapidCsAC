package com.xiaobai.rapidcsac.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaobai.rapidcsac.AppGraph
import com.xiaobai.rapidcsac.platform.*
import com.xiaobai.rapidcsac.ui.*
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val personalColors =
    listOf(
        "晴空蓝" to 0xff4169d8.toInt(),
        "紫罗兰" to 0xff7c4dcb.toInt(),
        "玫瑰红" to 0xffb5476a.toInt(),
        "琥珀色" to 0xff9b691e.toInt(),
        "松石绿" to 0xff267a6f.toInt(),
        "石墨灰" to 0xff596579.toInt(),
    )

@Composable
fun PersonalizationScreen(graph: AppGraph, ui: UiController, nav: Navigation) {
    val style by graph.personalization.style.collectAsStateWithLifecycle()
    val theme by graph.theme.collectAsStateWithLifecycle()
    val reduced by graph.reducedMotion.collectAsStateWithLifecycle()
    val wallpaper by graph.wallpapers.image.collectAsStateWithLifecycle()
    val colors = MiuixTheme.colorScheme
    var importing by remember { mutableStateOf(false) }
    var enterSend by remember { mutableStateOf(graph.preferences.getBoolean("enter_send", false)) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                ui.launch {
                    importing = true
                    try {
                        graph.wallpapers.replace(uri)
                    } finally {
                        importing = false
                    }
                }
        }
    fun customColor(title: String, value: Int?, selected: (Int) -> Unit) {
        ui.edit(
            title,
            listOf(
                InputField("color", "六位 RGB 颜色，例如 #4169D8", personalColorHex(value ?: style.accent))
            ),
        ) {
            selected(parsePersonalColor(it.getString("color")))
        }
    }
    fun colorChoices(title: String, current: Int?, default: Int, selected: (Int?) -> Unit) {
        ui.actions(
            title,
            listOf(ActionItem("跟随主题", swatchColor = default) { selected(null) }) +
                personalColors.map { (name, color) ->
                    ActionItem(name + if (current == color) " · 当前" else "", swatchColor = color) {
                        selected(color)
                    }
                } +
                ActionItem("自定义颜色", swatchColor = current ?: style.accent) {
                    customColor(title, current) { selected(it) }
                },
        )
    }
    Page("个性化", nav::back) { padding ->
        PageList(padding) {
            item {
                SectionLabel("外观与文字")
                Card {
                    OverlaySpinnerPreference(
                        items = listOf("跟随系统", "浅色", "深色").map { DropdownItem(text = it) },
                        selectedIndex =
                            listOf("system", "light", "dark").indexOf(theme).coerceAtLeast(0),
                        title = "外观",
                        onSelectedIndexChange = {
                            graph.preferences.edit {
                                putString("theme", listOf("system", "light", "dark")[it])
                            }
                            graph.applyTheme()
                        },
                    )
                    BasicComponent(title = "主题色", summary = personalColorHex(style.accent))
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        maxItemsInEachRow = 3,
                    ) {
                        personalColors.forEach { (name, value) ->
                            Column(
                                Modifier.width(80.dp)
                                    .heightIn(min = 72.dp)
                                    .selectable(
                                        style.accent == value,
                                        role = Role.RadioButton,
                                        onClick = {
                                            graph.personalization.update { it.copy(accent = value) }
                                        },
                                    )
                                    .padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val color = androidx.compose.ui.graphics.Color(value)
                                Box(
                                    Modifier.size(32.dp).background(color, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (style.accent == value)
                                        Icon(
                                            rapidIcon("check"),
                                            null,
                                            Modifier.size(18.dp),
                                            tint = readableForeground(color),
                                        )
                                }
                                Text(name, fontSize = 12.sp)
                            }
                        }
                    }
                    MenuRow("自定义主题色", personalColorHex(style.accent)) {
                        customColor("自定义主题色", style.accent) { color ->
                            graph.personalization.update { it.copy(accent = color) }
                        }
                    }
                    OverlaySpinnerPreference(
                        items = InterfaceFont.entries.map { DropdownItem(text = it.label) },
                        selectedIndex = style.font.ordinal,
                        title = "界面字体",
                        onSelectedIndexChange = { index ->
                            graph.personalization.update {
                                it.copy(font = InterfaceFont.entries[index])
                            }
                        },
                    )
                    PersonalSlider(
                        "界面字号",
                        style.fontScale,
                        .85f..1.3f,
                        8,
                        "在系统字体大小的基础上调整，松手后应用",
                    ) { value ->
                        graph.personalization.update { it.copy(fontScale = value) }
                    }
                    SwitchPreference(
                        checked = style.highContrast,
                        title = "高对比度",
                        summary = "增强文字与控件对比，使用不透明气泡",
                        onCheckedChange = { value ->
                            graph.personalization.update { it.copy(highContrast = value) }
                        },
                    )
                    SwitchPreference(
                        checked = style.compact,
                        title = "紧凑布局",
                        summary = "减小会话行、消息间距和气泡内边距",
                        onCheckedChange = { value ->
                            graph.personalization.update { it.copy(compact = value) }
                        },
                    )
                }
            }
            item {
                SectionLabel("聊天样式")
                Card {
                    BasicComponent(title = "实时预览")
                    ChatPreview()
                    OverlaySpinnerPreference(
                        items = BubbleCorners.entries.map { DropdownItem(text = it.label) },
                        selectedIndex = style.corners.ordinal,
                        title = "气泡圆角",
                        onSelectedIndexChange = { index ->
                            graph.personalization.update {
                                it.copy(corners = BubbleCorners.entries[index])
                            }
                        },
                    )
                    MenuRow("我的气泡颜色", colorLabel(style.ownBubble)) {
                        colorChoices("我的气泡颜色", style.ownBubble, colors.primaryContainer.toArgb()) {
                            color ->
                            graph.personalization.update { it.copy(ownBubble = color) }
                        }
                    }
                    MenuRow("对方气泡颜色", colorLabel(style.otherBubble)) {
                        colorChoices(
                            "对方气泡颜色",
                            style.otherBubble,
                            colors.surfaceContainer.toArgb(),
                        ) { color ->
                            graph.personalization.update { it.copy(otherBubble = color) }
                        }
                    }
                    PersonalSlider(
                        "气泡不透明度",
                        if (style.highContrast) 1f else style.bubbleOpacity,
                        .7f..1f,
                        5,
                        if (style.highContrast) "高对比度模式下使用 100% 不透明度" else "松手后应用",
                        !style.highContrast,
                    ) { value ->
                        graph.personalization.update { it.copy(bubbleOpacity = value) }
                    }
                    MenuRow("聊天背景色调", colorLabel(style.backgroundTint) + " · 随外观自动适配") {
                        colorChoices("聊天背景色调", style.backgroundTint, colors.surface.toArgb()) {
                            color ->
                            graph.personalization.update { it.copy(backgroundTint = color) }
                        }
                    }
                    BasicComponent(
                        title = "聊天壁纸",
                        summary =
                            if (importing) "正在处理图片…"
                            else if (wallpaper == null) "使用背景色调" else "已使用本机静态图片",
                        enabled = !importing,
                        onClick = { picker.launch(arrayOf("image/*")) },
                    )
                    if (wallpaper != null) {
                        MenuRow("移除聊天壁纸") { ui.launch { graph.wallpapers.clear() } }
                        PersonalSlider(
                            "壁纸遮罩",
                            style.wallpaperDim,
                            0.3f..0.8f,
                            9,
                            "遮罩越高，文字周围的背景越柔和",
                        ) { value ->
                            graph.personalization.update { it.copy(wallpaperDim = value) }
                        }
                    }
                    MenuRow("消息显示样式", "日期、时间与群聊署名") { nav.open("message-display") }
                }
            }
            item {
                SectionLabel("会话列表")
                Card {
                    OverlaySpinnerPreference(
                        items = ConversationOrder.entries.map { DropdownItem(text = it.label) },
                        selectedIndex = style.conversationOrder.ordinal,
                        title = "会话排序",
                        summary = "置顶会话优先显示",
                        onSelectedIndexChange = { index ->
                            graph.personalization.update {
                                it.copy(conversationOrder = ConversationOrder.entries[index])
                            }
                        },
                    )
                    OverlaySpinnerPreference(
                        items = ConversationSubtitle.entries.map { DropdownItem(text = it.label) },
                        selectedIndex = style.conversationSubtitle.ordinal,
                        title = "会话副标题",
                        onSelectedIndexChange = { index ->
                            graph.personalization.update {
                                it.copy(conversationSubtitle = ConversationSubtitle.entries[index])
                            }
                        },
                    )
                }
            }
            item {
                SectionLabel("交互偏好")
                Card {
                    SwitchPreference(
                        checked = style.showAvatars,
                        title = "显示聊天头像",
                        summary = "在群聊消息旁显示发送者头像",
                        onCheckedChange = { value ->
                            graph.personalization.update { it.copy(showAvatars = value) }
                        },
                    )
                    SwitchPreference(
                        checked = style.enablePat,
                        title = "头像双击拍一拍",
                        summary = "显示头像时，可双击群成员头像触发",
                        enabled = style.showAvatars,
                        onCheckedChange = { value ->
                            graph.personalization.update { it.copy(enablePat = value) }
                        },
                    )
                    SwitchPreference(
                        checked = reduced,
                        onCheckedChange = graph::setReducedMotion,
                        title = "减少动态效果",
                        summary = "简化页面与弹层动画",
                    )
                    SwitchPreference(
                        checked = enterSend,
                        title = "回车键发送",
                        summary = "关闭时使用回车换行",
                        onCheckedChange = {
                            enterSend = it
                            graph.preferences.edit { putBoolean("enter_send", it) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonalSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    summary: String,
    enabled: Boolean = true,
    commit: (Float) -> Unit,
) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    var touching by remember { mutableStateOf(false) }
    val disabledSemantics =
        if (enabled) Modifier
        else
            Modifier.clearAndSetSemantics {
                disabled()
                contentDescription = "$title，$summary"
                stateDescription = "${(value * 100).roundToInt()}%"
            }
    SliderPreference(
        modifier =
            disabledSemantics.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touching = true
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        touching = false
                    }
                }
            },
        value = draft,
        onValueChange = {
            if (enabled) {
                draft = it
                if (!touching) commit(it.coerceIn(range))
            }
        },
        title = title,
        summary = summary,
        valueText = "${(draft * 100).roundToInt()}%",
        valueRange = range,
        steps = steps,
        enabled = enabled,
        onValueChangeFinished = { if (enabled) commit(draft.coerceIn(range)) },
    )
}

private fun colorLabel(value: Int?): String = value?.let(::personalColorHex) ?: "跟随主题"

@Composable
private fun ChatPreview() {
    ChatBackdrop(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (LocalPersonalization.current.showAvatars) Avatar("小明", "", 32)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "小明 · 青铜",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    ChatBubble(false) { Text("消息会以这样的样式显示。", fontSize = 17.sp, lineHeight = 25.sp) }
                }
            }
            ChatBubble(true, Modifier.align(Alignment.End).widthIn(max = 240.dp)) {
                Text("调整后会立即生效。", fontSize = 17.sp, lineHeight = 25.sp)
            }
        }
    }
}
