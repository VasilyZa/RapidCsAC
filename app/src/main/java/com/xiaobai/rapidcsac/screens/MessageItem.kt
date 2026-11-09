package com.xiaobai.rapidcsac.screens

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xiaobai.rapidcsac.core.model.Message
import com.xiaobai.rapidcsac.core.network.firstText
import com.xiaobai.rapidcsac.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MessageItem(
    message: Message,
    own: Boolean,
    group: Boolean,
    resolve: (String) -> String,
    open: () -> Unit,
    menu: () -> Unit,
    reply: () -> Unit,
    jump: (Long) -> Unit,
    sender: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    select: () -> Unit = {},
    voiceContent: @Composable (() -> Unit)? = null,
    mediaTag: String = "",
) {
    val scope = rememberCoroutineScope()
    val reduce = LocalReducedMotion.current
    val density = LocalDensity.current
    val maximum = with(density) { 80.dp.toPx() }
    val threshold = with(density) { 56.dp.toPx() }
    var offset by remember(message.id) { mutableFloatStateOf(0f) }
    var settle by remember { mutableStateOf<Job?>(null) }
    val canReply = message.id > 0 && !message.recalled
    val clickMessage = if (selectionMode) select else open
    val holdMessage = if (selectionMode) select else menu
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (selected) MiuixTheme.colorScheme.primary.copy(alpha = .08f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction("消息操作") {
                            menu()
                            true
                        }
                    )
            }
            .draggable(
                state = rememberDraggableState { offset = (offset + it).coerceIn(0f, maximum) },
                orientation = Orientation.Horizontal,
                enabled = canReply && !selectionMode,
                onDragStarted = { settle?.cancel() },
                onDragStopped = { velocity ->
                    if (offset >= threshold) reply()
                    if (reduce) offset = 0f
                    else
                        settle = scope.launch {
                            AnimationState(offset, velocity).animateTo(
                                0f,
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ) {
                                offset = value
                            }
                        }
                },
            )
            .graphicsLayer { translationX = offset },
        horizontalArrangement = if (own) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (selectionMode)
            Checkbox(
                if (selected) ToggleableState.On else ToggleableState.Off,
                select,
                Modifier.padding(end = 8.dp).size(44.dp),
            )
        if (!own && group) {
            IconButton(
                onClick = if (selectionMode) select else sender,
                modifier = Modifier.padding(end = 6.dp),
                minHeight = 44.dp,
                minWidth = 44.dp,
            ) {
                Avatar(message.name, resolve(message.avatar), 32)
            }
        }
        Column(
            Modifier.widthIn(max = 310.dp),
            horizontalAlignment = if (own) Alignment.End else Alignment.Start,
        ) {
            if (group && !own)
                Text(
                    message.name.ifEmpty { "UID ${message.sender}" } +
                        org.json.JSONObject(message.raw).firstText("member_title", "title").let {
                            if (it.isNotEmpty()) " · $it" else ""
                        },
                    Modifier.padding(bottom = 4.dp)
                        .combinedClickable(onClick = clickMessage, onLongClick = holdMessage),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                )
            Card(
                modifier = Modifier.widthIn(max = 310.dp),
                cornerRadius = 16.dp,
                colors =
                    CardDefaults.defaultColors(
                        color =
                            if (own) MiuixTheme.colorScheme.primaryContainer
                            else MiuixTheme.colorScheme.surfaceContainer
                    ),
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                onClick = clickMessage,
                onLongPress = holdMessage,
            ) {
                if (message.reply > 0 && !message.recalled)
                    Button(
                        onClick = { if (selectionMode) select() else jump(message.reply) },
                        modifier = Modifier.padding(bottom = 6.dp),
                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        minHeight = 48.dp,
                        cornerRadius = 10.dp,
                        colors =
                            ButtonDefaults.buttonColors(
                                color = MiuixTheme.colorScheme.secondaryContainer
                            ),
                    ) {
                        Column {
                            Text(
                                message.replyName.ifEmpty { "引用消息" },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.primary,
                            )
                            Text(
                                message.replyText.ifEmpty { "查看原消息" },
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                when {
                    message.recalled ->
                        Text(
                            "消息已撤回",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    message.image.isNotEmpty() ->
                        AsyncImage(
                            resolve(message.image),
                            if (message.type == 5) "表情" else "图片，点按查看",
                            Modifier.sharedMedia(mediaTag)
                                .widthIn(min = 120.dp, max = 260.dp)
                                .heightIn(min = 100.dp, max = 280.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        )
                    message.type == 3 ->
                        if (voiceContent != null && !selectionMode) voiceContent()
                        else Text("语音 ${message.duration} 秒")
                    message.type == 6 ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                rapidIcon("file"),
                                null,
                                Modifier.size(26.dp),
                                tint = MiuixTheme.colorScheme.primary,
                            )
                            Text(
                                message.fileName.ifEmpty { "文件" },
                                Modifier.widthIn(max = 200.dp).padding(vertical = 4.dp),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    else -> RichMessageText(message.text)
                }
            }
            Row(
                Modifier.padding(top = 4.dp)
                    .combinedClickable(onClick = clickMessage, onLongClick = holdMessage),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (message.essence)
                    Icon(
                        rapidIcon("heart"),
                        "精华消息",
                        Modifier.size(12.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                Text(
                    remember(message.time) {
                        SimpleDateFormat("HH:mm", Locale.getDefault())
                            .format(Date(message.time * 1000))
                    },
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (own) MessageReceipt(!group && message.read, delivery = message.delivery)
            }
        }
    }
}
