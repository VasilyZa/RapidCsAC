package com.xiaobai.rapidcsac.core.network

import java.io.File
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

data class UploadPart(val field: String, val file: File, val name: String, val mime: String)

class HttpApi(
    val origin: String,
    private val token: () -> String,
    private val client: OkHttpClient,
) {
    private val base = origin.toHttpUrl()

    fun resolve(path: String): String = base.resolve(path.trimStart('/'))?.toString().orEmpty()

    internal fun request(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("User-Agent", "CsAC-Android/0.1").apply {
            if (
                url.scheme == base.scheme &&
                    url.host == base.host &&
                    url.port == base.port &&
                    token().isNotEmpty()
            ) {
                header("Authorization", "Bearer ${token()}")
            }
        }

    suspend fun getRoute(route: String, adminToken: String = ""): JSONObject =
        jsonRequest("/rpc/UniCsAC.php?route=$route", null, adminToken)

    suspend fun postRoute(route: String, body: JSONObject): JSONObject =
        postJson("/rpc/UniCsAC.php?route=$route", body)

    suspend fun getJson(path: String): JSONObject = jsonRequest(path, null)

    suspend fun postJson(path: String, body: JSONObject): JSONObject = jsonRequest(path, body)

    private suspend fun jsonRequest(
        path: String,
        body: JSONObject?,
        adminToken: String = "",
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val url = base.resolve(path) ?: throw IOException("无效地址")
            val builder = request(url)
            if (adminToken.isNotEmpty()) {
                require(url.scheme == base.scheme && url.host == base.host && url.port == base.port)
                builder.header("X-CsAC-Admin-Token", adminToken)
            }
            if (body != null)
                builder.post(
                    body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                )
            client.newCall(builder.build()).awaitResponse().use { response ->
                val value = runCatching {
                    JSONObject(response.body?.string().orEmpty())
                }
                    .getOrElse {
                        throw ApiFailure(response.code, "请求失败（${response.code}）")
                    }
                if (!response.isSuccessful)
                    throw ApiFailure(
                        response.code,
                        value.optString("message", "请求失败（${response.code}）"),
                    )
                if (value.has("success") && !value.optBoolean("success")) {
                    throw ApiFailure(-32000, value.optString("message", "操作失败"))
                }
                value
            }
        }

    suspend fun upload(route: String, fields: JSONObject, files: List<UploadPart>): JSONObject =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            fields.keys().forEach { key -> body.addFormDataPart(key, fields.optString(key)) }
            files.forEach { part ->
                body.addFormDataPart(
                    part.field,
                    part.name,
                    part.file.asRequestBody(part.mime.toMediaType()),
                )
            }
            val url =
                base
                    .newBuilder()
                    .addPathSegments("rpc/UniCsAC.php")
                    .addQueryParameter("route", route)
                    .build()
            client.newCall(request(url).post(body.build()).build()).awaitResponse().use { response
                ->
                val result =
                    try {
                        JSONObject(response.body?.string().orEmpty())
                    } catch (_: Exception) {
                        JSONObject()
                    }
                if (!response.isSuccessful || !result.optBoolean("success")) {
                    throw ApiFailure(response.code, result.optString("message", "上传失败，请检查连接"))
                }
                result
            }
        }

    suspend fun download(url: String, target: File, maxBytes: Long = 32L * 1024 * 1024) =
        withContext(Dispatchers.IO) {
            val address = base.resolve(url) ?: throw IOException("无效下载地址")
            require(address.isHttps) { "下载地址必须使用 HTTPS" }
            client.newCall(request(address).build()).awaitResponse().use { response ->
                if (!response.isSuccessful) throw IOException("下载失败（${response.code}）")
                val body = response.body ?: throw IOException("文件为空")
                if (body.contentLength() > maxBytes) throw IOException("文件过大")
                try {
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val size = input.read(buffer)
                                if (size < 0) break
                                total += size
                                if (total > maxBytes) throw IOException("文件过大")
                                output.write(buffer, 0, size)
                            }
                        }
                    }
                } catch (error: Exception) {
                    target.delete()
                    throw error
                }
            }
        }
}

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        }
    )
}
