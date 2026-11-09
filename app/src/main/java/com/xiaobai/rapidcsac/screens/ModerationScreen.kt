package com.xiaobai.rapidcsac.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.network.*
import com.xiaobai.rapidcsac.ui.*
import top.yukonga.miuix.kmp.basic.*

@Composable
fun ModerationScreen(repo: ChatRepository, ui: UiController, nav: Navigation) {
    val allowed by repo.moderation.allowed.collectAsStateWithLifecycle()
    var group by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val remote =
        rememberRemote(repo, allowed) {
            check(allowed == true) { "此账户没有站点管理权限" }
            repo.moderation.bans()
        }
    fun act(target: Long, isGroup: Boolean, title: String, banned: Boolean) {
        val kind = if (isGroup) "群组" else "用户"
        ui.actions(
            "$kind · $title · $target",
            listOf(
                ActionItem("查看资料", "person") { nav.detail(isGroup, target) },
                if (banned)
                    ActionItem("解除封禁", "lock") {
                        ui.confirm("解除$kind 封禁", "解除 $title（ID $target）的封禁？") {
                            repo.moderation.unban(isGroup, target)
                            remote.reload()
                            ui.inform("已解除封禁")
                        }
                    }
                else
                    ActionItem("封禁$kind", "lock", true) {
                        ui.edit(
                            "封禁$kind · $target",
                            listOf(
                                InputField("days", "封禁天数（1–3650）", initial = "1", numeric = true),
                                InputField("reason", "封禁原因", multiline = true),
                            ),
                        ) { params ->
                            val days = params.optString("days").toIntOrNull() ?: error("请输入有效天数")
                            val reason = params.optString("reason").trim()
                            require(days in 1..3650 && reason.isNotEmpty()) { "请填写 1–3650 天和封禁原因" }
                            ui.confirm("确认封禁", "$title（ID $target）\n封禁 $days 天\n$reason", true) {
                                repo.moderation.ban(isGroup, target, days, reason)
                                remote.reload()
                                ui.inform("封禁已生效")
                            }
                        }
                    },
            ),
        )
    }
    fun lookup() {
        ui.edit(
            "按 ID 管理",
            listOf(
                InputField("id", "用户或群组 ID", numeric = true),
                InputField("group", "群组", toggle = true, initial = if (group) "true" else "false"),
            ),
        ) { params ->
            val id = params.optString("id").toLongOrNull()?.takeIf { it > 0 } ?: error("请输入有效 ID")
            val isGroup = params.flag("group")
            val result =
                repo.execute(
                    if (isGroup) "group/get_group_view_info" else "user/get_info",
                    json((if (isGroup) "room_id" else "uid") to id),
                )
            val value = result.optJSONObject(if (isGroup) "room" else "user") ?: error("对象不存在")
            val title =
                value.firstText("room_name", "nickname", "username").ifEmpty { id.toString() }
            act(
                id,
                isGroup,
                title,
                remote.value?.objects(if (isGroup) "rooms" else "users")?.any {
                    it.optLong("id") == id
                } == true,
            )
        }
    }
    Page(
        "站点管理",
        nav::back,
        actions = {
            if (allowed == true)
                AnchoredMenu(
                    "管理操作",
                    listOf(
                        ActionItem("按 ID 管理", "search", run = ::lookup),
                        ActionItem("恢复注销中的账户", "person") {
                            ui.edit("恢复账户", listOf(InputField("uid", "账户 UID", numeric = true))) {
                                params ->
                                val uid =
                                    params.optString("uid").toLongOrNull()?.takeIf { it > 0 }
                                        ?: error("请输入有效 UID")
                                ui.confirm("恢复账户", "恢复 UID $uid 的账户？此操作需要站点主管理员权限。", true) {
                                    repo.moderation.restore(uid)
                                    ui.inform("账户已恢复")
                                }
                            }
                        },
                        ActionItem("刷新", "refresh") { remote.reload() },
                    ),
                )
        },
    ) { padding ->
        PageList(padding) {
            if (allowed != true) item { EmptyContent("无法访问站点管理", "需要服务端授予的管理员权限", icon = "lock") }
            else {
                item { TextField(query, { query = it }, label = "搜索封禁对象", singleLine = true) }
                item { TabRow(listOf("用户", "群组"), if (group) 1 else 0, { group = it == 1 }) }
                item { RemoteStatus(remote) }
                val values = remote.value?.objects(if (group) "rooms" else "users").orEmpty()
                val filtered = values.filter {
                    query.isBlank() || it.toString().contains(query, true)
                }
                if (!remote.loading && remote.error.isEmpty() && filtered.isEmpty())
                    item { EmptyContent("没有封禁记录", "通过右上角按 ID 查找管理对象", icon = "check") }
                items(filtered, key = { it.optLong("id") }) { value ->
                    val id = value.optLong("id")
                    val name = value.firstText("room_name", "nickname", "username")
                    Card(onClick = { act(id, group, name, true) }) {
                        BasicComponent {
                            Text("$name · $id")
                            Text(
                                value.optString("ban_reason"),
                                Modifier.padding(top = 6.dp),
                                fontSize = 14.sp,
                            )
                            Text(
                                "解封时间 ${value.firstText("ban_until_text","ban_until_formatted","ban_until")}",
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
