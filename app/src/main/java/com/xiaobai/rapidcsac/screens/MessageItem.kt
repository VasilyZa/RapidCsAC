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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xiaobai.rapidcsac.core.model.Message
import com.xiaobai.rapidcsac.ui.*
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
    timeLabel: String,
    senderLabel: String,
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
    pat: (() -> Unit)? = null,
) {
    if (message.isNotice) {
        ConversationNotice(message, selectionMode, selected, select, menu)
        return
    }
    val scope = rememberCoroutineScope()
    val personalization = LocalPersonalization.current
    val canPat = pat != null && personalization.enablePat
    val metadataSurface =
        if (LocalChatWallpaper.current != null)
            Modifier.background(
                    MiuixTheme.colorScheme.surface.copy(alpha = .9f),
                    RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 4.dp, vertical = 2.dp)
        else Modifier
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
            .graphicsLayer { translationX = offset }
            .padding(horizontal = 16.dp),
        horizontalArrangement = if (own) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (selectionMode)
            Checkbox(
                if (selected) ToggleableState.On else ToggleableState.Off,
                select,
                Modifier.padding(end = 8.dp).size(44.dp),
            )
        if (!own && group && personalization.showAvatars) {
            Box(
                Modifier.padding(end = 6.dp)
                    .size(44.dp)
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = if (selectionMode) "选择消息" else "查看资料",
                        onClick = if (selectionMode) select else sender,
                        onDoubleClick = if (selectionMode || !canPat) null else pat,
                    )
                    .semantics {
                        contentDescription =
                            "${message.name.ifEmpty { "UID ${message.sender}" }}的头像"
                        if (canPat && !selectionMode)
                            customActions =
                                listOf(
                                    CustomAccessibilityAction("拍一拍") {
                                        pat?.invoke()
                                        true
                                    }
                                )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Avatar(message.name, resolve(message.avatar), 32)
            }
        }
        Column(
            Modifier.widthIn(max = 310.dp),
            horizontalAlignment = if (own) Alignment.End else Alignment.Start,
        ) {
            if (group && !own && senderLabel.isNotBlank())
                Text(
                    senderLabel,
                    Modifier.padding(bottom = 4.dp)
                        .then(metadataSurface)
                        .combinedClickable(onClick = clickMessage, onLongClick = holdMessage),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                )
            ChatBubble(
                own = own,
                modifier = Modifier.widthIn(max = 310.dp),
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
                Modifier.widthIn(max = 310.dp)
                    .padding(top = 4.dp)
                    .then(metadataSurface)
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
                    timeLabel,
                    Modifier.weight(1f, fill = false),
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (own) MessageReceipt(!group && message.read, delivery = message.delivery)
            }
        }
    }
}

@Composable
private fun ConversationNotice(
    message: Message,
    selectionMode: Boolean,
    selected: Boolean,
    select: () -> Unit,
    menu: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (selected) MiuixTheme.colorScheme.primary.copy(alpha = .08f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode)
            Checkbox(
                if (selected) ToggleableState.On else ToggleableState.Off,
                select,
                Modifier.size(44.dp),
            )
        Box(
            Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                message.text.ifBlank { message.preview },
                Modifier.background(
                        if (LocalChatWallpaper.current == null)
                            MiuixTheme.colorScheme.onSurface.copy(alpha = .06f)
                        else MiuixTheme.colorScheme.surface.copy(alpha = .9f),
                        RoundedCornerShape(12.dp),
                    )
                    .combinedClickable(
                        onClick = if (selectionMode) select else menu,
                        onLongClick = if (selectionMode) select else menu,
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (selectionMode) Spacer(Modifier.width(44.dp))
    }
}
