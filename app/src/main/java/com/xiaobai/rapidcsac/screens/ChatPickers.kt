package com.xiaobai.rapidcsac.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.network.*
import com.xiaobai.rapidcsac.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*

@Composable
fun ChatPicker(
    repo: ChatRepository,
    ui: UiController,
    show: Boolean,
    members: Boolean,
    room: Long,
    dismiss: () -> Unit,
    onEmoji: suspend (String) -> Unit,
    onMembers: (List<JSONObject>) -> Unit,
) {
    var values by remember(repo, members, room) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(show, members, room, reload) {
        if (!show) return@LaunchedEffect
        loading = true
        error = ""
        try {
            values =
                repo
                    .execute(
                        if (members) "group/get_members" else "emoji/get_list",
                        if (members) json("room_id" to room) else JSONObject(),
                    )
                    .objects(if (members) "members" else "emojis")
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = failure.message ?: "加载失败"
        } finally {
            loading = false
        }
    }
    CompactSheet(
        show = show,
        title = if (members) "提及成员" else "表情",
        allowDismiss = !busy,
        onDismissRequest = { if (!busy) dismiss() },
        onDismissFinished = {
            query = ""
            selected = emptySet()
        },
    ) {
        Column(
            Modifier.heightIn(max = LocalWindowInfo.current.containerDpSize.height * .75f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                query,
                { query = it },
                label = if (members) "搜索群成员" else "搜索表情",
                singleLine = true,
            )
            if (loading) Text("加载中…")
            if (error.isNotEmpty()) Button({ reload++ }) { Text("$error · 重试") }
            val filtered = values.filter {
                it.firstText("nickname", "full_name", "abbr").contains(query, true)
            }
            if (members) {
                LazyColumn(
                    Modifier.weight(1f, fill = false)
                        .heightIn(max = LocalWindowInfo.current.containerDpSize.height * .45f)
                ) {
                    items(filtered, key = { it.firstLong("uid", "id") }) { member ->
                        val uid = member.firstLong("uid", "id")
                        CompactSheetRow(
                            title = member.optString("nickname"),
                            modifier =
                                Modifier.semantics {
                                    toggleableState =
                                        if (uid in selected) ToggleableState.On
                                        else ToggleableState.Off
                                },
                            role = Role.Checkbox,
                            leading = {
                                Avatar(
                                    member.optString("nickname"),
                                    repo.avatarUrl(member.optString("avatar")),
                                    32,
                                )
                            },
                            trailing = {
                                Checkbox(
                                    if (uid in selected) ToggleableState.On
                                    else ToggleableState.Off,
                                    null,
                                )
                            },
                            onClick = {
                                selected = if (uid in selected) selected - uid else selected + uid
                            },
                        )
                    }
                }
                PrimaryButton(
                    "提及 ${selected.size} 位成员",
                    Modifier.fillMaxWidth(),
                    enabled = selected.isNotEmpty(),
                ) {
                    onMembers(values.filter { it.firstLong("uid", "id") in selected })
                    dismiss()
                }
            } else
                LazyVerticalGrid(
                    GridCells.Adaptive(76.dp),
                    Modifier.weight(1f, fill = false)
                        .heightIn(max = LocalWindowInfo.current.containerDpSize.height * .5f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.optString("abbr") }) { emoji ->
                        Column(
                            Modifier.combinedClickable(
                                    enabled = !busy,
                                    onClick = {
                                        busy = true
                                        scope.launch {
                                            try {
                                                onEmoji(emoji.optString("abbr"))
                                                dismiss()
                                            } catch (failure: CancellationException) {
                                                throw failure
                                            } catch (failure: Exception) {
                                                ui.inform(failure.message ?: "发送失败")
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    },
                                )
                                .padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AsyncImage(
                                repo.avatarUrl(emoji.optString("address")),
                                emoji.firstText("full_name", "abbr"),
                                Modifier.size(56.dp),
                            )
                            Text(
                                emoji.firstText("full_name", "abbr"),
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
        }
    }
}
