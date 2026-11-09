package com.xiaobai.rapidcsac.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaobai.rapidcsac.AppGraph
import com.xiaobai.rapidcsac.BuildConfig
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.model.Conversation
import com.xiaobai.rapidcsac.core.network.ConnectionState
import com.xiaobai.rapidcsac.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomeScreen(graph: AppGraph, repo: ChatRepository, ui: UiController, nav: Navigation) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var chatFilter by rememberSaveable { mutableIntStateOf(0) }
    var contactFilter by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val conversations by repo.conversations.collectAsStateWithLifecycle()
    val user by repo.user.collectAsStateWithLifecycle()
    val moderator by repo.moderation.allowed.collectAsStateWithLifecycle()
    val connection by repo.connection.collectAsStateWithLifecycle()
    val connectionError by repo.error.collectAsStateWithLifecycle()
    val notices =
        rememberRemote(repo) {
            withTimeout(15_000) {
                repo.awaitReady()
                repo.execute("user/get_notifications")
            }
        }
    LaunchedEffect(repo) {
        repo.updates.collect { types ->
            if (types.any { it.startsWith("notification.") || it.startsWith("friend.") }) {
                delay(250)
                notices.reload()
            }
        }
    }

    val titles = remember { listOf("聊天", "联系人", "发现", "我") }
    val scrollBehavior = MiuixScrollBehavior()
    val listStates = List(4) { rememberLazyListState() }
    val textMeasurer = rememberTextMeasurer()
    val filterStyle =
        MiuixTheme.textStyles.main.copy(
            fontSize = MiuixTheme.textStyles.body1.fontSize,
            fontWeight = FontWeight.Bold,
        )
    val filterText =
        remember(textMeasurer, filterStyle) { textMeasurer.measure("全部", style = filterStyle) }
    val filterWidth =
        with(LocalDensity.current) { maxOf(62.dp, filterText.size.width.toDp() + 24.dp) }
    val filterHeight =
        with(LocalDensity.current) { maxOf(48.dp, filterText.size.height.toDp() + 24.dp) }
    BackHandler(tab != 0) { tab = 0 }
    val filtered =
        remember(conversations, query, tab, chatFilter, contactFilter) {
            conversations.filter { item ->
                val match = item.name.contains(query, true) || item.id.toString().contains(query)
                match &&
                    if (tab == 1) item.group == (contactFilter == 1)
                    else
                        when (chatFilter) {
                            1 ->
                                (item.unread > 0 || item.markedUnread) &&
                                    !item.archived &&
                                    !item.hidden
                            2 -> item.favorite && !item.hidden
                            3 -> item.group && !item.archived && !item.hidden
                            4 -> item.archived && !item.hidden
                            5 -> item.hidden
                            else -> !item.archived && !item.hidden
                        }
            }
        }
    Scaffold(
        modifier =
            if (tab == 0) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
            else Modifier,
        topBar = {
            val leadingTitle: @Composable () -> Unit = {
                BoxWithConstraints(Modifier.padding(start = TopAppBarDefaults.TitlePadding)) {
                    val titleWidth =
                        (maxWidth - 48.dp * 2 - TopAppBarDefaults.ActionIconPadding).coerceAtLeast(
                            0.dp
                        )
                    Text(
                        titles[tab],
                        Modifier.widthIn(max = titleWidth).graphicsLayer {
                            alpha =
                                if (tab == 0)
                                    (scrollBehavior.state.collapsedFraction * 3f).coerceIn(0f, 1f)
                                else 1f
                        },
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = MiuixTheme.textStyles.title3.fontSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val headerActions: @Composable RowScope.() -> Unit = {
                RapidIconButton("search", "搜索聊天记录") { nav.search() }
                AnchoredMenu("新建或添加", addActions(repo, ui, nav), "add")
            }
            if (tab == 0)
                TopAppBar(
                    title = "",
                    largeTitle = titles[tab],
                    navigationIcon = leadingTitle,
                    navigationIconPadding = 0.dp,
                    actions = headerActions,
                    scrollBehavior = scrollBehavior,
                )
            else
                SmallTopAppBar(
                    title = "",
                    navigationIcon = leadingTitle,
                    navigationIconPadding = 0.dp,
                    actions = headerActions,
                )
        },
        bottomBar = {
            NavigationBar(showDivider = false) {
                listOf("chat", "people", "discover", "person").forEachIndexed { index, icon ->
                    NavigationBarItem(
                        tab == index,
                        {
                            tab = index
                            query = ""
                        },
                        rapidIcon(icon),
                        titles[index],
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            state = listStates[tab],
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (tab <= 1) 0.dp else 12.dp),
        ) {
            if (tab == 0 && connection != ConnectionState.CONNECTED)
                item("connection") {
                    Text(
                        if (connectionError.isNotEmpty()) connectionError
                        else if (connection == ConnectionState.CONNECTING) "连接中…"
                        else "离线 · 可查看已缓存的聊天",
                        Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            if (tab <= 1) {
                item("search") {
                    TextField(
                        query,
                        { query = it },
                        label = if (tab == 0) "搜索聊天" else "搜索联系人或群组",
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                rapidIcon("search"),
                                null,
                                Modifier.padding(start = 14.dp, end = 10.dp).size(22.dp),
                            )
                        },
                    )
                }
                item("filters") {
                    TabRow(
                        if (tab == 0) listOf("全部", "未读", "关注", "群聊", "归档", "隐藏")
                        else listOf("好友", "群组"),
                        if (tab == 0) chatFilter else contactFilter,
                        { if (tab == 0) chatFilter = it else contactFilter = it },
                        Modifier.padding(vertical = 8.dp),
                        minWidth = filterWidth,
                        maxWidth = maxOf(98.dp, filterWidth),
                        height = filterHeight,
                    )
                }
                if (filtered.isEmpty())
                    item("empty") {
                        EmptyContent(
                            if (query.isNotEmpty()) "没有找到匹配项"
                            else if (tab == 0) "还没有聊天" else "暂无联系人",
                            if (query.isNotEmpty()) "试试其他关键词" else "通过右上角添加好友或加入群组",
                            modifier = Modifier.padding(top = 48.dp),
                            icon = "chat",
                        )
                    }
                items(filtered, key = { it.key }, contentType = { "conversation" }) { item ->
                    ConversationRow(
                        item,
                        repo,
                        tab == 1,
                        open = {
                            if (tab == 0) nav.chat(item.key, item.name)
                            else nav.detail(item.group, item.id)
                        },
                        menu = { conversationMenu(item, repo, ui, nav) },
                    )
                }
            } else if (tab == 2) {
                item { Card { MenuRow("好友动态", "查看与分享近况", "discover") { nav.open("feed") } } }
                item {
                    Card {
                        MenuRow("小程序", "eMApps", "file") { nav.open("apps") }
                        MenuRow("发现群组", icon = "people") { nav.records("public") }
                    }
                }
                item {
                    Card {
                        MenuRow("扫一扫与名片", icon = "scan") { nav.open("qr") }
                        MenuRow("聊天记录", "搜索本机已缓存的消息", "search") { nav.search() }
                    }
                }
            } else {
                item {
                    Card(onClick = { nav.open("profile") }) {
                        BasicComponent(
                            insideMargin = PaddingValues(22.dp),
                            startAction = {
                                Avatar(
                                    user.optString("nickname", repo.account.name),
                                    repo.avatarUrl(user.optString("avatar")),
                                    68,
                                )
                            },
                            endActions = { Icon(rapidIcon("more"), null, Modifier.size(22.dp)) },
                        ) {
                            Text(
                                user.optString("nickname", repo.account.name),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "UID ${repo.account.uid}",
                                Modifier.padding(top = 6.dp),
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Card {
                        MenuRow(
                            "通知与申请",
                            "${notices.value?.optInt("total_unread")?:0} 条未读",
                            "chat",
                        ) {
                            nav.records("notices")
                        }
                        MenuRow("我的二维码", icon = "scan") { nav.open("qr") }
                        if (moderator == true)
                            MenuRow("站点管理", "用户与群组封禁", "lock") { nav.open("moderation") }
                    }
                }
                item {
                    Card {
                        MenuRow("设置", "外观、隐私与本地数据", "settings") { nav.open("settings") }
                        MenuRow("切换账户", icon = "people") { savedAccounts(graph, ui) }
                    }
                }
                item {
                    Text(
                        "RapidCsAC ${BuildConfig.VERSION_NAME}",
                        Modifier.fillMaxWidth().padding(top = 18.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    item: Conversation,
    repo: ChatRepository,
    contact: Boolean,
    open: () -> Unit,
    menu: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = open, onLongClick = menu)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Avatar(item.name, repo.avatarUrl(item.avatar), 54)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    Modifier.weight(1f),
                    fontSize = 17.sp,
                    fontWeight = if (item.unread > 0) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.favorite)
                    Icon(
                        rapidIcon("heart"),
                        "特别关注",
                        Modifier.padding(start = 6.dp).size(14.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                if (item.pinned)
                    Icon(
                        rapidIcon("pin"),
                        "已置顶",
                        Modifier.padding(start = 6.dp).size(14.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (
                    item.draft.isEmpty() &&
                        item.lastMessage?.sender == repo.account.uid &&
                        item.lastMessage?.time == item.updated &&
                        item.lastMessage?.preview == item.subtitle &&
                        !contact
                )
                    MessageReceipt(item.lastMessage?.read == true)
                Text(
                    if (item.draft.isNotEmpty() && !contact) "草稿 · ${item.draft}"
                    else item.subtitle.ifEmpty { if (contact) "ID ${item.id}" else "开始聊天" },
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color =
                        if (item.draft.isNotEmpty()) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (!contact)
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (item.updated > 0)
                    Text(
                        conversationTime(item.updated),
                        fontSize = 12.sp,
                        color =
                            if (item.unread > 0) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                if (item.unread > 0 || item.markedUnread)
                    Box(
                        Modifier.clip(CircleShape)
                            .background(
                                if (item.muted) MiuixTheme.colorScheme.secondaryContainer
                                else MiuixTheme.colorScheme.primary
                            )
                            .defaultMinSize(20.dp, 20.dp)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (item.unread > 99) "99+"
                            else if (item.unread > 0) "${item.unread}" else "",
                            fontSize = 11.sp,
                            color =
                                if (item.muted) MiuixTheme.colorScheme.onSurface
                                else MiuixTheme.colorScheme.onPrimary,
                        )
                    }
            }
    }
}

private fun conversationTime(seconds: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = seconds * 1000 }
    val sameDay =
        now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(if (sameDay) "HH:mm" else "M/d", Locale.getDefault())
        .format(Date(seconds * 1000))
}

fun ChatRepository.avatarUrl(value: String): String =
    if (value.isBlank()) "" else media.resolve(value)

private fun conversationMenu(
    item: Conversation,
    repo: ChatRepository,
    ui: UiController,
    nav: Navigation,
) {
    ui.actions(
        item.name,
        listOf(
            ActionItem(if (item.pinned) "取消置顶" else "置顶", "pin") {
                ui.launch { repo.setPreference(item.key, "pinned", !item.pinned) }
            },
            ActionItem(if (item.muted) "取消静音" else "消息静音") {
                ui.launch { repo.setPreference(item.key, "muted", !item.muted) }
            },
            ActionItem(if (item.archived) "移出归档" else "归档", "file") {
                ui.launch { repo.setPreference(item.key, "archived", !item.archived) }
            },
            ActionItem(if (item.favorite) "取消特别关注" else "特别关注", "heart") {
                ui.launch { repo.setPreference(item.key, "favorite", !item.favorite) }
            },
            ActionItem(if (item.unread > 0 || item.markedUnread) "标为已读" else "标为未读", "chat") {
                ui.launch {
                    if (item.unread > 0 || item.markedUnread) repo.markConversationRead(item.key)
                    else repo.setPreference(item.key, "marked_unread", true)
                }
            },
            ActionItem(if (item.hidden) "显示会话" else "隐藏会话", "lock") {
                ui.launch { repo.toggleHidden(item.key) }
            },
            ActionItem("清空本机记录", "delete", true) {
                ui.confirm("清空本机记录", "清除这段会话的本机消息记录？", true) { repo.clearConversation(item.key) }
            },
            ActionItem("资料", "person") { nav.detail(item.group, item.id) },
        ),
    )
}

private fun addActions(repo: ChatRepository, ui: UiController, nav: Navigation): List<ActionItem> {
    fun lookup(group: Boolean) =
        ui.edit(
            if (group) "加入群组" else "添加好友",
            listOf(InputField("id", if (group) "群组 ID" else "用户 UID", numeric = true)),
        ) {
            val id =
                it.getString("id").toLongOrNull()?.takeIf { value -> value > 0 }
                    ?: error("请输入有效 ID")
            nav.detail(group, id)
        }
    return listOf(
        ActionItem("添加好友", "person") { lookup(false) },
        ActionItem("加入群组", "people") { lookup(true) },
        ActionItem("创建群组", "add") {
            ui.edit("创建群组", listOf(InputField("room_name", "群组名称"))) { params ->
                require(params.getString("room_name").isNotBlank()) { "请输入群组名称" }
                val result = repo.execute("group/create", params)
                repo.refresh()
                nav.chat("g:${result.getLong("room_id")}", params.getString("room_name"))
            }
        },
        ActionItem("扫一扫", "scan") { nav.open("qr") },
    )
}
