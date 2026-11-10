package com.xiaobai.rapidcsac.screens

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.network.*
import com.xiaobai.rapidcsac.platform.copyText
import com.xiaobai.rapidcsac.platform.networkEnvironment
import com.xiaobai.rapidcsac.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DiagnosticsScreen(repo: ChatRepository, ui: UiController, nav: Navigation) {
    val context = LocalContext.current
    val connection by repo.connection.collectAsStateWithLifecycle()
    val stats = rememberRemote(repo) { repo.statistics() }
    var revision by remember { mutableIntStateOf(0) }
    var report by remember(repo) { mutableStateOf(DiagnosticReport()) }
    var environment by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var running by remember { mutableStateOf(true) }
    var started by remember { mutableLongStateOf(0) }
    var task by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(repo, revision) {
        val currentTask = currentCoroutineContext().job
        task = currentTask
        running = true
        started = SystemClock.elapsedRealtime()
        report = DiagnosticReport()
        environment = networkEnvironment(context)
        try {
            repo.diagnoseNetwork().collect { report = it }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            report = report.stopped("检测未能完成，请重试")
            ui.inform("检测未能完成，请重新检测")
        } finally {
            if (task === currentTask) running = false
        }
    }
    Page("网络诊断", nav::back) { padding ->
        PageList(padding) {
            item {
                DiagnosticOverview(report, running, repo.account.origin)
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton(
                        if (running) "停止检测" else "重新检测",
                        Modifier.weight(1f),
                    ) {
                        if (running) {
                            task?.cancel()
                            report =
                                report
                                    .stopped()
                                    .copy(totalMs = SystemClock.elapsedRealtime() - started)
                            running = false
                        } else revision++
                    }
                    Button(
                        onClick = {
                            copyText(context, report.asText(repo.account.origin, environment))
                            ui.inform("诊断报告已复制")
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        enabled = !running,
                    ) {
                        Text("复制报告")
                    }
                }
            }
            DiagnosticKind.entries
                .groupBy { it.group }
                .forEach { (group, kinds) ->
                    item(group) {
                        SectionLabel(group)
                        Card {
                            kinds.forEachIndexed { index, kind ->
                                DiagnosticRow(report.checks.first { it.kind == kind })
                                if (index != kinds.lastIndex)
                                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                            }
                        }
                    }
                }
            item {
                SectionLabel("本轮网络与客户端")
                Card {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        environment.forEach { (name, value) -> DiagnosticValue(name, value) }
                        HorizontalDivider()
                        DiagnosticValue("聊天实时连接", connectionLabel(connection))
                        DiagnosticValue(
                            "缓存消息",
                            stats.value?.first?.let { "$it 条" }
                                ?: if (stats.loading) "读取中…" else "读取失败",
                        )
                        DiagnosticValue(
                            "待确认发送",
                            stats.value?.second?.let { "$it 条" }
                                ?: if (stats.loading) "读取中…" else "读取失败",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticOverview(report: DiagnosticReport, running: Boolean, origin: String) {
    val colors = MiuixTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]
    val reduce = LocalReducedMotion.current
    val accent by
        animateColorAsState(
            if (report.failures > 0) colors.error else colors.primary,
            tween(if (reduce) 0 else RapidMotion.feedbackMillis),
        )
    val progress by
        animateFloatAsState(
            report.completed.toFloat() / report.checks.size,
            tween(if (reduce) 0 else RapidMotion.feedbackMillis, easing = RapidMotion.easeOut),
        )
    val title =
        when {
            running -> "正在检查连接"
            report.cancelled -> "检测已停止"
            report.passed -> "连接检查通过"
            else -> "发现 ${report.failures} 项异常"
        }
    val detail =
        if (running)
            report.checks.firstOrNull { it.status == DiagnosticStatus.RUNNING }?.kind?.title
                ?: "准备检测…"
        else
            "${report.checks.count { it.status == DiagnosticStatus.PASSED }} 项通过 · ${report.checks.count { it.status == DiagnosticStatus.SKIPPED }} 项跳过"
    Card {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("连接状态", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = accent)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    Modifier.semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(detail, fontSize = 14.sp, color = colors.onSurfaceVariantSummary)
            }
            SelectionContainer {
                Text(origin, fontSize = 14.sp, color = colors.onSurfaceVariantSummary)
            }
            LinearProgressIndicator(progress = progress)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                DiagnosticMetric(
                    "已检测",
                    "${report.completed} / ${report.checks.size}",
                    Modifier.weight(1f),
                )
                DiagnosticMetric(
                    "本轮耗时",
                    if (running) "检测中" else "${report.totalMs} ms",
                    Modifier.weight(1f),
                )
            }
            if (!running)
                Text(
                    SimpleDateFormat("MM-dd HH:mm:ss", locale).format(Date(report.startedAt)),
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariantSummary,
                )
        }
    }
}

@Composable
private fun DiagnosticMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun DiagnosticRow(check: DiagnosticCheck) {
    val colors = MiuixTheme.colorScheme
    val accent =
        when (check.status) {
            DiagnosticStatus.PASSED,
            DiagnosticStatus.RUNNING -> colors.primary
            DiagnosticStatus.FAILED -> colors.error
            else -> colors.onSurfaceVariantSummary
        }
    Column(
        Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(28.dp).background(accent.copy(alpha = .1f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (check.status == DiagnosticStatus.RUNNING && !LocalReducedMotion.current)
                    CircularProgressIndicator(size = 18.dp)
                else if (
                    check.status == DiagnosticStatus.PASSED ||
                        check.status == DiagnosticStatus.FAILED
                )
                    Icon(
                        rapidIcon(
                            if (check.status == DiagnosticStatus.PASSED) "check" else "close"
                        ),
                        null,
                        Modifier.size(16.dp),
                        tint = accent,
                    )
                else Box(Modifier.size(5.dp).background(accent, CircleShape))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        check.kind.title,
                        Modifier.weight(1f),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(check.status.label, color = accent, fontSize = 12.sp)
                        check.elapsedMs?.let {
                            Text("$it ms", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                        }
                    }
                }
                if (check.detail.isNotEmpty())
                    SelectionContainer {
                        Text(
                            check.detail,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                if (check.suggestion.isNotEmpty())
                    Text(
                        check.suggestion,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = colors.error,
                    )
            }
        }
    }
}

@Composable
private fun DiagnosticValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        SelectionContainer { Text(value, fontSize = 15.sp, lineHeight = 21.sp) }
    }
}

private fun connectionLabel(state: ConnectionState): String =
    when (state) {
        ConnectionState.CONNECTED -> "WS v2 · 已连接"
        ConnectionState.CONNECTING -> "WS v2 · 连接中"
        ConnectionState.DISCONNECTED -> "WS v2 · 已断开"
        ConnectionState.EXPIRED -> "会话已过期"
    }
