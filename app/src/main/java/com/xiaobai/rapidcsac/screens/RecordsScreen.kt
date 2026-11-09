package com.xiaobai.rapidcsac.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.model.Message
import com.xiaobai.rapidcsac.core.network.*
import com.xiaobai.rapidcsac.platform.MediaFiles
import com.xiaobai.rapidcsac.platform.openAttachment
import com.xiaobai.rapidcsac.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val recordTitles =
    mapOf(
        "notices" to "通知中心",
        "mentions" to "提及与回复",
        "requests" to "好友申请",
        "applications" to "入群申请",
        "members" to "群成员",
        "public" to "发现群组",
        "common" to "共同群组",
        "created" to "创建的群组",
        "essence" to "群精华",
        "essencestats" to "精华统计",
        "files" to "群文件",
        "media" to "共享媒体",
        "friendchanges" to "好友变动",
    )

@Composable
fun RecordsScreen(
    repo: ChatRepository,
    ui: UiController,
    nav: Navigation,
    kind: String,
    id: Long,
    key: String,
    manage: Boolean,
) {
    val context = LocalContext.current
    var canManage by remember { mutableStateOf(manage) }
    var owner by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var period by remember { mutableStateOf("all") }
    val remote =
        rememberRemote(repo, kind, id, key, period) {
            withTimeout(20_000) {
                repo.awaitReady()
                if (kind in setOf("files", "members")) {
                    val room = repo.execute("group/get_group_view_info", json("room_id" to id))
                    owner = room.flag("is_owner")
                    canManage = room.flag("is_admin") || owner
                }
                when (kind) {
                    "notices" -> repo.execute("user/get_notice_list").objects("notices")
                    "mentions" ->
                        repo.execute("message/get_mentions", json("limit" to 100)).let {
                            it.objects("mentions") + it.objects("replies")
                        }
                    "requests" -> repo.execute("friend/get_friend_requests").objects("requests")
                    "applications" ->
                        if (id > 0)
                            repo
                                .execute("group/get_applications", json("room_id" to id))
                                .objects("applications")
                        else
                            supervisorScope {
                                val slots = Semaphore(4)
                                repo.conversations.value
                                    .filter { it.group }
                                    .map { conversation ->
                                        async {
                                            slots.withPermit {
                                                try {
                                                    repo
                                                        .execute(
                                                            "group/get_applications",
                                                            json("room_id" to conversation.id),
                                                        )
                                                        .objects("applications")
                                                        .map {
                                                            it.put("room_id", conversation.id)
                                                                .put("room_name", conversation.name)
                                                        }
                                                } catch (failure: ApiFailure) {
                                                    val status =
                                                        failure.details?.optInt("http_status") ?: 0
                                                    if (
                                                        status == 403 ||
                                                            failure.code in listOf(403, -32003) ||
                                                            failure.message.contains("权限") ||
                                                            failure.message.contains("管理员")
                                                    )
                                                        emptyList()
                                                    else throw failure
                                                }
                                            }
                                        }
                                    }
                                    .awaitAll()
                                    .flatten()
                                    .sortedBy { it.optString("apply_time") }
                            }
                    "members" ->
                        repo.execute("group/get_members", json("room_id" to id)).objects("members")
                    "public" -> repo.execute("group/get_public_list").objects("groups")
                    "common" ->
                        repo
                            .execute("friend/get_common_groups", json("friend_id" to id))
                            .objects("groups")
                    "created" ->
                        repo.execute("user/get_created_groups", json("uid" to id)).objects("groups")
                    "essencestats" ->
                        repo
                            .execute(
                                "essence/get_essence_stats",
                                json("room_id" to id, "type" to period),
                            )
                            .let {
                                listOf(JSONObject(it.toString()).put("_summary", true)) +
                                    it.objects("rank")
                            }
                    "essence" ->
                        repo
                            .execute("essence/get_essence", json("room_id" to id))
                            .objects("essence_list")
                    "files" ->
                        repo.execute("group/file_list", json("room_id" to id)).objects("files")
                    "friendchanges" ->
                        repo.execute("friend/get_deleted_notices").let {
                            it.objects("notices").ifEmpty { it.objects("list") }
                        }
                    "media" ->
                        repo
                            .cachedMessages(key)
                            .filter { it.type in setOf(2, 3, 5, 6) }
                            .map { JSONObject(it.raw) }
                    else -> emptyList()
                }
            }
        }
    LaunchedEffect(repo, kind, id) {
        repo.updates.collect { types ->
            if (
                types.any {
                    it.startsWith("group.") ||
                        it.startsWith("friend.") ||
                        it.startsWith("notification.") ||
                        (kind in setOf("essence", "essencestats", "media", "mentions") &&
                            it.startsWith("message.")) ||
                        (kind == "mentions" && it == "conversation.read")
                }
            )
                remote.reload()
        }
    }
    val upload =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                ui.launch {
                    val part = MediaFiles(context).prepare(uri, "file")
                    try {
                        repo.media.upload("group/file_upload", json("room_id" to id), listOf(part))
                        remote.reload()
                    } finally {
                        part.file.delete()
                    }
                }
        }
    fun fileTools() {
        val actions = mutableListOf(ActionItem("上传文件", "add") { upload.launch(arrayOf("*/*")) })
        if (canManage) {
            actions +=
                ActionItem("新建文件分组") {
                    ui.edit("新建文件分组", listOf(InputField("name", "分组名称"))) { params ->
                        params.put("room_id", id)
                        params.put("action", "add")
                        repo.execute("group/file_groups", params)
                        remote.reload()
                    }
                }
            actions += ActionItem("管理文件分组") { fileGroups(repo, ui, id) { remote.reload() } }
        }
        actions += ActionItem("刷新", "refresh") { remote.reload() }
        ui.actions("群文件", actions)
    }
    fun handle(value: JSONObject, allow: Boolean) = ui.launch {
        repo.execute(
            if (kind == "requests") "friend/handle_request" else "group/handle_apply",
            if (kind == "requests")
                json(
                    "request_id" to value.optLong("id"),
                    "action" to if (allow) "agree" else "refuse",
                )
            else
                json(
                    "apply_id" to value.firstLong("apply_id", "id"),
                    "action" to if (allow) "pass" else "refuse",
                ),
        )
        repo.refresh()
        remote.reload()
    }
    fun select(value: JSONObject, title: String) {
        when (kind) {
            "essencestats" -> if (!value.flag("_summary")) nav.detail(false, value.optLong("uid"))
            "mentions" -> {
                val room = value.optLong("room_id")
                val message = value.optJSONObject("message") ?: value
                nav.chat(
                    "g:$room",
                    value.optString("room_name", "群聊"),
                    message.firstLong("msg_id", "id"),
                )
            }
            "public",
            "common",
            "created" -> nav.detail(true, value.firstLong("room_id", "id"))
            "members" -> nav.detail(false, value.firstLong("uid", "id"))
            "notices" ->
                ui.confirm(title, value.optString("content")) {
                    repo.execute("user/mark_notice_read", json("notice_id" to value.optLong("id")))
                    remote.reload()
                }
            "requests",
            "applications" ->
                if (value.optInt("status") == 0)
                    ui.actions(
                        title,
                        listOf(
                            ActionItem("同意", "check") { handle(value, true) },
                            ActionItem("拒绝", "close", true) { handle(value, false) },
                        ),
                    )
                else ui.inform("该申请已处理")
            "essence",
            "media" -> nav.chat(key.ifEmpty { "g:$id" }, "聊天", value.firstLong("msg_id", "id"))
            "files" ->
                ui.launch {
                    openAttachment(
                        context,
                        repo,
                        value.optString("file_path"),
                        value.optString("file_name"),
                    )
                }
            "friendchanges" ->
                ui.confirm("恢复好友关系", "恢复与此用户的好友关系？") {
                    repo.execute(
                        "friend/recover_friend",
                        json("friend_id" to value.firstLong("friend_id", "uid", "id")),
                    )
                    repo.refresh()
                    remote.reload()
                }
        }
    }
    Page(
        recordTitles[kind] ?: "列表",
        nav::back,
        actions = {
            RapidIconButton(
                if (kind in setOf("files", "notices")) "more" else "refresh",
                if (kind == "files") "文件选项" else if (kind == "notices") "通知选项" else "刷新",
            ) {
                if (kind == "files") fileTools()
                else if (kind == "notices")
                    ui.actions(
                        "通知选项",
                        listOf(
                            ActionItem("全部标为已读", "check") {
                                ui.launch {
                                    repo.execute("user/mark_notice_read", json("read_all" to 1))
                                    remote.reload()
                                }
                            },
                            ActionItem("刷新", "refresh") { remote.reload() },
                        ),
                    )
                else remote.reload()
            }
        },
    ) { padding ->
        PageList(padding) {
            if (kind == "essence")
                item {
                    Card {
                        MenuRow("精华统计", icon = "heart") { nav.records("essencestats", id, key) }
                    }
                }
            if (kind == "essencestats")
                item {
                    TabRow(
                        listOf("今天", "7 天", "一个月", "全部"),
                        listOf("today", "week", "month", "all").indexOf(period),
                        { period = listOf("today", "week", "month", "all")[it] },
                    )
                }
            if (kind == "notices")
                item {
                    Card {
                        MenuRow("提及与回复", icon = "reply") { nav.records("mentions") }
                        MenuRow("入群申请", icon = "people") { nav.records("applications") }
                        MenuRow("好友申请", icon = "person") { nav.records("requests") }
                        MenuRow("好友变动") { nav.records("friendchanges") }
                    }
                }
            item { RemoteStatus(remote) }
            val source = remote.value.orEmpty()
            if (kind in setOf("members", "files", "essence", "media", "public"))
                item {
                    TextField(filter, { filter = it }, label = "筛选名称或内容", singleLine = true)
                }
            if (kind == "files")
                item {
                    val groups =
                        listOf("全部") +
                            source.map { it.optString("file_group").ifEmpty { "默认分组" } }.distinct()
                    AnchoredMenu(
                        "按文件分组筛选",
                        groups.map { name ->
                            ActionItem(name, "file") { filter = if (name == "全部") "" else name }
                        },
                        "file",
                    )
                }
            val values = source.filter { value ->
                filter.isEmpty() ||
                    recordTitle(kind, value, key).contains(filter, true) ||
                    recordSummary(kind, value).contains(filter, true)
            }

            if (!remote.loading && remote.error.isEmpty() && values.isEmpty())
                item { EmptyContent("暂无内容", "有新内容时会显示在这里", icon = "file") }
            itemsIndexed(
                values,
                key = { index, value ->
                    "$kind:${value.firstLong("id", "uid", "room_id", "msg_id", "apply_id")}:$index"
                },
            ) { _, value ->
                val title = recordTitle(kind, value, key)
                val summary = recordSummary(kind, value)
                Card(onClick = { select(value, title) }) {
                    BasicComponent(
                        startAction = {
                            if (
                                kind in
                                    setOf(
                                        "members",
                                        "requests",
                                        "applications",
                                        "public",
                                        "common",
                                        "created",
                                    )
                            )
                                Avatar(title, repo.avatarUrl(value.optString("avatar")), 44)
                            else
                                Icon(
                                    rapidIcon(if (kind == "files") "file" else "chat"),
                                    null,
                                    Modifier.size(24.dp),
                                    tint = MiuixTheme.colorScheme.primary,
                                )
                        },
                        endActions = {
                            if (
                                kind == "members" &&
                                    canManage &&
                                    value.firstLong("uid", "id") != repo.account.uid &&
                                    !value.flag("is_owner")
                            )
                                RapidIconButton("more", "管理 $title") {
                                    memberMenu(value, repo, ui, id, owner) { remote.reload() }
                                }
                            else if (
                                kind == "files" &&
                                    (canManage || value.optLong("uploader_uid") == repo.account.uid)
                            )
                                RapidIconButton("more", "文件选项") {
                                    fileMenu(value, repo, ui, id) { remote.reload() }
                                }
                            else if (kind == "notices" && !value.flag("is_read"))
                                Text("未读", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
                        },
                    ) {
                        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (summary.isNotBlank())
                            Text(
                                summary,
                                Modifier.padding(top = 6.dp),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 14.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                    }
                }
            }
        }
    }
}

private fun recordTitle(kind: String, value: JSONObject, key: String) =
    when (kind) {
        "notices" -> value.optString("title", "通知")
        "mentions" ->
            (if (value.optString("kind") == "reply") "回复 · " else "提及 · ") +
                value.optString("room_name")
        "essencestats" ->
            if (value.flag("_summary"))
                "${value.optString("type_name")} · ${value.optInt("total")} 条精华"
            else "${value.optInt("rank")}. ${value.optString("nickname")}"
        "requests",
        "applications",
        "friendchanges" ->
            value.firstText("nickname", "from_nickname", "from_username").ifEmpty {
                "UID ${value.firstLong("from_uid", "uid", "friend_id")}"
            }
        "files" -> value.optString("file_name")
        "public",
        "common",
        "created" -> value.firstText("room_name", "name")
        "essence",
        "media" -> Message.parse(value, key).preview
        else -> value.firstText("nickname", "name").ifEmpty { "用户 ${value.firstLong("uid", "id")}" }
    }

private fun recordSummary(kind: String, value: JSONObject) =
    when (kind) {
        "notices" -> value.optString("content")
        "essencestats" ->
            if (value.flag("_summary"))
                "文字 ${value.optInt("text_count")} · 图片 ${value.optInt("image_count")} · 语音 ${value.optInt("voice_count")}"
            else "${value.optInt("count")} 条精华"
        "mentions" ->
            Message.parse(value.optJSONObject("message") ?: value, "g:${value.optLong("room_id")}")
                .preview
        "requests",
        "applications" ->
            listOf(
                    value.firstText("content", "answer_content", "room_name"),
                    when (value.optInt("status")) {
                        0 -> "待处理"
                        1 -> "已同意"
                        else -> "已拒绝"
                    },
                )
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        "files" ->
            "${value.optLong("file_size")} 字节 · ${value.optString("uploader_name")} · ${value.optString("file_group").ifEmpty { "默认分组" }}"
        "public",
        "common",
        "created" -> "ID ${value.firstLong("room_id", "id")} · ${value.optInt("member_count")} 人"
        else -> value.firstText("title", "online_status", "reason", "add_time", "intro")
    }

private fun memberMenu(
    value: JSONObject,
    repo: ChatRepository,
    ui: UiController,
    id: Long,
    owner: Boolean,
    reload: () -> Unit,
) {
    val uid = value.firstLong("uid", "id")
    if (uid == repo.account.uid) return
    fun operation(method: String, vararg fields: Pair<String, Any?>) = ui.launch {
        repo.execute(method, json("room_id" to id, "target_uid" to uid, *fields))
        reload()
    }
    ui.actions(
        value.optString("nickname"),
        listOfNotNull(
            ActionItem(if (value.flag("is_muted")) "解除禁言" else "禁言成员") {
                if (value.flag("is_muted")) operation("group/mute_member", "action" to "unmute")
                else
                    ui.edit(
                        "禁言成员",
                        listOf(InputField("minutes", "禁言分钟数", initial = "10", numeric = true)),
                    ) { params ->
                        val minutes = params.optString("minutes").toIntOrNull() ?: error("请输入有效分钟数")
                        require(minutes in 1..525600) { "请输入 1–525600 分钟" }
                        repo.execute(
                            "group/mute_member",
                            json(
                                "room_id" to id,
                                "target_uid" to uid,
                                "action" to "mute",
                                "minutes" to minutes,
                            ),
                        )
                        reload()
                    }
            },
            if (owner)
                ActionItem(if (value.flag("is_admin")) "撤销管理员" else "设为管理员") {
                    operation(
                        "group/set_admin",
                        "action" to if (value.flag("is_admin")) "remove" else "set",
                    )
                }
            else null,
            ActionItem("编辑头衔") {
                ui.edit(
                    "编辑头衔",
                    listOf(
                        InputField("title", "头衔", value.optString("title")),
                        InputField("level", "等级", value.optString("level"), numeric = true),
                    ),
                ) { params ->
                    params.put("room_id", id)
                    params.put("target_uid", uid)
                    repo.execute("group/set_member_title", params)
                    reload()
                }
            },
            ActionItem("拍一拍") { operation("message/send_pat_msg") },
            ActionItem("移出群组", "delete", true) {
                ui.confirm("移出群组", "将此用户移出群组？", true) {
                    repo.execute("group/kick_member", json("room_id" to id, "target_uid" to uid))
                    reload()
                }
            },
        ),
    )
}

private fun fileMenu(
    value: JSONObject,
    repo: ChatRepository,
    ui: UiController,
    id: Long,
    reload: () -> Unit,
) {
    ui.actions(
        value.optString("file_name"),
        listOf(
            ActionItem("移动到分组", "file") {
                ui.edit(
                    "移动到分组",
                    listOf(
                        InputField("target_group", "分组名称（留空移至默认）", value.optString("file_group"))
                    ),
                ) { params ->
                    params.put("file_id", value.optLong("id"))
                    repo.execute("group/file_move", params)
                    reload()
                }
            },
            ActionItem("删除文件", "delete", true) {
                ui.confirm("删除群文件", "删除 ${value.optString("file_name")}？", true) {
                    repo.execute(
                        "group/file_delete",
                        json("room_id" to id, "file_id" to value.optLong("id")),
                    )
                    reload()
                }
            },
        ),
    )
}

private fun fileGroups(repo: ChatRepository, ui: UiController, id: Long, reload: () -> Unit) =
    ui.launch {
        val array = repo.execute("group/file_groups", json("room_id" to id)).optJSONArray("groups")
        val names =
            if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }
        if (names.isEmpty()) {
            ui.inform("暂无文件分组")
            return@launch
        }
        ui.actions(
            "文件分组",
            names.map { name ->
                ActionItem(name, "file") {
                    ui.actions(
                        name,
                        listOf(
                            ActionItem("重命名") {
                                ui.edit("重命名分组", listOf(InputField("new_name", "新名称", name))) {
                                    params ->
                                    params.put("old_name", name)
                                    params.put("room_id", id)
                                    params.put("action", "rename")
                                    repo.execute("group/file_groups", params)
                                    reload()
                                }
                            },
                            ActionItem("删除分组", "delete", true) {
                                ui.confirm("删除分组", "删除 $name？其中的文件将移至默认分组。", true) {
                                    repo.execute(
                                        "group/file_groups",
                                        json("room_id" to id, "name" to name, "action" to "remove"),
                                    )
                                    reload()
                                }
                            },
                        ),
                    )
                }
            },
        )
    }
