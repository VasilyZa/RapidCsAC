package com.xiaobai.rapidcsac.core.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okio.Buffer

enum class DiagnosticKind(val title: String, val group: String) {
    SITE("站点连通与耗时", "HTTPS 与资源"),
    API("HTTP API 可用性", "HTTPS 与资源"),
    LOGIN("HTTP 登录状态", "HTTPS 与资源"),
    IMAGE("图片资源访问", "HTTPS 与资源"),
    WS_CONNECT("握手与协议协商", "WebSocket v2"),
    WS_PING("心跳与往返延迟", "WebSocket v2"),
    WS_CATALOG("接口发现", "WebSocket v2"),
    WS_LOGIN("会话认证与业务请求", "WebSocket v2"),
}

enum class DiagnosticStatus(val label: String) {
    PENDING("等待"),
    RUNNING("检测中"),
    PASSED("通过"),
    FAILED("异常"),
    SKIPPED("跳过"),
    CANCELLED("已停止"),
}

data class DiagnosticCheck(
    val kind: DiagnosticKind,
    val status: DiagnosticStatus = DiagnosticStatus.PENDING,
    val elapsedMs: Long? = null,
    val detail: String = "",
    val suggestion: String = "",
)

data class DiagnosticReport(
    val startedAt: Long = System.currentTimeMillis(),
    val totalMs: Long = 0,
    val checks: List<DiagnosticCheck> = DiagnosticKind.entries.map { DiagnosticCheck(it) },
    val finished: Boolean = false,
    val cancelled: Boolean = false,
) {
    val completed: Int
        get() = checks.count {
            it.status == DiagnosticStatus.PASSED ||
                it.status == DiagnosticStatus.FAILED ||
                it.status == DiagnosticStatus.SKIPPED
        }

    val failures: Int
        get() = checks.count { it.status == DiagnosticStatus.FAILED }

    val passed: Boolean
        get() = finished && !cancelled && completed == checks.size && failures == 0

    fun stopped(reason: String = "用户停止了本轮检测"): DiagnosticReport =
        copy(
            cancelled = true,
            checks =
                checks.map {
                    if (it.status == DiagnosticStatus.RUNNING)
                        it.copy(status = DiagnosticStatus.CANCELLED, detail = reason)
                    else it
                },
        )

    fun asText(origin: String, environment: List<Pair<String, String>>): String {
        val verdict =
            when {
                cancelled -> "已停止"
                !finished -> "检测中"
                passed -> "检测通过"
                else -> "$failures 项异常"
            }
        return buildString {
            appendLine("RapidCsAC 网络诊断")
            appendLine(
                "检测时间  ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.getDefault()).format(Date(startedAt))}"
            )
            appendLine(
                "服务端  ${origin.toHttpUrl().newBuilder().username("").password("").encodedPath("/").query(null).fragment(null).build()}"
            )
            environment.forEach { (name, value) -> appendLine("$name  $value") }
            appendLine("本轮耗时  $totalMs ms")
            appendLine("检测结果  $verdict")
            checks.forEach { check ->
                appendLine()
                appendLine(
                    "[${check.status.label}] ${check.kind.group} · ${check.kind.title}${check.elapsedMs?.let { "  $it ms" }.orEmpty()}"
                )
                if (check.detail.isNotEmpty()) appendLine(check.detail)
                if (check.suggestion.isNotEmpty()) appendLine("建议  ${check.suggestion}")
            }
        }
            .trimEnd()
    }
}

