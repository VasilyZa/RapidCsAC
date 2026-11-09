package com.xiaobai.rapidcsac.screens

import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.ui.*
import kotlinx.coroutines.withTimeout
import top.yukonga.miuix.kmp.basic.*

@Composable
fun DiagnosticsScreen(repo: ChatRepository, ui: UiController, nav: Navigation) {
    val connection by repo.connection.collectAsStateWithLifecycle()
    val stats = rememberRemote(repo) { repo.statistics() }
    var result by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    Page("连接与本机数据", nav::back) { padding ->
        PageList(padding) {
            item {
                Card {
                    BasicComponent(title = "服务端", summary = repo.account.origin)
                    BasicComponent(title = "实时连接", summary = "WS v2 · $connection")
                    BasicComponent(
                        title = "系统",
                        summary = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
                    )
                    BasicComponent(title = "缓存消息", summary = "${stats.value?.first?:0} 条")
                    BasicComponent(title = "待确认的发送记录", summary = "${stats.value?.second?:0} 条")
                }
            }
            item {
                PrimaryButton(
                    if (running) "检测中…" else "检测真实请求耗时",
                    Modifier.fillMaxWidth(),
                    !running,
                ) {
                    running = true
                    ui.launch {
                        try {
                            withTimeout(15_000) {
                                val started = SystemClock.elapsedRealtime()
                                repo.execute("user/get_info")
                                result = "WS v2 用户信息请求 ${SystemClock.elapsedRealtime()-started} ms"
                            }
                        } finally {
                            running = false
                        }
                    }
                }
            }
            if (result.isNotEmpty()) item { Text(result, Modifier.padding(12.dp)) }
        }
    }
}
