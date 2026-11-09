package com.xiaobai.rapidcsac.screens

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.network.*
import com.xiaobai.rapidcsac.platform.MiniAppPackage
import com.xiaobai.rapidcsac.ui.*
import java.io.ByteArrayInputStream
import top.yukonga.miuix.kmp.basic.*

@Composable
fun MiniAppsScreen(repo: ChatRepository, ui: UiController, nav: Navigation) {
    val remote =
        rememberRemote(repo) {
            repo.media.getJson("/emapps/public/catalog").let {
                it.objects("data").ifEmpty { it.objects("apps") }
            }
        }
    Page("小程序", nav::back, actions = { RapidIconButton("refresh", "刷新") { remote.reload() } }) {
        padding ->
        PageList(padding) {
            item { RemoteStatus(remote) }
            if (!remote.loading && remote.error.isEmpty() && remote.value.isNullOrEmpty())
                item { EmptyContent("暂无小程序", "上架的小程序会显示在这里", icon = "file") }
            items(remote.value.orEmpty(), key = { it.optString("app_id") }) { app ->
                Card {
                    MenuRow(
                        app.optString("name"),
                        app.firstText("description", "desc", "version"),
                        "file",
                    ) {
                        nav.miniApp(app.optString("app_id"))
                    }
                }
            }
        }
    }
}

@Composable
fun MiniAppScreen(repo: ChatRepository, ui: UiController, nav: Navigation, id: String) {
    val context = LocalContext.current
    val remote =
        rememberRemote(repo, id) { MiniAppPackage(context, repo.media, repo.account.key).open(id) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    val pack = remote.value
    DisposableEffect(pack) {
        onDispose { pack?.root?.parentFile?.deleteRecursively() }
    }
    BackHandler(canGoBack) { web?.goBack() }
    Page(
        pack?.title ?: "小程序",
        { if (canGoBack) web?.goBack() else nav.back() },
        actions = { RapidIconButton("close", "关闭小程序", onClick = nav::back) },
    ) { padding ->
        if (pack == null)
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) { RemoteStatus(remote) }
        else
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(padding),
                factory = { ctx ->
                    WebView(ctx).apply {
                        web = this
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            javaScriptCanOpenWindowsAutomatically = false
                            mediaPlaybackRequiresUserGesture = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            setGeolocationEnabled(false)
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient =
                            object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    canGoBack = view.canGoBack()
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): WebResourceResponse? {
                                    if (request.url.host != pack.host) return null
                                    return try {
                                        require(
                                            request.url.scheme == "https" &&
                                                request.url.port in setOf(-1, 443)
                                        )
                                        val path =
                                            Uri.decode(request.url.encodedPath.orEmpty())
                                                .trimStart('/')
                                                .ifEmpty { pack.entry }
                                        val file = MiniAppPackage.safeFile(pack.root, path)
                                        require(file.isFile)
                                        val mime =
                                            when (file.extension.lowercase()) {
                                                "js",
                                                "mjs" -> "application/javascript"
                                                "html" -> "text/html"
                                                "css" -> "text/css"
                                                else ->
                                                    MimeTypeMap.getSingleton()
                                                        .getMimeTypeFromExtension(
                                                            file.extension.lowercase()
                                                        ) ?: "application/octet-stream"
                                            }
                                        WebResourceResponse(
                                            mime,
                                            "UTF-8",
                                            200,
                                            "OK",
                                            mapOf("X-Content-Type-Options" to "nosniff"),
                                            file.inputStream(),
                                        )
                                    } catch (_: Exception) {
                                        WebResourceResponse(
                                            "text/plain",
                                            "UTF-8",
                                            404,
                                            "Not Found",
                                            emptyMap(),
                                            ByteArrayInputStream(byteArrayOf()),
                                        )
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    if (
                                        request.url.scheme == "https" &&
                                            request.url.host == pack.host &&
                                            request.url.port in setOf(-1, 443)
                                    )
                                        return false
                                    if (
                                        request.isForMainFrame &&
                                            request.hasGesture() &&
                                            request.url.scheme == "https"
                                    )
                                        ui.confirm("打开外部链接", request.url.toString()) {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, request.url)
                                            )
                                        }
                                    return true
                                }
                            }
                        loadUrl("https://${pack.host}/${pack.entry}")
                    }
                },
                onRelease = { browser ->
                    browser.stopLoading()
                    (browser.parent as? ViewGroup)?.removeView(browser)
                    browser.removeAllViews()
                    browser.destroy()
                    if (web === browser) web = null
                },
            )
    }
}
