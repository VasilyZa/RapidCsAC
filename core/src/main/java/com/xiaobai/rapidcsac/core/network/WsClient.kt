package com.xiaobai.rapidcsac.core.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    EXPIRED,
}

/** One socket per account. Business state and reconnect policy belong to the repository. */
class WsClient(private val http: OkHttpClient, private val origin: String) {
    private val sequence = AtomicLong()
    private val generation = AtomicLong()
    private val mutex = Mutex()
    private val slots = Semaphore(4)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val incoming = Channel<JSONObject>(256)
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state = mutableState.asStateFlow()
    val events = incoming.receiveAsFlow()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready: CompletableDeferred<JSONObject>? = null
    @Volatile private var hello: JSONObject? = null

    suspend fun connect(token: String): JSONObject = mutex.withLock {
        if (state.value == ConnectionState.CONNECTED) return@withLock hello ?: JSONObject()
        disconnect()
        mutableState.value = ConnectionState.CONNECTING
        val epoch = generation.incrementAndGet()
        val opened = CompletableDeferred<JSONObject>()
        ready = opened
        val request =
            Request.Builder()
                .url(origin.replaceFirst("https://", "wss://") + "/ws/v2")
                .header("User-Agent", "CsAC-Android/0.1")
                .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
                .build()
        socket =
            http.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (generation.get() != epoch) return
                        try {
                            val frame = JSONObject(text)
                            val id = frame.optString("id")
                            if (id.isNotEmpty() && id != "null") {
                                pending.remove(id)?.complete(frame)
                                return
                            }
                            when (frame.optString("method")) {
                                "session.ready" -> {
                                    hello = frame.getJSONObject("params")
                                    mutableState.value = ConnectionState.CONNECTED
                                    opened.complete(hello!!)
                                }
                                "event" -> {
                                    if (
                                        !incoming.trySend(frame.getJSONObject("params")).isSuccess
                                    ) {
                                        webSocket.cancel()
                                        failed(epoch, ConnectionLost("同步队列已满，正在重新连接"))
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            webSocket.cancel()
                            failed(epoch, ConnectionLost("服务器返回了无效数据"))
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        failed(epoch, ConnectionLost(), response?.code == 401)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                        failed(epoch, ConnectionLost(), code == 4401)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        failed(epoch, ConnectionLost(), code == 4401)
                    }
                },
            )
        try {
            withTimeout(15_000) { opened.await() }
        } catch (error: Exception) {
            socket?.cancel()
            if (state.value != ConnectionState.EXPIRED) failed(epoch, ConnectionLost())
            throw error
        }
    }

    suspend fun call(
        method: String,
        params: JSONObject = JSONObject(),
        key: String? = null,
    ): JSONObject = slots.withPermit {
        if (state.value != ConnectionState.CONNECTED) throw ConnectionLost()
        val id = sequence.incrementAndGet().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val request = json("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params)
        if (key != null) request.put("idempotency_key", key)
        try {
            if (socket?.send(request.toString()) != true) throw ConnectionLost()
            val reply = withTimeout(35_000) { deferred.await() }
            reply.optJSONObject("error")?.let {
                throw ApiFailure(
                    it.optInt("code"),
                    it.optString("message", "操作失败"),
                    it.optJSONObject("data"),
                )
            }
            reply.optJSONObject("result") ?: JSONObject()
        } finally {
            pending.remove(id)
        }
    }

    fun disconnect() {
        generation.incrementAndGet()
        socket?.close(1000, null)
        socket = null
        hello = null
        ready?.completeExceptionally(ConnectionLost())
        pending.values.forEach { it.completeExceptionally(ConnectionLost()) }
        pending.clear()
        mutableState.value = ConnectionState.DISCONNECTED
    }

    private fun failed(epoch: Long, failure: Exception, expired: Boolean = false) {
        if (generation.get() != epoch) return
        mutableState.value = if (expired) ConnectionState.EXPIRED else ConnectionState.DISCONNECTED
        ready?.completeExceptionally(failure)
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
    }
}
