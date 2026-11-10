package com.xiaobai.rapidcsac.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.network.ConnectionLost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun Page(
    title: String,
    back: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title,
                navigationIcon = { RapidIconButton("back", "返回", onClick = back) },
                actions = actions,
            )
        },
        content = content,
    )
}

@Composable
fun PageList(padding: PaddingValues, content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 12.dp, top = 8.dp, bottom = 6.dp),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
fun MenuRow(
    title: String,
    summary: String? = null,
    icon: String = "",
    badge: Int = 0,
    onClick: () -> Unit,
) {
    ArrowPreference(
        title = title,
        summary = summary?.takeIf { it.isNotBlank() },
        startAction =
            if (icon.isEmpty()) null
            else {
                {
                    Icon(
                        rapidIcon(icon),
                        null,
                        Modifier.size(24.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
            },
        endActions = { UnreadBadge(badge) },
        onClick = onClick,
    )
}

@Composable
fun UnreadBadge(count: Int) {
    if (count <= 0) return
    Box(
        Modifier.semantics(mergeDescendants = true) {
                contentDescription = "$count 条待处理"
            }
            .background(MiuixTheme.colorScheme.error, CircleShape)
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onError,
        )
    }
}

@Stable
class RemoteData<T> {
    var value by mutableStateOf<T?>(null)
        internal set

    var error by mutableStateOf("")
        internal set

    var loading by mutableStateOf(true)
        internal set

    internal var revision by mutableIntStateOf(0)

    fun reload() {
        revision++
    }
}

@Composable
fun <T> rememberRemote(vararg keys: Any?, load: suspend () -> T): RemoteData<T> {
    val remote = remember(*keys) { RemoteData<T>() }
    // Repository-backed loads can retry after the next successful synchronization.
    val repo = keys.firstOrNull { it is ChatRepository } as? ChatRepository
    val currentLoad by rememberUpdatedState(load)
    LaunchedEffect(remote, remote.revision) {
        val revision = remote.revision
        val generation = repo?.readyGeneration?.value ?: 0L
        remote.loading = true
        remote.error = ""
        try {
            remote.value = currentLoad()
        } catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException)
                throw failure
            currentCoroutineContext().ensureActive()
            remote.error =
                if (failure is TimeoutCancellationException) "加载超时，请重试"
                else failure.message ?: "加载失败"
            if (
                repo != null &&
                    (failure is ConnectionLost || failure is TimeoutCancellationException)
            ) {
                remote.loading = false
                repo.readyGeneration.first { it > generation }
                remote.reload()
            }
        } finally {
            if (remote.revision == revision) remote.loading = false
        }
    }
    return remote
}

@Composable
fun RemoteStatus(remote: RemoteData<*>) {
    if (remote.error.isNotEmpty())
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(remote.error, color = MiuixTheme.colorScheme.error)
                Button({ remote.reload() }) { Text("重新加载") }
            }
        }
    else if (remote.loading && remote.value == null)
        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
            Text("正在加载…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
}
