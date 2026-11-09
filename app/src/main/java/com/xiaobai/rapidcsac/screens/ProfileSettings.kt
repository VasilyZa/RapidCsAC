package com.xiaobai.rapidcsac.screens

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaobai.rapidcsac.AppGraph
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.network.flag
import com.xiaobai.rapidcsac.core.network.json
import com.xiaobai.rapidcsac.platform.BackgroundSync
import com.xiaobai.rapidcsac.platform.MediaFiles
import com.xiaobai.rapidcsac.ui.*
import kotlinx.coroutines.withTimeout
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ProfileScreen(repo: ChatRepository, ui: UiController, nav: Navigation) {
    val context = LocalContext.current
    val remote =
        rememberRemote(repo) {
            withTimeout(20_000) {
                repo.awaitReady()
                repo.execute("user/get_info").getJSONObject("user")
            }
        }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                ui.launch {
                    val part = MediaFiles(context).prepare(uri, "avatar", true)
                    try {
                        repo.media.upload(
                            "user/update_profile",
                            json("action" to "avatar"),
                            listOf(part),
                        )
                        remote.reload()
                    } finally {
                        part.file.delete()
                    }
                }
        }
    Page("我的资料", nav::back) { padding ->
        PageList(padding) {
            item { RemoteStatus(remote) }
            val user = remote.value
            if (user != null) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Avatar(
                            user.optString("nickname"),
                            repo.avatarUrl(user.optString("avatar")),
                            88,
                        )
                        Button({ picker.launch(arrayOf("image/*")) }) { Text("更换头像") }
                    }
                }
                item {
                    Card {
                        MenuRow("昵称", user.optString("nickname")) {
                            ui.edit(
                                "修改昵称",
                                listOf(InputField("nickname", "昵称", user.optString("nickname"))),
                            ) { params ->
                                params.put("action", "nickname")
                                repo.execute("user/update_profile", params)
                                remote.reload()
                            }
                        }
                        MenuRow("拍一拍", user.optString("pat_action")) {
                            ui.edit(
                                "拍一拍动作",
                                listOf(
                                    InputField("pat_action", "动作", user.optString("pat_action"))
                                ),
                            ) { params ->
                                params.put("action", "pat_action")
                                repo.execute("user/update_profile", params)
                                remote.reload()
                            }
                        }
                        SwitchPreference(
                            checked = user.flag("allow_auto_join"),
                            onCheckedChange = { enabled ->
                                ui.launch {
                                    repo.execute(
                                        "user/update_profile",
                                        json("action" to "privacy", "allow_auto_join" to enabled),
                                    )
                                    remote.reload()
                                }
                            },
                            title = "允许邀请后自动入群",
                        )
                        MenuRow("我的二维码", icon = "scan") { nav.open("qr") }
                    }
                }
                item {
                    Card {
                        MenuRow("修改密码", icon = "lock") {
                            ui.edit(
                                "修改密码",
                                listOf(
                                    InputField("old_password", "当前密码", password = true),
                                    InputField("new_password", "新密码", password = true),
                                    InputField("confirm_password", "确认新密码", password = true),
                                ),
                            ) { params ->
                                require(
                                    params.getString("new_password") ==
                                        params.getString("confirm_password")
                                ) {
                                    "两次密码不一致"
                                }
                                params.put("action", "password")
                                repo.execute("user/update_profile", params)
                                ui.inform("密码已更新")
                            }
                        }
                        MenuRow("邮箱验证") { nav.open("email") }
                    }
                }
                item {
                    Text(
                        "UID ${repo.account.uid}\n${repo.account.origin}",
                        Modifier.padding(12.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(graph: AppGraph, repo: ChatRepository, ui: UiController, nav: Navigation) {
    val context = LocalContext.current
    val theme by graph.theme.collectAsStateWithLifecycle()
    val reduce by graph.reducedMotion.collectAsStateWithLifecycle()
    val locked by graph.lockEnabled.collectAsStateWithLifecycle()
    var notifications by remember {
        mutableStateOf(
            graph.preferences.getBoolean("notifications", true) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()
        )
    }
    var background by remember {
        mutableStateOf(graph.preferences.getBoolean("background_sync", false))
    }
    var enterSend by remember { mutableStateOf(graph.preferences.getBoolean("enter_send", false)) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notifications = granted
            graph.preferences.edit().putBoolean("notifications", granted).apply()
        }
    Page("设置", nav::back) { padding ->
        PageList(padding) {
            item {
                SectionLabel("外观与交互")
                Card {
                    OverlaySpinnerPreference(
                        items = listOf("跟随系统", "浅色", "深色").map { DropdownItem(text = it) },
                        selectedIndex =
                            listOf("system", "light", "dark").indexOf(theme).coerceAtLeast(0),
                        title = "外观",
                        onSelectedIndexChange = {
                            graph.preferences
                                .edit()
                                .putString("theme", listOf("system", "light", "dark")[it])
                                .apply()
                            graph.applyTheme()
                        },
                    )
                    SwitchPreference(
                        checked = reduce,
                        onCheckedChange = graph::setReducedMotion,
                        title = "减少动态效果",
                        summary = "简化页面与弹层动画",
                    )
                    SwitchPreference(
                        checked = enterSend,
                        onCheckedChange = {
                            enterSend = it
                            graph.preferences.edit().putBoolean("enter_send", it).apply()
                        },
                        title = "回车键发送",
                        summary = "关闭时使用回车换行",
                    )
                }
            }
            item {
                SectionLabel("隐私与通知")
                Card {
                    SwitchPreference(
                        checked = locked,
                        onCheckedChange = { enabled ->
                            if (
                                enabled &&
                                    !context
                                        .getSystemService(KeyguardManager::class.java)
                                        .isDeviceSecure
                            )
                                ui.inform("请先为设备设置锁屏凭据")
                            else graph.setAppLock(enabled)
                        },
                        title = "应用锁",
                        summary = "离开 30 秒后验证设备凭据",
                    )
                    SwitchPreference(
                        checked = notifications,
                        onCheckedChange = { enabled ->
                            if (
                                enabled &&
                                    Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    ) != PackageManager.PERMISSION_GRANTED
                            )
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else {
                                notifications = enabled
                                graph.preferences
                                    .edit()
                                    .putBoolean("notifications", enabled)
                                    .apply()
                            }
                        },
                        title = "消息通知",
                    )
                    SwitchPreference(
                        checked = background,
                        onCheckedChange = {
                            background = it
                            graph.preferences.edit().putBoolean("background_sync", it).apply()
                            BackgroundSync.schedule(context, it)
                        },
                        title = "后台同步",
                        summary = "系统约每 15 分钟调度，时间受省电策略影响",
                    )
                }
            }
            item {
                SectionLabel("数据与支持")
                Card {
                    MenuRow("清理消息缓存", "保留待发送消息", "file") {
                        ui.confirm("清理本机缓存", "清理此账户在本机缓存的消息记录？") {
                            repo.clearCache()
                            ui.inform("缓存已清理")
                        }
                    }
                    MenuRow("连接与本机数据", icon = "refresh") { nav.open("diagnostics") }
                    MenuRow("问题反馈", icon = "chat") {
                        ui.edit(
                            "问题反馈",
                            listOf(
                                InputField("title", "标题"),
                                InputField("description", "问题描述", multiline = true),
                            ),
                        ) {
                            repo.execute("bug_report", it)
                            ui.inform("反馈已提交")
                        }
                    }
                    MenuRow("开源许可", "第三方库与许可全文", "file") { nav.open("licenses") }
                    BasicComponent(
                        title = "RapidCsAC",
                        summary = "0.1.0 · ${repo.account.origin.removePrefix("https://")}",
                    )
                }
            }
            item {
                Card {
                    MenuRow("退出登录") { ui.confirm("退出登录", "移除此设备保存的当前账户凭据？") { graph.logout() } }
                    BasicComponent(
                        onClick = {
                            ui.confirm("注销账户", "向服务器申请注销账户并进入冷静期？恢复期限以服务器返回为准。", true) {
                                val result = repo.execute("user/delete_account")
                                ui.inform(result.optString("message", "注销申请已提交"))
                                graph.logout()
                            }
                        }
                    ) {
                        Text("注销账户", color = MiuixTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
