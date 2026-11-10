package com.xiaobai.rapidcsac.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.network.*
import com.xiaobai.rapidcsac.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun NoticesScreen(repo: ChatRepository, ui: UiController, nav: Navigation) {
    val notices =
        rememberRemote(repo) {
            withTimeout(15_000) {
                repo.awaitReady()
                repo.execute("user/get_notice_list").objects("notices")
            }
        }
    val counts =
        rememberRemote(repo) {
            withTimeout(15_000) {
                repo.awaitReady()
                repo.execute("user/get_notifications")
            }
        }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var showDetail by remember { mutableStateOf(false) }
    var markingAll by remember { mutableStateOf(false) }
    fun reload() {
        notices.reload()
        counts.reload()
    }
    LaunchedEffect(repo) {
        repo.updates.collect { types ->
            if (types.any { it.startsWith("notification.") || it.startsWith("friend.") }) {
                delay(150)
                reload()
            }
        }
    }
    LaunchedEffect(notices.value) {
        val current = detail
        if (current != null) {
            notices.value
                ?.firstOrNull { it.optLong("id") == current.optLong("id") }
                ?.let {
                    detail = it
                }
        }
    }
    val unread = notices.value.orEmpty().count { !it.flag("is_read") }
    val actions = mutableListOf(ActionItem("刷新", "refresh") { reload() })
    if (unread > 0 && !markingAll)
        actions.add(
            0,
            ActionItem("系统通知全部标为已读", "check") {
                ui.launch {
                    if (!markingAll) {
                        markingAll = true
                        try {
                            repo.execute("user/mark_notice_read", json("read_all" to 1))
                            reload()
                            ui.inform("系统通知已全部标为已读")
                        } finally {
                            markingAll = false
                        }
                    }
                }
            },
        )
    Page(
        "通知中心",
        nav::back,
        actions = { AnchoredMenu("通知选项", actions) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            item {
                MenuRow("提及与回复", icon = "reply") { nav.records("mentions") }
                MenuRow("入群申请", icon = "people") { nav.records("applications") }
                MenuRow(
                    "好友申请",
                    icon = "person",
                    badge = counts.value?.optInt("friend_request_unread") ?: 0,
                ) {
                    nav.records("requests")
                }
                MenuRow(
                    "好友变动",
                    icon = "person",
                    badge = counts.value?.optInt("deleted_friend_notices") ?: 0,
                ) {
                    nav.records("friendchanges")
                }
                if (counts.error.isNotEmpty())
                    Button(onClick = { counts.reload() }) { Text("未读数量加载失败，点此重试") }
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                SectionLabel(if (unread > 0) "系统通知 · $unread 条未读" else "系统通知")
                if (notices.error.isNotEmpty()) {
                    Text(notices.error, color = MiuixTheme.colorScheme.error)
                    Button(onClick = { notices.reload() }) { Text("重新加载") }
                } else if (notices.loading && notices.value == null)
                    Text(
                        "正在加载…",
                        Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                else if (notices.value?.isEmpty() == true)
                    EmptyContent("暂无系统通知", "好友申请和群组申请可从上方入口处理", icon = "chat")
            }
            items(notices.value.orEmpty(), key = { it.optLong("id") }) { notice ->
                NoticeRow(notice) {
                    detail = notice
                    showDetail = true
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
    detail?.let { notice ->
        key(notice.optLong("id")) {
            val scope = rememberCoroutineScope()
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            OverlayDialog(
                show = showDetail,
                title = notice.optString("title", "通知"),
                onDismissRequest = { if (!busy) showDetail = false },
                onDismissFinished = { if (!showDetail) detail = null },
            ) {
                Column(
                    Modifier.heightIn(max = LocalWindowInfo.current.containerDpSize.height * .5f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NoticeStatus(notice)
                    SelectionContainer { Text(notice.optString("content")) }
                    if (error.isNotEmpty()) Text(error, color = MiuixTheme.colorScheme.error)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        { showDetail = false },
                        Modifier.weight(1f),
                        enabled = !busy,
                    ) {
                        Text("关闭")
                    }
                    if (!notice.flag("is_read"))
                        Button(
                            onClick = {
                                busy = true
                                error = ""
                                scope.launch {
                                    try {
                                        repo.execute(
                                            "user/mark_notice_read",
                                            json("notice_id" to notice.optLong("id")),
                                        )
                                        detail = JSONObject(notice.toString()).put("is_read", 1)
                                        reload()
                                    } catch (failure: CancellationException) {
                                        throw failure
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "标记失败，请重试"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(if (busy) "标记中…" else "标为已读")
                        }
                }
            }
        }
    }
}

@Composable
private fun NoticeRow(notice: JSONObject, open: () -> Unit) {
    BasicComponent(onClick = open, onClickLabel = "查看通知") {
        Text(
            notice.optString("title", "通知"),
            fontWeight = if (notice.flag("is_read")) FontWeight.Normal else FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            notice.optString("content"),
            Modifier.padding(top = 6.dp, bottom = 10.dp),
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        NoticeStatus(notice)
    }
}

@Composable
private fun NoticeStatus(notice: JSONObject) {
    val read = notice.flag("is_read")
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!read) Box(Modifier.size(6.dp).background(MiuixTheme.colorScheme.error, CircleShape))
        Text(
            if (read) "已读" else "未读",
            fontSize = 12.sp,
            color =
                if (read) MiuixTheme.colorScheme.onSurfaceVariantSummary
                else MiuixTheme.colorScheme.error,
        )
        Text(
            notice.optString("add_time"),
            Modifier.weight(1f),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
