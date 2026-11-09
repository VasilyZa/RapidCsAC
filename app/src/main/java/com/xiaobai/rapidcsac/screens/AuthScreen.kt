package com.xiaobai.rapidcsac.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaobai.rapidcsac.AppGraph
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.network.json
import com.xiaobai.rapidcsac.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AuthScreen(graph: AppGraph, ui: UiController) {
    var registering by rememberSaveable { mutableStateOf(false) }
    var origin by rememberSaveable { mutableStateOf("https://csac.chat") }
    var username by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    Scaffold { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 540.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Spacer(Modifier.height(20.dp))
                Icon(
                    rapidIcon("chat"),
                    null,
                    Modifier.size(54.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Text("RapidCsAC", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (registering) "创建你的账户" else "登录，继续聊天",
                    fontSize = 18.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
                Card(insideMargin = PaddingValues(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        RapidField(username, { username = it }, "用户名", enabled = !busy)
                        if (registering)
                            RapidField(nickname, { nickname = it }, "昵称", enabled = !busy)
                        RapidField(
                            password,
                            { password = it },
                            "密码",
                            password = true,
                            enabled = !busy,
                        )
                        if (registering) {
                            RapidField(email, { email = it }, "邮箱（按服务器要求填写）", enabled = !busy)
                            RapidField(
                                code,
                                { code = it },
                                "邮箱验证码",
                                numeric = true,
                                enabled = !busy,
                            )
                        }
                    }
                }
                if (error.isNotEmpty()) Text(error, color = MiuixTheme.colorScheme.error)
                PrimaryButton(
                    if (busy) "正在连接…" else if (registering) "注册并登录" else "登录",
                    Modifier.fillMaxWidth(),
                    !busy,
                ) {
                    if (username.isBlank() || password.isEmpty()) {
                        error = "请填写用户名和密码"
                    } else {
                        focus.clearFocus()
                        busy = true
                        error = ""
                        scope.launch {
                            try {
                                val params =
                                    json(
                                        "username" to username.trim(),
                                        "pwd" to password,
                                        "platform" to "Android-RapidCsAC-0.1",
                                    )
                                if (registering) {
                                    params.put(
                                        "nickname",
                                        nickname.trim().ifEmpty { username.trim() },
                                    )
                                    params.put("confirm_pwd", password)
                                    params.put("email", email.trim())
                                    params.put("email_code", code.trim())
                                }
                                graph.authenticate(
                                    origin,
                                    if (registering) "auth/register" else "auth/login",
                                    params,
                                )
                                password = ""
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                error = failure.message ?: "登录失败，请重试"
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
                Button(
                    {
                        registering = !registering
                        error = ""
                    },
                    Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text(if (registering) "已有账户，登录" else "创建账户")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button({ emailOptions(graph, ui, origin) }, enabled = !busy) { Text("邮箱与找回") }
                    Button({ savedAccounts(graph, ui) }, enabled = !busy) { Text("已保存账户") }
                }
                Spacer(Modifier.weight(1f, fill = false))
                BasicComponent(
                    title = "服务器",
                    summary = origin.removePrefix("https://"),
                    endActions = { Icon(rapidIcon("settings"), null, Modifier.size(20.dp)) },
                    onClick = {
                        ui.edit("连接服务器", listOf(InputField("server", "HTTPS 地址", origin))) {
                            origin = it.getString("server").trim().trimEnd('/')
                        }
                    },
                )
            }
        }
    }
}

fun savedAccounts(graph: AppGraph, ui: UiController) = ui.launch {
    val accounts = withContext(Dispatchers.IO) { graph.vault.accounts() }
    val actions =
        accounts.map { account ->
            ActionItem(
                "${account.name.ifEmpty { "UID ${account.uid}" }} · ${account.origin.removePrefix("https://")}",
                "person",
            ) {
                ui.actions(
                    account.name.ifEmpty { "UID ${account.uid}" },
                    listOf(
                        ActionItem("切换到此账户", "person") { ui.launch { graph.select(account) } },
                        ActionItem("移除此设备的账户", "delete", true) {
                            ui.confirm("移除账户", "移除此设备保存的 ${account.name} 凭据？", true) {
                                if (graph.repository.value?.account?.key == account.key)
                                    graph.logout()
                                else withContext(Dispatchers.IO) { graph.vault.remove(account) }
                            }
                        },
                    ),
                )
            }
        } + ActionItem("添加账户", "add") { ui.launch { graph.addAccount() } }
    ui.actions("账户", actions)
}

private fun emailOptions(graph: AppGraph, ui: UiController, origin: String) {
    fun request(title: String, method: String) {
        ui.edit(title, listOf(InputField("email", "邮箱"))) { params ->
            val value = graph.authenticate(origin, method, params)
            ui.inform(value.optString("message", "请求已发送"))
        }
    }
    fun login(code: Boolean) {
        ui.edit(
            if (code) "验证码登录" else "邮箱密码登录",
            listOf(
                InputField("email", "邮箱"),
                InputField(
                    if (code) "code" else "pwd",
                    if (code) "验证码" else "密码",
                    password = !code,
                ),
            ),
        ) {
            it.put("platform", "Android-RapidCsAC-0.1")
            graph.authenticate(
                origin,
                if (code) "auth/login_by_code" else "auth/login_by_email",
                it,
            )
        }
    }
    ui.actions(
        "邮箱与账户",
        listOf(
            ActionItem("发送登录验证码") { request("发送登录验证码", "auth/send_login_code") },
            ActionItem("使用验证码登录") { login(true) },
            ActionItem("使用邮箱和密码登录") { login(false) },
            ActionItem("发送注册验证码") { request("发送注册验证码", "auth/send_register_code") },
            ActionItem("发送账户恢复邮件") { request("恢复账户", "auth/request_restore") },
            ActionItem("使用恢复令牌") {
                ui.edit(
                    "恢复账户",
                    listOf(
                        InputField("email", "邮箱"),
                        InputField("restore_token", "恢复令牌", password = true),
                    ),
                ) {
                    ui.inform(
                        graph
                            .authenticate(origin, "auth/restore_account", it)
                            .optString("message", "请求已提交")
                    )
                }
            },
        ),
    )
}

@Composable
fun EmailScreen(repo: ChatRepository, ui: UiController, back: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Page("邮箱验证", back) { padding ->
        PageList(padding) {
            item {
                Text(
                    "完成服务器要求的邮箱验证。",
                    Modifier.padding(12.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            item {
                Card(insideMargin = PaddingValues(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        RapidField(email, { email = it }, "邮箱")
                        RapidField(code, { code = it }, "验证码", numeric = true)
                        Button(
                            {
                                ui.launch {
                                    ui.inform(
                                        repo
                                            .execute(
                                                "auth/send_email_bind_code",
                                                json("email" to email.trim()),
                                            )
                                            .optString("message", "验证码已发送")
                                    )
                                }
                            },
                            Modifier.fillMaxWidth(),
                        ) {
                            Text("发送验证码")
                        }
                        PrimaryButton("验证并继续", Modifier.fillMaxWidth()) {
                            ui.launch {
                                repo.execute(
                                    "auth/verify_email_bind_code",
                                    json("email" to email.trim(), "email_code" to code.trim()),
                                )
                                repo.pause()
                                repo.start()
                                ui.inform("邮箱已验证")
                            }
                        }
                    }
                }
            }
        }
    }
}
