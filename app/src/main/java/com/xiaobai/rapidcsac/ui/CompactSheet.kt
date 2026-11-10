package com.xiaobai.rapidcsac.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared bottom-sheet geometry and typography, with the library owning gestures and dismissal. */
@Composable
fun CompactSheet(
    show: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
    allowDismiss: Boolean = true,
    dismissLabel: String = "关闭",
    content: @Composable () -> Unit,
) {
    val type = MiuixTheme.textStyles
    val colors = MiuixTheme.colorScheme
    val body = type.main.copy(fontSize = 19.sp, lineHeight = 24.sp)
    val sheetType =
        type.copy(
            main = body,
            paragraph = type.paragraph.copy(fontSize = 19.sp, lineHeight = 27.sp),
            headline1 = body,
            body1 = type.body1.copy(fontSize = 18.sp, lineHeight = 24.sp),
            body2 = type.body2.copy(fontSize = 16.sp, lineHeight = 22.sp),
            button = type.button.copy(fontSize = 18.sp, lineHeight = 24.sp),
        )
    OverlayBottomSheet(
        modifier = Modifier.navigationBarsPadding(),
        show = show,
        insideMargin = DpSize(12.dp, 8.dp),
        startAction = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    Modifier.weight(1f).padding(start = 8.dp).semantics { heading() },
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                RapidIconButton("close", dismissLabel, allowDismiss, onDismissRequest)
            }
        },
        allowDismiss = allowDismiss,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        content = { MiuixTheme(colors = colors, textStyles = sheetType, content = content) },
    )
}

@Composable
fun CompactSheetRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    role: Role = Role.Button,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = role, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(
            title,
            Modifier.weight(1f),
            fontWeight = FontWeight.Medium,
            color =
                if (destructive) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
        )
        trailing?.invoke()
    }
}
