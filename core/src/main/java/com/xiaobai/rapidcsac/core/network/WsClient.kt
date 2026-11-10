package com.xiaobai.rapidcsac.core.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
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

data class WsHandshake(val status: Int, val subprotocol: String)

/** One socket per account. Business state and reconnect policy belong to the repository. */
class WsClient(http: OkHttpClient, private val origin: String) {
    private val http = http.newBuilder().pingInterval(15, TimeUnit.SECONDS).build()
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
    @Volatile
    var handshake: WsHandshake? = null
        private set

    suspend fun connect(token: String): JSONObject = mutex.withLock {
        val opened = CompletableDeferred<JSONObject>()
        val request =
            Request.Builder()
                .url(origin.replaceFirst("https://", "wss://") + "/ws/v2")
                .header("User-Agent", "CsAC-Android/0.1")
                .header("Sec-WebSocket-Protocol", "csac.v2")
                .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
                .build()
        val epoch =
            synchronized(this) {
                if (state.value == ConnectionState.CONNECTED) return@withLock hello ?: JSONObject()
                disconnect()
                mutableState.value = ConnectionState.CONNECTING
                ready = opened
                generation.incrementAndGet()
            }
        var connectingSocket: WebSocket? = null
        try {
            val created =
                http.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            synchronized(this@WsClient) {
                                if (generation.get() != epoch) return
                                handshake =
                                    WsHandshake(
                                        response.code,
                                        response.header("Sec-WebSocket-Protocol").orEmpty(),
                                    )
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (generation.get() != epoch) return
                            try {
                                val frame = JSONObject(text)
                                synchronized(this@WsClient) {
                                    if (
                                        generation.get() != epoch ||
                                            state.value == ConnectionState.DISCONNECTED ||
                                            state.value == ConnectionState.EXPIRED
                                    )
                                        return
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
                                                !incoming
                                                    .trySend(frame.getJSONObject("params"))
                                                    .isSuccess
                                            ) {
                                                webSocket.cancel()
                                                failed(epoch, ConnectionLost("同步队列已满，正在重新连接"))
                                            }
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
                            failed(epoch, ConnectionLost(cause = t), response?.code == 401)
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
            connectingSocket = created
            val installed =
                synchronized(this) {
                    if (generation.get() != epoch) false
                    else {
                        socket = created
                        true
                    }
                }
            if (!installed) {
                created.cancel()
                currentCoroutineContext().ensureActive()
                throw ConnectionLost()
            }
            // A connection timeout must remain recoverable while caller cancellation propagates.
            withTimeoutOrNull(15_000) { opened.await() } ?: throw ConnectionLost("连接超时")
        } catch (error: Exception) {
            synchronized(this) {
                if (generation.get() == epoch) {
                    connectingSocket?.cancel()
                    if (state.value != ConnectionState.EXPIRED) failed(epoch, ConnectionLost())
                }
            }
            throw error
        }
    }

    suspend fun call(
        method: String,
        params: JSONObject = JSONObject(),
        key: String? = null,
    ): JSONObject = slots.withPermit {
        val id = sequence.incrementAndGet().toString()
        val deferred = CompletableDeferred<JSONObject>()
        val request = json("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params)
        if (key != null) request.put("idempotency_key", key)
        val (epoch, activeSocket) =
            synchronized(this) {
                val current = socket
                if (state.value != ConnectionState.CONNECTED || current == null)
                    throw ConnectionLost()
                pending[id] = deferred
                generation.get() to current
            }
        try {
            if (!activeSocket.send(request.toString())) throw ConnectionLost()
            val reply =
                withTimeoutOrNull(35_000) { deferred.await() } ?: throw ConnectionLost("连接超时")
            reply.optJSONObject("error")?.let {
                throw ApiFailure(
                    it.optInt("code"),
                    it.optString("message", "操作失败"),
                    it.optJSONObject("data"),
                )
            }
            reply.optJSONObject("result") ?: JSONObject()
        } catch (failure: ConnectionLost) {
            failed(epoch, failure)
            activeSocket.cancel()
            throw failure
        } finally {
            pending.remove(id)
        }
    }

    @Synchronized
    fun disconnect() {
        generation.incrementAndGet()
        socket?.close(1000, null)
        socket = null
        hello = null
        handshake = null
        ready?.completeExceptionally(ConnectionLost())
        ready = null
        pending.values.forEach { it.completeExceptionally(ConnectionLost()) }
        pending.clear()
        mutableState.value = ConnectionState.DISCONNECTED
    }

    @Synchronized
    private fun failed(epoch: Long, failure: Exception, expired: Boolean = false) {
        if (generation.get() != epoch) return
        mutableState.value =
            if (expired || state.value == ConnectionState.EXPIRED) ConnectionState.EXPIRED
            else ConnectionState.DISCONNECTED
        ready?.completeExceptionally(failure)
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
    }
}
