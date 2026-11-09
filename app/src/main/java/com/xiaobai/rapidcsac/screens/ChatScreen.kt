package com.xiaobai.rapidcsac.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaobai.rapidcsac.AppGraph
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.model.Message
import com.xiaobai.rapidcsac.core.network.*
import com.xiaobai.rapidcsac.platform.*
import com.xiaobai.rapidcsac.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ChatScreen(
    graph: AppGraph,
    repo: ChatRepository,
    ui: UiController,
    nav: Navigation,
    key: String,
    initialName: String,
    target: Long,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val group = key.startsWith("g:")
    val id = remember(key) { key.substringAfter(':').toLong() }
    val conversations by repo.conversations.collectAsStateWithLifecycle()
    val conversation = conversations.firstOrNull { it.key == key }
    val name = conversation?.name ?: initialName
    val banned = conversation?.banned == true
    var text by
        rememberSaveable(key) {
            mutableStateOf(repo.conversations.value.firstOrNull { it.key == key }?.draft.orEmpty())
        }
    var reply by remember { mutableStateOf<Message?>(null) }
    var selected by remember(key) { mutableStateOf<Set<Long>>(emptySet()) }
    fun toggleSelection(message: Message) {
        if (message.id <= 0) return
        selected = if (message.id in selected) selected - message.id else selected + message.id
    }
    BackHandler(selected.isNotEmpty()) { selected = emptySet() }
    val initialUnread =
        remember(key) { repo.conversations.value.firstOrNull { it.key == key }?.unread ?: 0 }
    var unreadBoundary by remember(key) { mutableLongStateOf(0L) }
    var mentions by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var picker by remember { mutableIntStateOf(1) }
    var pickerShown by remember { mutableStateOf(false) }
    var notice by remember(key) { mutableStateOf("") }
    LaunchedEffect(repo, key) {
        if (!group) return@LaunchedEffect
        repo.updates
            .onStart { emit(setOf("group.initial")) }
            .filter { types -> types.any { it.startsWith("group.") } }
            .collectLatest {
                try {
                    withTimeout(15_000) {
                        repo.awaitReady()
                        notice =
                            repo
                                .execute("group/get_group_view_info", json("room_id" to id))
                                .getJSONObject("room")
                                .optString("notice")
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    notice = ""
                }
            }
    }
    var limit by rememberSaveable(key) { mutableIntStateOf(80) }
    var before by rememberSaveable(key) { mutableLongStateOf(Long.MAX_VALUE) }
    var loadingOlder by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(true) }
    var typing by remember { mutableStateOf(false) }
    val media = remember(repo, key) { ChatMedia(context.applicationContext, repo, key) }
    val connection by repo.connection.collectAsStateWithLifecycle()
    val messageFlow = remember(repo, key, limit, before) { repo.messages(key, limit, before) }
    val messages by messageFlow.collectAsStateWithLifecycle(emptyList())
    val reversed = remember(messages) { messages.asReversed() }
    LaunchedEffect(messages.isNotEmpty()) {
        if (unreadBoundary == 0L && initialUnread > 0)
            unreadBoundary =
                messages
                    .filter { it.id > 0 && it.sender != repo.account.uid }
                    .takeLast(initialUnread)
                    .firstOrNull()
                    ?.id ?: 0L
    }
    val list = rememberLazyListState()
    val nearLatest by remember { derivedStateOf { list.firstVisibleItemIndex < 3 } }
    var jumpLatest by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    val currentText by rememberUpdatedState(text)
    val reduce = LocalReducedMotion.current
    val toolbarOffset = with(LocalDensity.current) { if (reduce) 0 else 6.dp.roundToPx() }
    val recordingOffset = with(LocalDensity.current) { if (reduce) 0 else 8.dp.roundToPx() }

    fun jump(messageId: Long) = ui.launch {
        val index = reversed.indexOfFirst { it.id == messageId }
        if (index >= 0) list.animateScrollToItem(index)
        else {
            repo.jumpToMessage(key, messageId)
            before = messageId + 1
            limit = 80
            more = true
            jumpLatest = true
            list.scrollToItem(0)
        }
    }
    fun send() {
        if (sending || text.isBlank() || banned) return
        sending = true
        val value = text
        val quote = reply?.id ?: 0
        val tagged = mentions
        scope.launch {
            try {
                repo.send(key, value, quote, tagged)
                if (text == value) text = "" else repo.saveDraft(key, text)
                reply = null
                mentions = ""
                if (before != Long.MAX_VALUE) {
                    before = Long.MAX_VALUE
                    limit = 80
                    more = true
                }
                jumpLatest = false
                list.scrollToItem(0)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                ui.inform(failure.message ?: "发送失败")
            } finally {
                sending = false
            }
        }
    }
    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                ui.launch {
                    media.upload(uri, true, reply?.id ?: 0)
                    reply = null
                }
        }
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                ui.launch {
                    media.upload(uri, false, reply?.id ?: 0)
                    reply = null
                }
        }
    val microphone =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            if (allowed) media.startRecording() else ui.inform("允许麦克风权限后才能录音")
        }
    fun record() {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
            media.startRecording()
        else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun attachments() {
        val actions =
            mutableListOf(
                ActionItem("图片", "image") { imagePicker.launch(arrayOf("image/*")) },
                ActionItem("文件", "file") { filePicker.launch(arrayOf("*/*")) },
                ActionItem("语音", "mic", run = ::record),
                ActionItem("表情", "heart") {
                    picker = 1
                    pickerShown = true
                },
            )
        if (group)
            actions +=
                ActionItem("提及群成员", "people") {
                    picker = 2
                    pickerShown = true
                }
        ui.actions("添加到消息", actions)
    }
    ChatPicker(
        repo,
        ui,
        pickerShown,
        picker == 2,
        id,
        dismiss = { pickerShown = false },
        onEmoji = { abbr ->
            repo.execute(
                "message/send_emoji_msg",
                json((if (group) "room_id" else "friend_id") to id, "abbr" to abbr),
            )
            if (repo.activeConversation == key) repo.openConversation(key)
        },
        onMembers = { members ->
            mentions =
                (mentions.split(',') + members.map { it.firstLong("uid", "id").toString() })
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .joinToString(",")
            text += members.joinToString(" ", postfix = " ") { "@${it.optString("nickname")}" }
        },
    )
    fun loadOlder() {
        if (loadingOlder || !more || banned) return
        val first = messages.firstOrNull { it.id > 0 }?.id ?: return
        loadingOlder = true
        ui.launch {
            try {
                more = repo.loadOlder(key, first)
                if (limit >= 480) {
                    before = first + 1
                    limit = 160
                    jumpLatest = true
                } else limit += 80
            } finally {
                loadingOlder = false
            }
        }
    }
    val currentLoadOlder by rememberUpdatedState(::loadOlder)
    LaunchedEffect(list) {
        snapshotFlow {
            list.isScrollInProgress &&
                (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >=
                    list.layoutInfo.totalItemsCount - 3
        }
            .distinctUntilChanged()
            .collect { if (it) currentLoadOlder() }
    }
    DisposableEffect(repo, key, lifecycle) {
        var resumedOnce = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (resumedOnce) ui.launch { repo.openConversation(key) }
                resumedOnce = true
            }
            if (event == Lifecycle.Event.ON_PAUSE) {
                if (repo.activeConversation == key) repo.activeConversation = null
                media.pause()
                graph.scope.launch { runCatching { repo.saveDraft(key, currentText) } }
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            if (repo.activeConversation == key) repo.activeConversation = null
            graph.scope.launch { runCatching { repo.saveDraft(key, currentText) } }
            media.close()
        }
    }
    LaunchedEffect(repo, key, banned) {
        try {
            if (banned) media.pause()
            repo.openConversation(key)
            if (target > 0 && !banned) {
                repo.jumpToMessage(key, target)
                before = target + 1
                jumpLatest = true
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            ui.inform(failure.message ?: "暂时无法同步会话")
        }
    }
    LaunchedEffect(text) {
        delay(350)
        repo.saveDraft(key, text)
    }
    LaunchedEffect(repo, key) {
        var last = 0L
        snapshotFlow { text }
            .collectLatest { value ->
                if (value.isNotBlank() && System.currentTimeMillis() - last > 2500) {
                    last = System.currentTimeMillis()
                    runCatching { repo.typing(key, true) }
                }
                delay(4500)
                runCatching { repo.typing(key, false) }
            }
    }
    LaunchedEffect(repo, key) {
        repo.transientEvents
            .filter { event ->
                val data = event.optJSONObject("data")
                event.optString("type") == "typing.changed" &&
                    data != null &&
                    if (group)
                        data.optString("conversation_type") == "group" &&
                            data.optLong("conversation_id") == id
                    else
                        data.optString("conversation_type") == "private" &&
                            data.optLong("uid") == id &&
                            data.optLong("conversation_id") == repo.account.uid
            }
            .collectLatest { event ->
                typing = event.getJSONObject("data").optBoolean("active")
                delay(5000)
                typing = false
            }
    }
    LaunchedEffect(reversed.firstOrNull()?.id) {
        if (reversed.isNotEmpty() && before == Long.MAX_VALUE) {
            if (nearLatest) list.scrollToItem(0) else jumpLatest = true
        }
    }
    var recordSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(media.recordingSince) {
        while (media.recordingSince > 0) {
            recordSeconds = ((System.currentTimeMillis() - media.recordingSince) / 1000).toInt()
            delay(1000)
        }
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column {
                SmallTopAppBar(
                    name,
                    navigationIcon = { RapidIconButton("back", "返回", onClick = nav::back) },
                    actions = {
                        AnchoredMenu("聊天选项", chatActions(repo, ui, nav, key, name, context))
                    },
                )
                AnimatedVisibility(
                    selected.isNotEmpty(),
                    enter =
                        fadeIn(tween(180, easing = RapidMotion.easeOut)) +
                            slideInVertically(tween(180, easing = RapidMotion.easeOut)) {
                                toolbarOffset
                            },
                    exit =
                        fadeOut(tween(140, easing = RapidMotion.easeOut)) +
                            slideOutVertically(tween(140, easing = RapidMotion.easeOut)) {
                                toolbarOffset
                            },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RapidIconButton("close", "退出多选") { selected = emptySet() }
                        Text("已选 ${selected.size}", Modifier.weight(1f))
                        RapidIconButton("copy", "复制所选消息") {
                            copyText(
                                context,
                                messages
                                    .filter { it.id in selected }
                                    .joinToString("\n") { it.preview },
                            )
                            selected = emptySet()
                        }
                        RapidIconButton("share", "转发所选消息") {
                            forwardMessages(
                                messages.filter { it.id in selected },
                                repo,
                                ui,
                                context,
                            ) {
                                selected = emptySet()
                            }
                        }
                        RapidIconButton("delete", "删除所选消息") {
                            val ids = selected.toList()
                            ui.confirm("从本机删除", "删除 ${ids.size} 条消息的本机记录？", true) {
                                repo.deleteLocally(key, ids)
                                selected = emptySet()
                            }
                        }
                    }
                }
                if (notice.isNotBlank() && selected.isEmpty())
                    BasicComponent(
                        insideMargin = PaddingValues(horizontal = 24.dp, vertical = 6.dp),
                        onClick = { ui.textViewer = notice },
                    ) {
                        Text(
                            "群公告 · $notice",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                val status =
                    when {
                        banned ->
                            "群组已被封禁${conversation?.banReason?.let {if(it.isNotBlank()) " · $it" else ""}.orEmpty()}"
                        media.recordingSince > 0 -> "录音中 · ${recordSeconds} 秒"
                        media.uploading -> "正在上传…"
                        media.playing > 0 -> "正在播放语音"
                        typing -> "正在输入…"
                        connection != ConnectionState.CONNECTED -> "离线 · 文字消息将在连接后发送"
                        else -> ""
                    }
                if (status.isNotEmpty())
                    Text(
                        status,
                        Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                if (jumpLatest || before != Long.MAX_VALUE)
                    Button(
                        {
                            before = Long.MAX_VALUE
                            limit = 80
                            more = true
                            jumpLatest = false
                            ui.launch {
                                repo.openConversation(key)
                                list.scrollToItem(0)
                            }
                        },
                        Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                    ) {
                        Text("回到最新消息")
                    }
                if (media.error.isNotEmpty())
                    Text(
                        media.error,
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        color = MiuixTheme.colorScheme.error,
                    )
                AnimatedVisibility(
                    media.recordingSince > 0 || media.voice != null,
                    enter =
                        fadeIn(tween(180, easing = RapidMotion.easeOut)) +
                            slideInVertically(tween(180, easing = RapidMotion.easeOut)) {
                                recordingOffset
                            },
                    exit =
                        fadeOut(tween(140, easing = RapidMotion.easeOut)) +
                            slideOutVertically(tween(140, easing = RapidMotion.easeOut)) {
                                recordingOffset
                            },
                ) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        if (media.recordingSince > 0) RecordingMeter(media)
                        else if (media.voice != null) VoiceMessage(null, media)
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (media.recordingSince > 0) "录音中 ${recordSeconds} 秒"
                                else "语音 ${media.voice?.seconds?:recordSeconds} 秒",
                                Modifier.weight(1f),
                            )
                            RapidIconButton("close", "取消录音", !media.uploading) {
                                if (media.recordingSince > 0) media.finishRecording(false)
                                else media.discardVoice()
                            }
                            if (media.recordingSince > 0)
                                Button({ media.finishRecording(true) }) { Text("完成") }
                            else
                                PrimaryButton("发送", enabled = !media.uploading && !banned) {
                                    ui.launch { media.sendVoice() }
                                }
                        }
                    }
                }
                if (media.recordingSince == 0L && media.voice == null) {
                    AnimatedVisibility(
                        reply != null,
                        enter =
                            if (reduce) fadeIn(tween(100))
                            else
                                fadeIn(tween(RapidMotion.feedbackMillis)) +
                                    slideInVertically(
                                        tween(
                                            RapidMotion.feedbackMillis,
                                            easing = RapidMotion.easeOut,
                                        )
                                    ) {
                                        it / 4
                                    },
                        exit = fadeOut(tween(100)),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "回复 ${reply?.name.orEmpty()}",
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                                Text(
                                    reply?.preview.orEmpty(),
                                    fontSize = 14.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            RapidIconButton("close", "取消引用") { reply = null }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        RapidIconButton("add", "附件与表情", !media.uploading && !banned, ::attachments)
                        TextField(
                            text,
                            { text = it },
                            modifier =
                                Modifier.weight(1f).onPreviewKeyEvent { event ->
                                    if (
                                        graph.preferences.getBoolean("enter_send", false) &&
                                            event.key == Key.Enter &&
                                            event.type == KeyEventType.KeyDown &&
                                            !event.isShiftPressed
                                    ) {
                                        send()
                                        true
                                    } else false
                                },
                            label = if (banned) "群组已被封禁" else "消息",
                            enabled = !banned,
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                        )
                        if (text.isNotBlank())
                            RapidIconButton("send", "发送消息", !sending && !banned, ::send)
                        else RapidIconButton("mic", "录制语音", !media.uploading && !banned, ::record)
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            state = list,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
        ) {
            items(
                reversed,
                key = { if (it.clientKey.isEmpty()) "message:${it.id}" else it.clientKey },
                contentType = { "message" },
            ) { message ->
                MessageItem(
                    message,
                    message.sender == repo.account.uid,
                    group,
                    repo::avatarUrl,
                    open = {
                        when {
                            message.clientKey.isNotEmpty() ->
                                messageMenu(
                                    message,
                                    repo,
                                    ui,
                                    nav,
                                    key,
                                    context,
                                    select = ::toggleSelection,
                                ) {
                                    reply = it
                                }
                            message.image.isNotEmpty() ->
                                nav.image(
                                    repo.media.resolve(message.image),
                                    "${repo.account.key}:$key:${message.id}",
                                )
                            message.type == 3 -> media.play(message)
                            message.type == 6 && message.file.isNotEmpty() ->
                                ui.launch {
                                    openAttachment(context, repo, message.file, message.fileName)
                                }
                            else ->
                                messageMenu(
                                    message,
                                    repo,
                                    ui,
                                    nav,
                                    key,
                                    context,
                                    select = ::toggleSelection,
                                ) {
                                    reply = it
                                }
                        }
                    },
                    menu = {
                        messageMenu(
                            message,
                            repo,
                            ui,
                            nav,
                            key,
                            context,
                            select = ::toggleSelection,
                        ) {
                            reply = it
                        }
                    },
                    reply = { reply = message },
                    jump = { jump(it) },
                    sender = { nav.detail(false, message.sender) },
                    selectionMode = selected.isNotEmpty(),
                    selected = message.id in selected,
                    select = { toggleSelection(message) },
                    voiceContent = { VoiceMessage(message, media) },
                    mediaTag = "${repo.account.key}:$key:${message.id}",
                )
                if (message.id == unreadBoundary)
                    Text(
                        "以下是未读消息",
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MiuixTheme.colorScheme.primary,
                    )
            }
            if (messages.isEmpty())
                item {
                    EmptyContent("开始聊天", "发送第一条消息", modifier = Modifier.padding(vertical = 32.dp))
                }
            if (messages.count { it.id > 0 } >= 80 && more)
                item {
                    Button(
                        { loadOlder() },
                        Modifier.fillMaxWidth(),
                        enabled = !loadingOlder && !banned,
                    ) {
                        Text(if (loadingOlder) "加载中…" else "加载更早的消息")
                    }
                }
        }
    }
}
