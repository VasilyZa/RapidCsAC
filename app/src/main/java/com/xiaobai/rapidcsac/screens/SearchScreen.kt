package com.xiaobai.rapidcsac.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.model.Message
import com.xiaobai.rapidcsac.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SearchScreen(repo: ChatRepository, ui: UiController, nav: Navigation, key: String) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableIntStateOf(0) }
    var sender by rememberSaveable { mutableLongStateOf(0L) }
    var days by rememberSaveable { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<Message>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    val conversations by repo.conversations.collectAsStateWithLifecycle()
    val scopes = listOf("全部", "私聊", "群聊", "图片", "语音", "文件", "精华")
    val types = listOf("all", "private", "group", "image", "voice", "file", "essence")
    LaunchedEffect(query, kind, key, sender, days) {
        delay(250)
        error = ""
        try {
            results =
                if (query.isBlank() && kind == 0 && sender == 0L && days == 0) emptyList()
                else
                    repo.search(
                        query.trim(),
                        key,
                        sender,
                        if (days > 0) System.currentTimeMillis() / 1000 - days * 86400L else 0,
                        type = types[kind],
                    )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = failure.message ?: "搜索失败"
        }
    }
    Page(
        "搜索",
        nav::back,
        actions = {
            AnchoredMenu(
                "搜索范围",
                scopes.mapIndexed { index, title -> ActionItem(title) { kind = index } } +
                    listOf(
                        ActionItem("按发送者和日期筛选", "person") {
                            ui.edit(
                                "筛选消息",
                                listOf(
                                    InputField(
                                        "sender",
                                        "发送者 UID（0 为全部）",
                                        sender.toString(),
                                        numeric = true,
                                    ),
                                    InputField(
                                        "days",
                                        "最近天数（0 为全部）",
                                        days.toString(),
                                        numeric = true,
                                    ),
                                ),
                            ) {
                                val uid =
                                    it.optString("sender").toLongOrNull() ?: error("请输入有效 UID")
                                val count = it.optString("days").toIntOrNull() ?: error("请输入有效天数")
                                require(uid >= 0 && count in 0..36500) { "请输入有效范围" }
                                sender = uid
                                days = count
                            }
                        }
                    ),
            )
        },
    ) { padding ->
        PageList(padding) {
            item { TextField(query, { query = it }, label = "搜索联系人、群组与本机消息", singleLine = true) }
            if (key.isEmpty() && query.isNotBlank()) {
                val matches = conversations.filter {
                    it.name.contains(query, true) || it.id.toString() == query
                }
                if (matches.isNotEmpty()) item { SectionLabel("联系人与群组") }
                items(matches, key = { "conversation:${it.key}" }) { conversation ->
                    MenuRow(
                        conversation.name,
                        "ID ${conversation.id}",
                        if (conversation.group) "people" else "person",
                    ) {
                        nav.chat(conversation.key, conversation.name)
                    }
                }
                query
                    .toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { uid ->
                        item {
                            Card {
                                MenuRow("查找用户 $uid", icon = "person") { nav.detail(false, uid) }
                                MenuRow("查找群组 $uid", icon = "people") { nav.detail(true, uid) }
                            }
                        }
                    }
            }
            item {
                Text(
                    if (error.isNotEmpty()) error
                    else
                        "${scopes[kind]} · ${results.size} 条消息 · 最多 200 条${if(sender>0) " · UID $sender" else ""}${if(days>0) " · 最近 $days 天" else ""}",
                    Modifier.padding(horizontal = 12.dp),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (results.isEmpty())
                item { EmptyContent("没有匹配的本机消息", "可按关键词、类型、日期和发送者筛选", icon = "search") }
            items(results, key = { "${it.conversation}:${it.id}" }) { message ->
                val name =
                    conversations.firstOrNull { it.key == message.conversation }?.name ?: "聊天"
                Card(onClick = { nav.chat(message.conversation, name, message.id) }) {
                    BasicComponent {
                        Text(message.preview, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${message.name} · $name",
                            Modifier.padding(top = 6.dp),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}
