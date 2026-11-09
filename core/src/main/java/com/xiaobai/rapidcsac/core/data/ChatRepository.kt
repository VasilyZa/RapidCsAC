package com.xiaobai.rapidcsac.core.data

import android.content.Context
import com.xiaobai.rapidcsac.core.model.Conversation
import com.xiaobai.rapidcsac.core.model.Message
import com.xiaobai.rapidcsac.core.model.PendingMessage
import com.xiaobai.rapidcsac.core.network.*
import com.xiaobai.rapidcsac.core.security.Account
import com.xiaobai.rapidcsac.core.storage.ChatStore
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONObject

/** Owns synchronization for one account. UI code never decides replay or retry policy. */
class ChatRepository(context: Context, val account: Account, httpClient: OkHttpClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ws = WsClient(httpClient, account.origin)
    val media = HttpApi(account.origin, { account.token }, httpClient)
    val moderation = ModerationRepository(media)
    private val store = ChatStore(context, account.key)
    private val syncLock = Mutex()
    private val sendLock = Mutex()
    private val ready = MutableStateFlow(false)
    private var connectionJob: Job? = null
    private var methods = emptyMap<String, Boolean>()
    private val mutableConversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations = mutableConversations.asStateFlow()
    private val version = MutableStateFlow(0L)
    val changes = version.asStateFlow()
    private val mutableUpdates = MutableSharedFlow<Set<String>>(extraBufferCapacity = 16)
    val updates = mutableUpdates.asSharedFlow()
    val connection = ws.state
    private val mutableUser = MutableStateFlow(JSONObject())
    val user = mutableUser.asStateFlow()
    private val mutableError = MutableStateFlow("")
    val error = mutableError.asStateFlow()
    val transientEvents = MutableSharedFlow<JSONObject>(extraBufferCapacity = 16)
    private val mutableNeedsEmail = MutableStateFlow(false)
    val needsEmail = mutableNeedsEmail.asStateFlow()
    @Volatile var activeConversation: String? = null
    var onNewMessage: ((Conversation) -> Unit)? = null
    var onExpired: (() -> Unit)? = null

    init {
        scope.launch {
            store.meta("user")?.let { mutableUser.value = JSONObject(it) }
            publishLocal()
        }
        scope.launch {
            ws.events.collect { event ->
                if (event.optBoolean("transient")) {
                    transientEvents.emit(event)
                    return@collect
                }
                ready.filter { it }.first()
                try {
                    syncLock.withLock {
                        val sequence = event.optString("seq").toLongOrNull() ?: return@withLock
                        if (sequence <= (store.meta("cursor")?.toLongOrNull() ?: 0L))
                            return@withLock
                        applyEvents(listOf(event))
                        store.setMeta("cursor", sequence.toString())
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    ws.disconnect() // Reconnect replays from the last successfully applied cursor.
                }
            }
        }
    }

    @Synchronized
    fun start() {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    syncLock.withLock {
                        ready.value = false
                        val hello = ws.connect(account.token)
                        val catalog = ws.call("rpc.discover")
                        methods =
                            catalog.objects("methods").associate {
                                it.getString("name") to it.optBoolean("idempotency_key")
                            }
                        synchronize(hello.getString("cursor"))
                        ready.value = true
                    }
                    mutableError.value = ""
                    scope.launch { moderation.probe() }
                    attempt = 0
                    flushPending()
                    ws.state.first {
                        it == ConnectionState.DISCONNECTED || it == ConnectionState.EXPIRED
                    }
                    if (ws.state.value == ConnectionState.EXPIRED) {
                        onExpired?.invoke()
                        break
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    mutableError.value = failure.message ?: "暂时无法连接"
                    if (ws.state.value == ConnectionState.EXPIRED) {
                        onExpired?.invoke()
                        break
                    }
                }
                ready.value = false
                ws.disconnect()
                val wait = minOf(30_000L, 1000L shl minOf(attempt++, 5))
                delay(wait + Random.nextLong(0, 500))
            }
        }
    }

    fun pause() {
        connectionJob?.cancel()
        connectionJob = null
        ready.value = false
        ws.disconnect()
    }

    suspend fun shutdown() {
        pause()
        scope.coroutineContext.job.cancelAndJoin()
        withContext(Dispatchers.IO) { store.close() }
    }

    suspend fun awaitReady() {
        ready.filter { it }.first()
    }

    suspend fun execute(
        method: String,
        params: JSONObject = JSONObject(),
        key: String? = null,
    ): JSONObject {
        if (!ready.value) throw ConnectionLost("正在连接，请稍后重试")
        val retryKey = if (methods[method] == true) key ?: UUID.randomUUID().toString() else null
        return ws.call(method, params, retryKey).optJSONObject("data") ?: JSONObject()
    }

    suspend fun refresh() =
        withContext(Dispatchers.IO) {
            syncLock.withLock {
                refreshInbox()
                publishLocal()
            }
        }

    private suspend fun synchronize(initialCursor: String) {
        var cursor = store.meta("cursor") ?: initialCursor
        try {
            mutableUser.value = ws.call("user/get_info").getJSONObject("data").getJSONObject("user")
            mutableNeedsEmail.value = false
        } catch (failure: ApiFailure) {
            if (
                failure.details?.optJSONObject("body")?.optBoolean("needs_email_verification") !=
                    true
            )
                throw failure
            mutableNeedsEmail.value = true
            return
        }
        store.setMeta("user", mutableUser.value.toString())
        refreshInbox()
        activeConversation?.let {
            loadMessagesInternal(it)
            markRead(it)
        }
        repeat(500) {
            val page =
                try {
                    ws.call("events.resume", json("after" to cursor, "limit" to 200))
                } catch (failure: ApiFailure) {
                    if (failure.code != -32009) throw failure
                    cursor = failure.details?.getString("cursor") ?: throw failure
                    store.clearMessages()
                    refreshInbox()
                    activeConversation?.let {
                        loadMessagesInternal(it)
                        markRead(it)
                    }
                    ws.call("events.resume", json("after" to cursor, "limit" to 200))
                }
            applyEvents(page.objects("events"), notify = false)
            cursor = page.getString("next_cursor")
            store.setMeta("cursor", cursor)
            if (!page.optBoolean("has_more")) {
                ws.call("events.ack", json("seq" to cursor))
                publishLocal()
                return
            }
        }
        throw ConnectionLost("历史同步较多，正在继续同步")
    }

    private suspend fun refreshInbox() {
        val friends = ws.call("user/get_friends").getJSONObject("data").objects("friends")
        val groups = ws.call("user/get_groups").getJSONObject("data").objects("groups")
        val values =
            friends.map { Conversation.parse(it, false) } +
                groups.map { Conversation.parse(it, true) }
        store.replaceConversations(values)
        val hidden =
            ws.call("user/get_hide_conv_list").getJSONObject("data").optJSONArray("hide_conv_list")
        val hiddenIds = buildSet {
            if (hidden != null) for (index in 0 until hidden.length()) add(hidden.optString(index))
        }
        values
            .filter { it.group }
            .forEach { store.preference(it.key, "hidden", it.id.toString() in hiddenIds) }
        if (
            activeConversation != null && values.none { it.key == activeConversation && !it.banned }
        )
            activeConversation = null
    }

    private suspend fun applyEvents(events: List<JSONObject>, notify: Boolean = true) {
        if (events.isEmpty()) return
        var inboxChanged = false
        val refreshChats = linkedMapOf<String, Long>()
        for (event in events) {
            val data = event.optJSONObject("data") ?: JSONObject()
            val type = event.optString("type")
            when {
                type.startsWith("message.") -> {
                    val key =
                        if (data.optLong("room_id") > 0) "g:${data.optLong("room_id")}"
                        else {
                            val other =
                                if (data.optLong("from_uid") == account.uid) data.optLong("to_uid")
                                else data.optLong("from_uid")
                            "p:$other"
                        }
                    if (type == "message.updated" && data.has("is_read"))
                        store.updateRead(key, data.optLong("msg_id"), data.flag("is_read"))
                    if (type == "message.deleted") store.removeMessage(key, data.optLong("msg_id"))
                    else if (key == activeConversation)
                        refreshChats[key] =
                            if (type == "message.updated") data.optLong("msg_id") else 0
                    if (type == "message.created") {
                        store.preview(key, "收到新消息", event.optLong("server_time"))
                        val sender = data.firstLong("uid", "from_uid")
                        if (notify && sender != account.uid)
                            conversations.value
                                .firstOrNull {
                                    it.key == key && !it.muted && key != activeConversation
                                }
                                ?.let { onNewMessage?.invoke(it) }
                    }
                    inboxChanged = true
                }
                type == "user.updated" || type == "profile.updated" -> {
                    val value = ws.call("user/get_info").getJSONObject("data").getJSONObject("user")
                    mutableUser.value = value
                    store.setMeta("user", value.toString())
                    inboxChanged = true
                }
                type.startsWith("friend.") ||
                    type.startsWith("group.") ||
                    type == "profile.updated" ||
                    type == "conversation.read" -> inboxChanged = true
            }
        }
        if (inboxChanged) refreshInbox()
        for ((key, target) in refreshChats) {
            if (store.pending().none { it.conversation == key && it.state == "sending" }) {
                loadMessagesInternal(key, target = target)
                markRead(key)
            }
        }
        publishLocal()
        mutableUpdates.emit(events.map { it.optString("type") }.toSet())
    }

    fun messages(key: String, limit: Int = 80, before: Long = Long.MAX_VALUE): Flow<List<Message>> =
        changes
            .map {
                withContext(Dispatchers.IO) {
                    val pending =
                        store
                            .pending()
                            .filter { it.conversation == key }
                            .map { item ->
                                val data = JSONObject(item.params)
                                Message(
                                    -item.created,
                                    key,
                                    account.uid,
                                    account.name,
                                    data.optString("content"),
                                    1,
                                    item.created / 1000,
                                    reply = data.optLong("reply_to"),
                                    clientKey = item.key,
                                    delivery = item.state,
                                    raw = item.params,
                                )
                            }
                    store.messages(key, limit, before) +
                        (if (before == Long.MAX_VALUE) pending else emptyList())
                }
            }
            .distinctUntilChanged()

    suspend fun openConversation(key: String) =
        withContext(Dispatchers.IO) {
            activeConversation =
                if (conversations.value.any { it.key == key && it.banned }) null else key
            store.preference(key, "marked_unread", false)
            publishLocal()
            if (ready.value && activeConversation == key)
                syncLock.withLock {
                    loadMessagesInternal(key)
                    markRead(key)
                    refreshInbox()
                    publishLocal()
                }
        }

    suspend fun jumpToMessage(key: String, target: Long) =
        withContext(Dispatchers.IO) {
            if (ready.value)
                syncLock.withLock {
                    loadMessagesInternal(key, target = target)
                    publishLocal()
                }
        }

    suspend fun loadOlder(key: String, before: Long) =
        withContext(Dispatchers.IO) {
            if (!ready.value) throw ConnectionLost()
            syncLock.withLock {
                val more = loadMessagesInternal(key, before = before)
                publishLocal()
                more
            }
        }

    private suspend fun loadMessagesInternal(
        key: String,
        before: Long = 0,
        target: Long = 0,
    ): Boolean {
        val group = key.startsWith("g:")
        val params =
            json(
                (if (group) "room_id" else "friend_id") to key.substringAfter(':').toLong(),
                "limit" to 80,
            )
        if (before > 0) params.put("before_id", before)
        if (target > 0) params.put("before_id", target + 1)
        val value =
            ws.call(if (group) "message/get_group_msg" else "message/get_private_msg", params)
                .getJSONObject("data")
        val messages = value.objects("messages").map { Message.parse(it, key) }
        store.putMessages(key, messages)
        if (before == 0L && target == 0L)
            store.messages(key, 1).lastOrNull().let {
                store.preview(key, it?.preview.orEmpty(), it?.time ?: 0L)
            }
        return value.optBoolean("has_more", messages.size == 80)
    }

    private suspend fun markRead(key: String) {
        val params =
            json(
                (if (key.startsWith("g:")) "room_id" else "friend_id") to
                    key.substringAfter(':').toLong(),
                "last_msg_id" to store.latest(key),
            )
        ws.call("message/mark_read", params, UUID.randomUUID().toString())
    }

    suspend fun send(key: String, text: String, reply: Long = 0, mentions: String = "") =
        withContext(Dispatchers.IO) {
            val group = key.startsWith("g:")
            val params =
                json(
                    (if (group) "room_id" else "friend_id") to key.substringAfter(':').toLong(),
                    "content" to text,
                    "reply_to" to reply,
                    "mention_uids" to mentions,
                )
            store.enqueue(
                PendingMessage(
                    UUID.randomUUID().toString(),
                    key,
                    if (group) "message/send_group_msg" else "message/send_private_msg",
                    params.toString(),
                    System.currentTimeMillis(),
                    "queued",
                    "",
                )
            )
            store.draft(key, "")
            publishLocal()
            scope.launch { flushPending() }
        }

    suspend fun retry(key: String) =
        withContext(Dispatchers.IO) {
            store.pendingState(key, "queued")
            publishLocal()
            flushPending()
        }

    suspend fun discard(key: String) =
        withContext(Dispatchers.IO) {
            store.complete(key)
            publishLocal()
        }

    private suspend fun flushPending() = sendLock.withLock {
        if (!ready.value) return@withLock
        for (pending in store.pending()) {
            if (pending.state == "failed") continue
            var accepted = false
            try {
                if (pending.state == "uncertain" || pending.state == "sending") {
                    val status = ws.call("requests.status", json("idempotency_key" to pending.key))
                    if (status.optString("state") == "pending_or_interrupted") {
                        store.pendingState(pending.key, "uncertain", "发送状态待确认，请同步会话")
                        continue
                    }
                    if (status.optString("state") == "complete") {
                        val previous = status.getJSONObject("outcome")
                        if (previous.has("error")) {
                            store.pendingState(
                                pending.key,
                                "failed",
                                previous.getJSONObject("error").optString("message"),
                            )
                            continue
                        }
                        accepted = true
                        syncLock.withLock {
                            if (pending.conversation == activeConversation)
                                loadMessagesInternal(pending.conversation)
                            store.complete(pending.key)
                        }
                        continue
                    }
                }
                store.pendingState(pending.key, "sending")
                publishLocal()
                ws.call(pending.method, JSONObject(pending.params), pending.key)
                accepted = true
                syncLock.withLock {
                    if (pending.conversation == activeConversation)
                        loadMessagesInternal(pending.conversation)
                    store.complete(pending.key)
                }
            } catch (failure: ApiFailure) {
                store.pendingState(
                    pending.key,
                    if (accepted || failure.code == -32011) "uncertain" else "failed",
                    failure.message,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                store.pendingState(pending.key, "uncertain", "连接中断，重连后自动确认")
                break
            } finally {
                publishLocal()
            }
        }
    }

    suspend fun setPreference(key: String, field: String, value: Boolean) =
        withContext(Dispatchers.IO) {
            store.preference(key, field, value)
            publishLocal()
        }

    suspend fun saveDraft(key: String, text: String) =
        withContext(Dispatchers.IO) { store.draft(key, text) }

    suspend fun search(
        query: String,
        conversation: String = "",
        sender: Long = 0,
        after: Long = 0,
        before: Long = Long.MAX_VALUE,
        type: String = "all",
    ): List<Message> =
        withContext(Dispatchers.IO) {
            store.search(query, conversation, sender, after, before, type)
        }

    suspend fun deleteLocally(key: String, ids: List<Long>) =
        withContext(Dispatchers.IO) {
            store.deleteLocally(key, ids)
            store.messages(key, 1).lastOrNull().let {
                store.preview(key, it?.preview.orEmpty(), it?.time ?: 0L)
            }
            publishLocal()
        }

    suspend fun clearConversation(key: String) =
        withContext(Dispatchers.IO) {
            store.clearConversation(key, store.latest(key))
            publishLocal()
        }

    suspend fun markConversationRead(key: String) =
        withContext(Dispatchers.IO) {
            if (!ready.value) throw ConnectionLost()
            syncLock.withLock {
                markRead(key)
                store.preference(key, "marked_unread", false)
                refreshInbox()
                publishLocal()
            }
        }

    suspend fun toggleHidden(key: String) {
        val conversation = conversations.value.firstOrNull { it.key == key } ?: return
        if (conversation.group) execute("user/toggle_hide_conv", json("room_id" to conversation.id))
        setPreference(key, "hidden", !conversation.hidden)
    }

    suspend fun cachedMessages(
        key: String,
        limit: Int = 10_000,
        before: Long = Long.MAX_VALUE,
    ): List<Message> = withContext(Dispatchers.IO) { store.messages(key, limit, before) }

    fun exportMessages(key: String): Flow<List<Message>> = flow {
        val through = store.latest(key)
        var after = 0L
        while (after < through) {
            val page = store.messageBatch(key, after, through)
            if (page.isEmpty()) break
            emit(page)
            after = page.last().id
        }
    }
        .flowOn(Dispatchers.IO)

    suspend fun statistics(): Pair<Long, Long> = withContext(Dispatchers.IO) { store.statistics() }

    suspend fun clearCache() =
        withContext(Dispatchers.IO) {
            store.clearMessages()
            publishLocal()
        }

    suspend fun typing(key: String, active: Boolean) {
        if (ready.value)
            ws.call(
                "typing.set",
                json(
                    "conversation_type" to if (key.startsWith("g:")) "group" else "private",
                    "conversation_id" to key.substringAfter(':').toLong(),
                    "active" to active,
                ),
            )
    }

    private fun publishLocal() {
        mutableConversations.value = store.conversations()
        version.update { it + 1 }
    }
}
