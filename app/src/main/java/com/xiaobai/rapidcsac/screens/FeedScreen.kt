package com.xiaobai.rapidcsac.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.network.*
import com.xiaobai.rapidcsac.platform.MediaFiles
import com.xiaobai.rapidcsac.ui.*
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun FeedScreen(repo: ChatRepository, ui: UiController, nav: Navigation) {
    val context = LocalContext.current
    var values by remember { mutableStateOf<List<String>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var newActivity by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replyTarget by remember { mutableLongStateOf(0L) }
    val remote =
        rememberRemote(repo) {
            withTimeout(20_000) {
                repo.awaitReady()
                repo.execute("space/get_list", json("page" to 1, "page_size" to 20))
            }
        }
    LaunchedEffect(remote.value) {
        remote.value?.let { result ->
            values = result.objects("list").map { it.toString() }
            page = 1
            more = values.size < result.optInt("total")
            newActivity = false
        }
    }
    LaunchedEffect(repo) {
        repo.updates.collect { types ->
            if (types.any { it.startsWith("space.") }) newActivity = true
        }
    }
    fun compose(images: List<Uri>, replyTo: Long = 0) {
        ui.edit(
            if (replyTo > 0) "评论"
            else if (images.isEmpty()) "发布动态" else "发布动态 · ${images.size} 张图片",
            listOf(InputField("content", "写点什么", multiline = true)),
        ) { params ->
            require(params.getString("content").isNotBlank() || images.isNotEmpty()) {
                "请填写内容或选择图片"
            }
            if (replyTo > 0) params.put("reply_id", replyTo)
            val route = if (replyTo > 0) "space/reply" else "space/send"
            val parts = mutableListOf<UploadPart>()
            try {
                if (images.isEmpty()) repo.execute(route, params)
                else {
                    images.forEach { parts += MediaFiles(context).prepare(it, "images", true) }
                    repo.media.upload(route, params, parts)
                }
            } finally {
                parts.forEach { it.file.delete() }
            }
            remote.reload()
        }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { images
            ->
            if (images.isNotEmpty()) compose(images.take(9), replyTarget)
        }
    fun reply(post: JSONObject) {
        val replies = post.objects("replies")
        val actions = replies.map { entry ->
            ActionItem("${entry.optString("nickname")} · ${entry.optString("content")}", "chat") {
                val actions =
                    mutableListOf(
                        ActionItem("查看作者", "person") {
                            nav.detail(false, entry.optLong("sender_uid"))
                        }
                    )
                val pictures = entry.optJSONArray("img_conts")
                if (pictures != null)
                    for (index in 0 until pictures.length()) {
                        val url = pictures.optString(index)
                        actions +=
                            ActionItem("查看图片 ${index+1}", "image") {
                                nav.image(repo.media.resolve(url))
                            }
                    }
                if (entry.optLong("sender_uid") == repo.account.uid)
                    actions +=
                        ActionItem("删除评论", "delete", true) {
                            ui.confirm("删除评论", "删除这条评论？", true) {
                                repo.execute(
                                    "space/delete",
                                    json("cont_id" to entry.optLong("cont_id")),
                                )
                                remote.reload()
                            }
                        }
                ui.actions(entry.optString("content"), actions)
            }
        }
        ui.actions(
            "评论",
            listOf(
                ActionItem("写评论", "add") { compose(emptyList(), post.optLong("cont_id")) },
                ActionItem("图片评论", "image") {
                    replyTarget = post.optLong("cont_id")
                    picker.launch(arrayOf("image/*"))
                },
            ) + actions,
        )
    }
    Page(
        "好友动态",
        nav::back,
        actions = {
            RapidIconButton("refresh", "刷新") { remote.reload() }
            RapidIconButton("add", "发布动态") {
                ui.actions(
                    "发布动态",
                    listOf(
                        ActionItem("文字", "chat") { compose(emptyList()) },
                        ActionItem("图片与文字", "image") {
                            replyTarget = 0
                            picker.launch(arrayOf("image/*"))
                        },
                    ),
                )
            }
        },
    ) { padding ->
        PageList(padding) {
            item { TextField(query, { query = it }, label = "搜索已加载的动态", singleLine = true) }
            item { RemoteStatus(remote) }
            if (newActivity)
                item { Button({ remote.reload() }, Modifier.fillMaxWidth()) { Text("有新的动态，点此刷新") } }
            if (!remote.loading && remote.error.isEmpty() && values.isEmpty())
                item { EmptyContent("暂无动态", "发布近况，与好友分享", icon = "discover") }
            items(
                values.filter {
                    query.isBlank() ||
                        JSONObject(it).let { post ->
                            post.optString("content").contains(query, true) ||
                                post.optString("nickname").contains(query, true)
                        }
                },
                key = { JSONObject(it).optLong("cont_id") },
                contentType = { "post" },
            ) { raw ->
                val post = remember(raw) { JSONObject(raw) }
                var liking by remember { mutableStateOf(false) }
                Card(insideMargin = PaddingValues(18.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconButton(
                            { nav.detail(false, post.optLong("sender_uid")) },
                            minWidth = 44.dp,
                            minHeight = 44.dp,
                        ) {
                            Avatar(
                                post.optString("nickname"),
                                repo.avatarUrl(post.optString("avatar")),
                                44,
                            )
                        }
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(post.optString("nickname"), fontWeight = FontWeight.Medium)
                            Text(
                                post.optString("created_at"),
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        RapidIconButton("more", "动态选项") {
                            val actions =
                                mutableListOf(
                                    ActionItem("查看作者", "person") {
                                        nav.detail(false, post.optLong("sender_uid"))
                                    }
                                )
                            if (post.optLong("sender_uid") == repo.account.uid)
                                actions +=
                                    ActionItem("删除动态", "delete", true) {
                                        ui.confirm("删除动态", "删除这条动态及其评论？", true) {
                                            repo.execute(
                                                "space/delete",
                                                json("cont_id" to post.optLong("cont_id")),
                                            )
                                            values = values.filter {
                                                JSONObject(it).optLong("cont_id") !=
                                                    post.optLong("cont_id")
                                            }
                                        }
                                    }
                            ui.actions("动态选项", actions)
                        }
                    }
                    if (post.optString("content").isNotBlank())
                        Text(
                            post.optString("content"),
                            Modifier.padding(top = 16.dp),
                            fontSize = 17.sp,
                        )
                    val pictures =
                        remember(raw) {
                            val array =
                                post.optJSONArray("img_conts")
                                    ?: runCatching { JSONArray(post.optString("img_conts")) }
                                        .getOrNull()
                            if (array == null) emptyList()
                            else (0 until array.length()).map { array.optString(it) }
                        }
                    if (pictures.isNotEmpty())
                        Column(
                            Modifier.padding(top = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            pictures.chunked(3).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    row.forEach { url ->
                                        Card(
                                            Modifier.weight(1f).aspectRatio(1f),
                                            cornerRadius = 12.dp,
                                            onClick = { nav.image(repo.media.resolve(url)) },
                                        ) {
                                            AsyncImage(
                                                repo.media.resolve(url),
                                                "动态图片",
                                                Modifier.fillMaxSize(),
                                                contentScale =
                                                    androidx.compose.ui.layout.ContentScale.Crop,
                                            )
                                        }
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            {
                                if (!liking) {
                                    liking = true
                                    ui.launch {
                                        try {
                                            val result =
                                                repo.execute(
                                                    "space/toggle_like",
                                                    json("cont_id" to post.optLong("cont_id")),
                                                )
                                            values = values.map { item ->
                                                val value = JSONObject(item)
                                                if (
                                                    value.optLong("cont_id") ==
                                                        post.optLong("cont_id")
                                                )
                                                    value
                                                        .put("is_liked", result.flag("is_liked"))
                                                        .put(
                                                            "likes_num",
                                                            result.optInt("likes_num"),
                                                        )
                                                        .toString()
                                                else item
                                            }
                                        } finally {
                                            liking = false
                                        }
                                    }
                                }
                            },
                            Modifier.weight(1f),
                            enabled = !liking,
                        ) {
                            Text(
                                "${if (post.flag("is_liked")) "已赞" else "赞"} ${post.optInt("likes_num")}",
                                color =
                                    if (post.flag("is_liked")) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurface,
                            )
                        }
                        Button({ reply(post) }, Modifier.weight(1f)) {
                            Text("评论 ${post.objects("replies").size}")
                        }
                    }
                }
            }
            if (more && values.isNotEmpty())
                item {
                    Button(
                        {
                            if (!loadingMore) {
                                loadingMore = true
                                ui.launch {
                                    try {
                                        val next = page + 1
                                        val response =
                                            repo.execute(
                                                "space/get_list",
                                                json("page" to next, "page_size" to 20),
                                            )
                                        values =
                                            (values +
                                                    response.objects("list").map { it.toString() })
                                                .distinctBy { JSONObject(it).optLong("cont_id") }
                                        page = next
                                        more = values.size < response.optInt("total")
                                    } finally {
                                        loadingMore = false
                                    }
                                }
                            }
                        },
                        Modifier.fillMaxWidth(),
                        enabled = !loadingMore,
                    ) {
                        Text(if (loadingMore) "加载中…" else "加载更多")
                    }
                }
        }
    }
}
