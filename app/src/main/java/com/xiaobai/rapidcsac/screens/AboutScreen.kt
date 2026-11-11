package com.xiaobai.rapidcsac.screens

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaobai.rapidcsac.BuildConfig
import com.xiaobai.rapidcsac.ui.LocalDarkTheme
import com.xiaobai.rapidcsac.ui.LocalPersonalization
import com.xiaobai.rapidcsac.ui.Navigation
import com.xiaobai.rapidcsac.ui.RapidIconButton
import com.xiaobai.rapidcsac.ui.UiController
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val sourceUrl = "https://github.com/VasilyZa/RapidCsAC"

@Composable
fun AboutScreen(ui: UiController, nav: Navigation, serverOrigin: String) {
    val context = LocalContext.current
    val dark = LocalDarkTheme.current
    val colors = MiuixTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val phase = rememberAboutGradientPhase()
    val haze = rememberHazeState()
    val highContrast = LocalPersonalization.current.highContrast
    val buildType =
        when (BuildConfig.BUILD_TYPE) {
            "release" -> "正式版"
            "preview" -> "预览版"
            "debug" -> "调试版"
            else -> BuildConfig.BUILD_TYPE
        }
    val minimumSystem =
        when (val sdk = context.applicationInfo.minSdkVersion) {
            Build.VERSION_CODES.N -> "Android 7.0 及以上"
            else -> "Android API $sdk 及以上"
        }
    val secondaryColor =
        if (highContrast) colors.onSurface
        else colors.onSurface.copy(alpha = if (dark) .82f else .72f)
    val glass =
        remember(dark, highContrast, colors.surface, colors.surfaceContainer) {
            val tint =
                colors.surfaceContainer.copy(
                    alpha = if (highContrast) .9f else if (dark) .28f else .42f
                )
            HazeBlurStyle {
                blurEnabled(true)
                blurRadius(28.dp)
                noiseFactor(.015f)
                backgroundColor(colors.surface)
                colorEffects(listOf(HazeColorEffect.tint(tint)))
                fallbackColorEffect(HazeColorEffect.tint(tint))
            }
        }

    Box(Modifier.fillMaxSize()) {
        AboutGradientBackground(
            phase,
            dark,
            colors.surface,
            Modifier.matchParentSize().hazeSource(haze),
        )
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                SmallTopAppBar(
                    title = "关于",
                    color = Color.Transparent,
                    navigationIcon = { RapidIconButton("back", "返回", onClick = nav::back) },
                )
            },
        ) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val heroHeight = (maxHeight * .5f).coerceIn(260.dp, 440.dp)
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    item {
                        Column(
                            Modifier.widthIn(max = 640.dp)
                                .fillMaxWidth()
                                .heightIn(min = heroHeight)
                                .padding(horizontal = 12.dp, vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement =
                                Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
                        ) {
                            BasicText(
                                "RapidCsAC",
                                Modifier.fillMaxWidth().aboutTitleGradient(phase, dark).semantics {
                                    heading()
                                },
                                autoSize = TextAutoSize.StepBased(16.sp, 52.sp),
                                maxLines = 1,
                                style =
                                    MiuixTheme.textStyles.title1.copy(
                                        color = Color.White,
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = (-1.5).sp,
                                        textAlign = TextAlign.Center,
                                    ),
                            )
                            Text(
                                BuildConfig.VERSION_NAME,
                                color = secondaryColor,
                                fontSize = 18.sp,
                            )
                        }
                    }
                    item {
                        Card(
                            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                            colors = CardDefaults.defaultColors(color = Color.Transparent),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().hazeBlur(HazeInput.Sources(haze), glass)
                            ) {
                                AboutInfoRow("版本号", BuildConfig.VERSION_NAME, secondaryColor)
                                AboutInfoRow(
                                    "构建号",
                                    BuildConfig.VERSION_CODE.toString(),
                                    secondaryColor,
                                )
                                AboutInfoRow("构建类型", buildType, secondaryColor)
                                AboutInfoRow("应用包名", context.packageName, secondaryColor)
                                AboutInfoRow("系统要求", minimumSystem, secondaryColor)
                                AboutInfoRow("当前服务器", serverOrigin, secondaryColor)
                                ArrowPreference(
                                    title = "开源地址",
                                    summary = sourceUrl,
                                    summaryColor =
                                        BasicComponentDefaults.summaryColor(secondaryColor),
                                    onClick = {
                                        runCatching { uriHandler.openUri(sourceUrl) }
                                            .onFailure { ui.inform("无法打开开源地址") }
                                    },
                                )
                                ArrowPreference(
                                    title = "开源许可证",
                                    summary = "AGPL-3.0-only",
                                    summaryColor =
                                        BasicComponentDefaults.summaryColor(secondaryColor),
                                    onClick = { nav.open("licenses/project") },
                                )
                                ArrowPreference(
                                    title = "第三方开源许可",
                                    summary = "使用的库与许可全文",
                                    summaryColor =
                                        BasicComponentDefaults.summaryColor(secondaryColor),
                                    onClick = { nav.open("licenses") },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String, valueColor: Color) {
    BasicComponent(Modifier.semantics(mergeDescendants = true) {}) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label,
                Modifier.padding(end = 16.dp),
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
            )
            Text(
                value,
                color = valueColor,
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                textAlign = TextAlign.End,
            )
        }
    }
}
