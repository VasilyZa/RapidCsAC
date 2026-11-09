package com.xiaobai.rapidcsac.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
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
fun MenuRow(title: String, summary: String? = null, icon: String = "", onClick: () -> Unit) {
    ArrowPreference(
        title = title,
        summary = summary,
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
        onClick = onClick,
    )
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
    val currentLoad by rememberUpdatedState(load)
    LaunchedEffect(remote, remote.revision) {
        remote.loading = true
        remote.error = ""
        try {
            remote.value = currentLoad()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            remote.error = failure.message ?: "加载失败"
        } finally {
            remote.loading = false
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