/** Read-only probes use their own socket and connection pool. Credentials never enter results. */
class NetworkDiagnostics(
    private val http: OkHttpClient,
    private val origin: String,
    private val token: String,
    private val expectedUid: Long,
) {
    fun run(latestImage: suspend () -> String?): Flow<DiagnosticReport> = flow {
        val started = System.nanoTime()
        var report = DiagnosticReport()
        suspend fun update(check: DiagnosticCheck) {
            report =
                report.copy(
                    totalMs = elapsed(started),
                    checks = report.checks.map { if (it.kind == check.kind) check else it },
                )
            emit(report)
        }
        suspend fun check(kind: DiagnosticKind, probe: suspend () -> Outcome): Boolean {
            update(DiagnosticCheck(kind, DiagnosticStatus.RUNNING))
            val timer = System.nanoTime()
            val result =
                try {
                    withTimeout(8_000) { probe() }
                } catch (failure: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    failureOutcome(failure)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    failureOutcome(failure)
                }
            update(
                DiagnosticCheck(
                    kind,
                    result.status,
                    elapsed(timer),
                    result.detail,
                    result.suggestion,
                )
            )
            return result.status == DiagnosticStatus.PASSED
        }

        val client =
            http
                .newBuilder()
                .connectionPool(ConnectionPool())
                .cache(null)
                .callTimeout(8, TimeUnit.SECONDS)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .eventListenerFactory {
                    it.request().tag(ConnectionTrace::class.java) ?: EventListener.NONE
                }
                .build()
        val base = origin.toHttpUrl()
        val api = HttpApi(origin, { token }, client)
        val publicApi = HttpApi(origin, { "" }, client)
        val ws = WsClient(client, origin)
        emit(report)
        try {
            check(DiagnosticKind.SITE) {
                val trace = ConnectionTrace()
                val request =
                    publicApi
                        .request(base)
                        .cacheControl(CacheControl.FORCE_NETWORK)
                        .tag(ConnectionTrace::class.java, trace)
                        .build()
                client.newCall(request).awaitResponse().use { response ->
                    verify(response.code in 200..499, "站点返回 HTTP ${response.code}", "检查服务器或反向代理状态")
                    val protocol =
                        if (response.protocol == Protocol.HTTP_2) "HTTP/2"
                        else response.protocol.toString().uppercase(Locale.ROOT)
                    val tls = response.handshake?.tlsVersion?.javaName.orEmpty()
                    Outcome(
                        listOf("HTTP ${response.code} · $protocol · $tls", trace.summary())
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                    )
                }
            }
            check(DiagnosticKind.API) {
                val health = publicApi.getJson("/healthz")
                verify(health.optBoolean("success"), "健康接口返回异常", "检查服务端 RPC 服务及数据库连接")
                Outcome("GET /healthz · API 返回有效 JSON")
            }
            check(DiagnosticKind.LOGIN) {
                val user = api.getRoute("user/get_info").optJSONObject("user")
                verify(
                    user?.optLong("uid") == expectedUid && expectedUid > 0,
                    "返回的账户身份不匹配",
                    "重新登录后再次检测",
                )
                Outcome("当前账户会话有效，身份校验通过")
            }
            check(DiagnosticKind.IMAGE) {
                val image = latestImage()?.takeIf { it.isNotBlank() }
                if (image == null) Outcome("本机尚无缓存图片，可在接收图片后重新检测", DiagnosticStatus.SKIPPED)
                else {
                    val url = base.resolve(image)
                    verify(
                        url != null &&
                            url.isHttps &&
                            url.username.isEmpty() &&
                            url.password.isEmpty(),
                        "图片地址无效或未使用 HTTPS",
                        "检查服务端返回的资源地址",
                    )
                    val request =
                        api.request(url!!)
                            .cacheControl(CacheControl.FORCE_NETWORK)
                            .header("Accept", "image/*")
                            .header("Range", "bytes=0-65535")
                            .build()
                    client.newCall(request).awaitResponse().use { response ->
                        verify(
                            response.isSuccessful,
                            "图片返回 HTTP ${response.code}",
                            "检查资源文件是否存在及访问权限",
                        )
                        val type = response.body?.contentType()
                        verify(type?.type == "image", "资源未返回图片内容", "检查图片域名和反向代理的资源路由")
                        val source = response.body!!.source()
                        val buffer = Buffer()
                        while (
                            buffer.size < 65_536 &&
                                source.read(buffer, minOf(8192, 65_536 - buffer.size)) != -1L
                        ) {
                            currentCoroutineContext().ensureActive()
                        }
                        verify(buffer.size > 0, "图片内容为空", "检查资源文件是否完整")
                        Outcome("${url.host} · $type\n采样接收 ${buffer.size} B，最多 64 KiB")
                    }
                }
            }
            val connected =
                check(DiagnosticKind.WS_CONNECT) {
                    val hello = ws.connect("")
                    verify(
                        ws.handshake?.status == 101 && ws.handshake?.subprotocol == "csac.v2",
                        "WebSocket 子协议协商异常",
                        "检查代理的 Upgrade 转发及 csac.v2 子协议",
                    )
                    verify(
                        hello.optInt("protocol") == 2,
                        "服务器未返回 WS v2 就绪消息",
                        "检查 /ws/v2 是否指向当前 RPC 服务",
                    )
                    Outcome("HTTP 101 · csac.v2 · session.ready\n服务端协议版本 2")
                }
            if (connected) {
                check(DiagnosticKind.WS_PING) {
                    val samples = mutableListOf<Long>()
                    repeat(3) {
                        val timer = System.nanoTime()
                        try {
                            withTimeout(2_000) {
                                verify(
                                    ws.call("ping").optLong("server_time") > 0,
                                    "心跳返回数据异常",
                                    "检查服务端 WS v2 控制消息处理",
                                )
                            }
                            samples.add(elapsed(timer))
                        } catch (failure: TimeoutCancellationException) {
                            currentCoroutineContext().ensureActive()
                        }
                    }
                    val timing =
                        if (samples.isEmpty()) "未收到有效心跳响应"
                        else
                            "平均 ${samples.average().toLong()} ms · 范围 ${samples.min()}–${samples.max()} ms"
                    Outcome(
                        "${samples.size}/3 次心跳成功\n$timing",
                        if (samples.size == 3) DiagnosticStatus.PASSED else DiagnosticStatus.FAILED,
                        if (samples.size == 3) "" else "检查网络稳定性及代理的 WebSocket 超时设置",
                    )
                }
                check(DiagnosticKind.WS_CATALOG) {
                    val catalog = ws.call("rpc.discover")
                    val methods = catalog.objects("methods")
                    verify(
                        catalog.optInt("protocol") == 2 &&
                            methods.any { it.optString("name") == "user/get_info" },
                        "接口目录不完整或版本不匹配",
                        "确认已部署支持 WS v2 的服务端",
                    )
                    Outcome("rpc.discover · ${methods.size} 个业务接口\n用户信息接口已提供")
                }
                check(DiagnosticKind.WS_LOGIN) {
                    val auth = ws.call("session.authenticate", json("token" to token))
                    verify(
                        auth.optLong("uid") == expectedUid && expectedUid > 0,
                        "WebSocket 认证身份不匹配",
                        "重新登录后再次检测",
                    )
                    val user = ws.call("user/get_info").optJSONObject("data")?.optJSONObject("user")
                    verify(
                        user?.optLong("uid") == expectedUid,
                        "WS v2 业务请求返回身份异常",
                        "检查业务接口路由及会话认证配置",
                    )
                    Outcome("session.authenticate · 认证通过\nuser/get_info · 业务请求成功")
                }
            } else {
                listOf(DiagnosticKind.WS_PING, DiagnosticKind.WS_CATALOG, DiagnosticKind.WS_LOGIN)
                    .forEach {
                        update(
                            DiagnosticCheck(
                                it,
                                DiagnosticStatus.SKIPPED,
                                detail = "需要先成功建立 WS v2 连接",
                            )
                        )
                    }
            }
            report = report.copy(totalMs = elapsed(started), finished = true)
            emit(report)
        } finally {
            ws.disconnect()
            client.connectionPool.evictAll()
        }
    }
        .flowOn(Dispatchers.IO)
}

