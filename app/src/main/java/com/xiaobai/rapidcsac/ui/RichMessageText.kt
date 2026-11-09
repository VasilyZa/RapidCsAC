package com.xiaobai.rapidcsac.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun RichMessageText(content: String) {
    val colors = MiuixTheme.colorScheme
    val body = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, color = colors.onSurface)
    val heading = body.copy(fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
    val uriHandler = LocalUriHandler.current
    CompositionLocalProvider(
        LocalUriHandler provides
            object : UriHandler {
                override fun openUri(uri: String) {
                    if (
                        Uri.parse(uri).scheme?.lowercase() in
                            setOf("https", "http", "mailto", "tel")
                    )
                        runCatching { uriHandler.openUri(uri) }
                }
            }
    ) {
        Markdown(
            content = content,
            modifier = Modifier,
            colors =
                markdownColor(
                    text = colors.onSurface,
                    codeBackground = colors.secondaryContainer,
                    inlineCodeBackground = colors.secondaryContainer,
                    dividerColor = colors.outline,
                    tableBackground = colors.surfaceContainer,
                    darkTheme = LocalDarkTheme.current,
                ),
            typography =
                markdownTypography(
                    text = body,
                    h1 = heading,
                    h2 = heading,
                    h3 = heading,
                    h4 = heading,
                    h5 = heading,
                    h6 = heading,
                    code = body.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    inlineCode = body.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
                    quote = body,
                    paragraph = body,
                    ordered = body,
                    bullet = body,
                    list = body,
                    table = body,
                    alertTitle = heading,
                    textLink =
                        TextLinkStyles(
                            SpanStyle(
                                color = colors.primary,
                                textDecoration = TextDecoration.Underline,
                            )
                        ),
                ),
            loading = { Text(content, fontSize = 17.sp) },
        )
    }
}
