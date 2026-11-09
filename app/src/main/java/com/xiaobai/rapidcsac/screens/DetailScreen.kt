package com.xiaobai.rapidcsac.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.network.*
import com.xiaobai.rapidcsac.platform.MediaFiles
import com.xiaobai.rapidcsac.platform.copyText
import com.xiaobai.rapidcsac.ui.*
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DetailScreen(
    repo: ChatRepository,
    ui: UiController,
    nav: Navigation,
    group: Boolean,
    id: Long,
) {
    val context = LocalContext.current
    val conversations by repo.conversations.collectAsStateWithLifecycle()
    val remote =
        rememberRemote(repo, group, id) {
            withTimeout(20_000) {
                repo.awaitReady()
                repo.execute(
                    if (group) "group/get_group_view_info" else "user/get_info",
                    json((if (group) "room_id" else "uid") to id),
                )
            }
        }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                ui.launch {
                    val part = MediaFiles(context).prepare(uri, "avatar", true)
                    try {
                        repo.media.upload(
                            "group/edit_info",
                            json("room_id" to id, "action" to "avatar"),
                            listOf(part),
                        )
                        remote.reload()
                    } finally {
                        part.file.delete()
                    }
                }
        }
    Page(
        if (group) "群组资料" else "用户资料",
        nav::back,
        actions = { RapidIconButton("refresh", "刷新") { remote.reload() } },
    ) { padding ->
        PageList(padding) {
            item { RemoteStatus(remote) }
            val details = remote.value
            val value = details?.optJSONObject(if (group) "room" else "user")
            if (value != null) {
                val name = value.firstText("room_name", "nickname", "username").ifEmpty { "ID $id" }
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(name, repo.avatarUrl(value.optString("avatar")), 84)
                        Text(name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        Button({
                            copyText(context, id.toString())
                            ui.inform("已复制 ID")
                        }) {
                            Text(
                                "${if (group) "群组 ID" else "UID"} $id",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
                if (group) {
                    val member = details.flag("is_in_group")
                    val owner = details.flag("is_owner")
                    val admin = owner || details.flag("is_admin")
                    item {
                        PrimaryButton(if (member) "进入聊天" else "加入群组", Modifier.fillMaxWidth()) {
                            if (member) nav.chat("g:$id", name)
                            else
                                ui.edit(
                                    "加入群组",
                                    listOf(
                                        InputField("code", "邀请码（按要求填写）"),
                                        InputField(
                                            "answer",
                                            value.optString("ask_question").ifBlank { "申请说明或入群答案" },
                                            multiline = true,
                                        ),
                                    ),
                                ) { params ->
                                    params.put("room_id", id)
                                    val result = repo.execute("group/apply_join", params)
                                    ui.inform(result.optString("message", "已提交"))
                                    repo.refresh()
                                    remote.reload()
                                }
                        }
                    }
                    if (value.firstText("intro", "description", "notice").isNotEmpty())
                        item {
                            Card {
                                if (value.firstText("intro", "description").isNotEmpty())
                                    BasicComponent(
                                        title = "群介绍",
                                        summary = value.firstText("intro", "description"),
                                    )
                                if (value.optString("notice").isNotBlank())
                                    BasicComponent(
                                        title = "群公告",
                                        summary = value.optString("notice"),
                                    )
                            }
                        }
                    if (member)
                        item {
                            Card {
                                MenuRow(
                                    "群成员",
                                    "${conversations.firstOrNull { it.group && it.id == id }?.let { JSONObject(it.raw).optInt("member_count") } ?: 0} 人",
                                    "people",
                                ) {
                                    nav.records("members", id, "g:$id", admin)
                                }
                                MenuRow("群精华", icon = "heart") {
                                    nav.records("essence", id, "g:$id")
                                }
                                MenuRow("群文件", icon = "file") {
                                    nav.records("files", id, "g:$id", admin)
                                }
                            }
                        }
                    if (details.flag("can_view_invite"))
                        item {
                            Card {
                                MenuRow("邀请码", value.optString("invite_code")) {
                                    copyText(context, value.optString("invite_code"))
                                    ui.inform("已复制邀请码")
                                }
                                MenuRow("分享群组", icon = "share") { nav.qr("rapidcsac://group/$id") }
                            }
                        }
                    if (admin)
                        item {
                            SectionLabel("群组管理")
                            Card {
                                MenuRow("编辑群资料") {
                                    ui.edit(
                                        "群资料",
                                        listOf(
                                            InputField("room_name", "群名称", name),
                                            InputField(
                                                "intro",
                                                "群介绍",
                                                value.optString("intro"),
                                                multiline = true,
                                            ),
                                            InputField(
                                                "notice",
                                                "群公告",
                                                value.optString("notice"),
                                                multiline = true,
                                            ),
                                        ),
                                    ) { params ->
                                        params.put("room_id", id)
                                        repo.execute("group/edit_info", params)
                                        repo.refresh()
                                        remote.reload()
                                    }
                                }
                                MenuRow("更换群头像", icon = "image") {
                                    picker.launch(arrayOf("image/*"))
                                }
                                MenuRow("入群申请") { nav.records("applications", id) }
                                MenuRow("邀请成员") {
                                    ui.edit(
                                        "邀请成员",
                                        listOf(InputField("target_uid", "用户 UID", numeric = true)),
                                    ) { params ->
                                        require(params.optLong("target_uid") > 0) { "请输入有效 UID" }
                                        params.put("room_id", id)
                                        repo.execute("group/invite_member", params)
                                        ui.inform("邀请已发送")
                                    }
                                }
                                MenuRow("加入方式与权限") {
                                    editGroupSettings(repo, ui, id, value) { remote.reload() }
                                }
                                if (owner)
                                    MenuRow("重置邀请码") {
                                        ui.confirm("重置邀请码", "旧的邀请码将立即失效。") {
                                            repo.execute(
                                                "group/reset_invite_code",
                                                json("room_id" to id),
                                            )
                                            remote.reload()
                                        }
                                    }
                            }
                        }
                    if (owner)
                        item {
                            Card {
                                MenuRow("转让群组") {
                                    ui.edit(
                                        "转让群组",
                                        listOf(InputField("target_uid", "新群主 UID", numeric = true)),
                                    ) { params ->
                                        val target = params.optLong("target_uid")
                                        require(target > 0 && target != repo.account.uid) {
                                            "请输入有效的新群主 UID"
                                        }
                                        ui.confirm("转让群组", "将 $name 的群主权限转让给 UID $target？", true) {
                                            repo.execute(
                                                "group/transfer",
                                                json("room_id" to id, "target_uid" to target),
                                            )
                                            repo.refresh()
                                            remote.reload()
                                        }
                                    }
                                }
                                BasicComponent(
                                    onClick = {
                                        ui.confirm("解散群组", "解散 $name？所有成员将无法继续在此聊天。", true) {
                                            repo.execute("group/disband", json("room_id" to id))
                                            repo.refresh()
                                            nav.home()
                                        }
                                    }
                                ) {
                                    Text("解散群组", color = MiuixTheme.colorScheme.error)
                                }
                            }
                        }
                    else if (member)
                        item {
                            Card {
                                BasicComponent(
                                    onClick = {
                                        ui.confirm("退出群组", "退出 $name？", true) {
                                            repo.execute("group/leave", json("room_id" to id))
                                            repo.refresh()
                                            nav.home()
                                        }
                                    }
                                ) {
                                    Text("退出群组", color = MiuixTheme.colorScheme.error)
                                }
                            }
                        }
                } else {
                    val friend = value.flag("is_friend")
                    if (id == repo.account.uid)
                        item {
                            PrimaryButton("编辑我的资料", Modifier.fillMaxWidth()) { nav.open("profile") }
                        }
                    else {
                        if (friend)
                            item {
                                PrimaryButton("发送消息", Modifier.fillMaxWidth()) {
                                    nav.chat("p:$id", name)
                                }
                            }
                        else if (value.flag("can_add_friend"))
                            item {
                                PrimaryButton("添加好友", Modifier.fillMaxWidth()) {
                                    ui.edit("添加好友", listOf(InputField("content", "申请说明"))) { params
                                        ->
                                        params.put("to_uid", id)
                                        repo.execute("friend/send_request", params)
                                        ui.inform("申请已发送")
                                        remote.reload()
                                    }
                                }
                            }
                        else
                            item {
                                Card {
                                    BasicComponent(
                                        title = if (value.flag("is_blocked")) "已拉黑" else "等待好友申请处理"
                                    )
                                }
                            }
                        item {
                            Card {
                                if (friend)
                                    MenuRow("好友备注", value.optString("remark")) {
                                        ui.edit(
                                            "好友备注",
                                            listOf(
                                                InputField(
                                                    "remark",
                                                    "备注",
                                                    value.optString("remark"),
                                                )
                                            ),
                                        ) { params ->
                                            params.put("friend_id", id)
                                            repo.execute("friend/update_remark", params)
                                            repo.refresh()
                                            remote.reload()
                                        }
                                    }
                                MenuRow("共同群组") { nav.records("common", id) }
                                MenuRow("创建的群组") { nav.records("created", id) }
                                MenuRow("分享名片", icon = "share") { nav.qr("rapidcsac://user/$id") }
                            }
                        }
                        if (friend || value.flag("is_blocked"))
                            item {
                                Card {
                                    if (friend) {
                                        MenuRow("删除好友") {
                                            ui.confirm("删除好友", "删除与 $name 的好友关系？", true) {
                                                repo.execute(
                                                    "friend/delete_friend",
                                                    json("friend_id" to id),
                                                )
                                                repo.refresh()
                                                remote.reload()
                                            }
                                        }
                                        MenuRow("拉黑用户") {
                                            ui.confirm("拉黑用户", "拉黑 $name 后将无法继续私聊。", true) {
                                                repo.execute(
                                                    "friend/block_friend",
                                                    json("friend_id" to id),
                                                )
                                                repo.refresh()
                                                remote.reload()
                                            }
                                        }
                                    } else
                                        MenuRow("恢复好友关系") {
                                            ui.confirm("恢复好友", "恢复与 $name 的好友关系？") {
                                                repo.execute(
                                                    "friend/recover_friend",
                                                    json("friend_id" to id),
                                                )
                                                repo.refresh()
                                                remote.reload()
                                            }
                                        }
                                }
                            }
                    }
                }
                if (group || id != repo.account.uid)
                    item {
                        Card {
                            MenuRow(if (group) "举报群组" else "举报用户") { report(repo, ui, group, id) }
                        }
                    }
            }
        }
    }
}

private fun editGroupSettings(
    repo: ChatRepository,
    ui: UiController,
    id: Long,
    value: JSONObject,
    reload: () -> Unit,
) {
    ui.edit(
        "加入方式与权限",
        listOf(
            InputField(
                "join_type",
                "加入方式",
                (value.optInt("join_type", 1) - 1).coerceIn(0, 3).toString(),
                choices = listOf("开放加入", "动态邀请码", "固定邀请码", "问题审核"),
            ),
            InputField(
                "fixed_code",
                "固定邀请码",
                value.optString("fixed_code"),
                visibleWhen = "join_type" to "2",
            ),
            InputField(
                "question",
                "入群问题",
                value.optString("ask_question"),
                visibleWhen = "join_type" to "3",
            ),
            InputField(
                "answer",
                "新入群答案（留空保留）",
                "",
                visibleWhen = "join_type" to "3",
            ),
            InputField(
                "show_in_list",
                "在公开群组中展示",
                if (value.flag("show_in_list")) "1" else "0",
                toggle = true,
            ),
            InputField(
                "allow_invite",
                "允许成员邀请",
                if (value.flag("allow_invite")) "1" else "0",
                toggle = true,
            ),
            InputField(
                "allow_search",
                "允许通过群号搜索加入",
                if (value.flag("allow_search")) "1" else "0",
                toggle = true,
            ),
        ),
    ) { params ->
        params.put("room_id", id)
        params.put("join_type", params.getInt("join_type") + 1)
        repo.execute("group/update_settings", params)
        reload()
    }
}

private fun report(repo: ChatRepository, ui: UiController, group: Boolean, id: Long) {
    ui.edit(
        "提交举报",
        listOf(
            InputField("reason", "举报原因", multiline = true),
            InputField("anonymous", "匿名举报", "0", toggle = true),
        ),
    ) { params ->
        require(params.getString("reason").isNotBlank()) { "请填写举报原因" }
        params.put("type", if (group) "group" else "user")
        params.put(if (group) "rid" else "uid", id)
        repo.execute("report/submit_report", params)
        ui.inform("举报已提交")
    }
}