private data class Outcome(
    val detail: String,
    val status: DiagnosticStatus = DiagnosticStatus.PASSED,
    val suggestion: String = "",
)

private class ProbeFailure(val detail: String, val suggestion: String) : Exception()

private fun verify(condition: Boolean, detail: String, suggestion: String) {
    if (!condition) throw ProbeFailure(detail, suggestion)
}

private fun elapsed(start: Long): Long = (System.nanoTime() - start).coerceAtLeast(0) / 1_000_000

private fun failureOutcome(error: Exception): Outcome {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(12).toList()
    val failure = causes.firstOrNull { it is ProbeFailure } as? ProbeFailure
    val detail =
        when {
            failure != null -> failure.detail
            causes.any { it is UnknownHostException } -> "DNS 域名解析失败"
            causes.any { it is SSLPeerUnverifiedException || it is SSLException } -> "TLS 或证书校验失败"
            causes.any { it is InterruptedIOException || it is TimeoutCancellationException } ->
                "请求超时"
            causes.any { it is ConnectException } -> "无法连接到服务器"
            error is ApiFailure -> "API 请求失败（${error.code}）"
            error is ConnectionLost -> "WebSocket 连接已断开"
            error is IOException -> "网络连接异常"
            else -> "返回数据无效或检测未能完成"
        }
    val suggestion =
        when {
            failure != null -> failure.suggestion
            causes.any { it is UnknownHostException } -> "检查网络、DNS 和服务器域名"
            causes.any { it is SSLPeerUnverifiedException || it is SSLException } -> "检查设备时间及服务端证书链"
            error is ApiFailure && error.code in setOf(401, 403, -32001) -> "重新登录，并确认账户仍有访问权限"
            else -> "检查当前网络及服务端状态后重试"
        }
    return Outcome(detail, DiagnosticStatus.FAILED, suggestion)
}

private class ConnectionTrace : EventListener() {
    private var dnsStart = 0L
    private var tlsStart = 0L
    private var dnsMs: Long? = null
    private var tlsMs: Long? = null
    private var addressCount = 0

    override fun dnsStart(call: Call, domainName: String) {
        dnsStart = System.nanoTime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        dnsMs = elapsed(dnsStart)
        addressCount = inetAddressList.size
    }

    override fun secureConnectStart(call: Call) {
        tlsStart = System.nanoTime()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        tlsMs = elapsed(tlsStart)
    }

    fun summary(): String =
        listOfNotNull(
                dnsMs?.let { "DNS $it ms · $addressCount 个地址" },
                tlsMs?.let { "TLS $it ms" },
            )
            .joinToString(" · ")
}
