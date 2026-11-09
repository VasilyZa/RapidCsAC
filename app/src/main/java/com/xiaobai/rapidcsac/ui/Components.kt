package com.xiaobai.rapidcsac.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

fun rapidIcon(name: String): ImageVector =
    when (name) {
        "back" -> MiuixIcons.Back
        "chat" -> MiuixIcons.Messages
        "people",
        "person" -> MiuixIcons.Contacts
        "discover" -> MiuixIcons.Album
        "search" -> MiuixIcons.Search
        "add" -> MiuixIcons.Add
        "more" -> MiuixIcons.More
        "send" -> MiuixIcons.Send
        "mic" -> MiuixIcons.Mic
        "image" -> MiuixIcons.Image
        "file" -> MiuixIcons.File
        "scan" -> MiuixIcons.Scan
        "settings" -> MiuixIcons.Settings
        "heart" -> MiuixIcons.Favorites
        "lock" -> MiuixIcons.Lock
        "theme" -> MiuixIcons.Theme
        "delete" -> MiuixIcons.Delete
        "close" -> MiuixIcons.Close
        "reply" -> MiuixIcons.Reply
        "copy" -> MiuixIcons.Copy
        "share" -> MiuixIcons.Share
        "pin" -> MiuixIcons.Pin
        "download" -> MiuixIcons.FileDownloads
        "play" -> MiuixIcons.Play
        "pause" -> MiuixIcons.Pause
        "check" -> MiuixIcons.Ok
        "refresh" -> MiuixIcons.Refresh
        else -> MiuixIcons.MoreCircle
    }

@Composable
fun RapidIconButton(
    name: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, minWidth = 48.dp, minHeight = 48.dp) {
        Icon(
            rapidIcon(name),
            description,
            Modifier.size(24.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun Avatar(name: String, url: String = "", size: Int = 50) {
    Box(
        Modifier.size(size.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            remember(name) {
                name
                    .substring(
                        0,
                        name.offsetByCodePoints(0, minOf(1, name.codePointCount(0, name.length))),
                    )
                    .uppercase()
            },
            fontSize = (size * .32).sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.primary,
        )
        if (url.isNotBlank())
            AsyncImage(
                url,
                null,
                Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
    }
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) {
        Text(text, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun RapidField(
    value: String,
    change: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    multiline: Boolean = false,
    numeric: Boolean = false,
    enabled: Boolean = true,
) {
    var visible by remember { mutableStateOf(false) }
    TextField(
        value,
        change,
        modifier.fillMaxWidth(),
        label = label,
        enabled = enabled,
        singleLine = !multiline,
        maxLines = if (multiline) 6 else 1,
        keyboardOptions =
            KeyboardOptions(
                keyboardType =
                    when {
                        password -> KeyboardType.Password
                        numeric -> KeyboardType.Number
                        else -> KeyboardType.Text
                    }
            ),
        visualTransformation =
            if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon =
            if (password)
                ({
                    IconButton(
                        onClick = { visible = !visible },
                        enabled = enabled,
                        minWidth = 48.dp,
                        minHeight = 48.dp,
                    ) {
                        Icon(
                            if (visible) MiuixIcons.Hide else MiuixIcons.Show,
                            if (visible) "隐藏密码" else "显示密码",
                            Modifier.size(22.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                })
            else null,
    )
}

@Composable
fun EmptyContent(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    icon: String = "chat",
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(76.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                rapidIcon(icon),
                null,
                Modifier.size(32.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(
            summary,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
