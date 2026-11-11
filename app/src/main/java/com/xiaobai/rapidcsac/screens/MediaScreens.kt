package com.xiaobai.rapidcsac.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.platform.*
import com.xiaobai.rapidcsac.platform.shareText
import com.xiaobai.rapidcsac.ui.*
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*

@Composable
fun QrScreen(repo: ChatRepository, ui: UiController, nav: Navigation, initialValue: String) {
    val context = LocalContext.current
    val value = initialValue.ifEmpty { "rapidcsac://user/${repo.account.uid}" }
    val qr =
        rememberRemote(value) {
            withContext(Dispatchers.Default) {
                val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 600, 600)
                val pixels =
                    IntArray(600 * 600) { index ->
                        if (matrix[index % 600, index / 600]) android.graphics.Color.BLACK
                        else android.graphics.Color.WHITE
                    }
                Bitmap.createBitmap(pixels, 600, 600, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
        }
    val historyPrefs =
        remember(repo) {
            context.getSharedPreferences(
                "qr-${repo.account.uid}-${repo.account.origin.hashCode()}",
                android.content.Context.MODE_PRIVATE,
            )
        }
    fun showResult(content: String) {
        val previous = runCatching {
            org.json.JSONArray(historyPrefs.getString("history", "[]"))
        }
            .getOrDefault(org.json.JSONArray())
        val entries =
            listOf(content) +
                List(previous.length()) { previous.optString(it) }.filter { it != content }
        historyPrefs
            .edit()
            .putString("history", org.json.JSONArray(entries.take(20)).toString())
            .apply()
        ui.confirm("二维码内容", content.take(2000)) {
            if (!nav.link(content)) {
                copyText(context, content)
                ui.inform("已复制二维码内容")
            }
        }
    }
    val scanner =
        rememberLauncherForActivityResult(ScanContract()) { result ->
            result.contents?.let(::showResult)
        }
    val gallery =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) ui.launch { showResult(decodeQr(context, uri)) }
        }
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri
            ->
            if (uri != null)
                qr.value?.let { bitmap ->
                    ui.launch {
                        saveQr(context, bitmap.asAndroidBitmap(), uri)
                        ui.inform("二维码已保存")
                    }
                }
        }
    Page(
        "二维码",
        nav::back,
        actions = {
            AnchoredMenu(
                "二维码选项",
                listOf(
                    ActionItem("从相册识别", "image") { gallery.launch(arrayOf("image/*")) },
                    ActionItem("保存二维码", "download") {
                        if (qr.value != null) save.launch("RapidCsAC-QR.png")
                    },
                    ActionItem("扫描记录", "file") {
                        val entries = runCatching {
                            org.json.JSONArray(historyPrefs.getString("history", "[]"))
                        }
                            .getOrDefault(org.json.JSONArray())
                        ui.actions(
                            "扫描记录",
                            List(entries.length()) { index ->
                                val entry = entries.optString(index)
                                ActionItem(entry.take(80), "scan") { showResult(entry) }
                            } +
                                ActionItem("清空记录", "delete", true) {
                                    ui.confirm("清空扫描记录", "清空本机保存的扫描记录？", true) {
                                        historyPrefs.edit().remove("history").apply()
                                    }
                                },
                        )
                    },
                ),
            )
        },
    ) { padding ->
        PageList(padding) {
            item { RemoteStatus(qr) }
            item {
                Card(insideMargin = PaddingValues(24.dp)) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Text(if (initialValue.isEmpty()) "我的名片" else "分享名片")
                        qr.value?.let {
                            Image(
                                it,
                                "二维码名片",
                                Modifier.widthIn(max = 280.dp)
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .background(Color.White)
                                    .padding(12.dp),
                            )
                        }
                        Text(value)
                    }
                }
            }
            item {
                PrimaryButton("扫一扫", Modifier.fillMaxWidth()) {
                    scanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("将二维码放入框内")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                    )
                }
            }
            item { Button({ shareText(context, value) }, Modifier.fillMaxWidth()) { Text("分享链接") } }
        }
    }
}

@Composable
fun ImageScreen(
    url: String,
    nav: Navigation,
    repo: ChatRepository,
    ui: UiController,
    tag: String = "",
) {
    val context = LocalContext.current
    val imageName =
        android.net.Uri.parse(url).lastPathSegment?.take(120)?.ifBlank { null } ?: "image"
    val imageMime =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(imageName.substringAfterLast('.').lowercase())
            ?: "application/octet-stream"
    val saver =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(imageMime)) { uri
            ->
            if (uri != null)
                ui.launch {
                    saveAttachment(context, repo, url, uri)
                    ui.inform("图片已保存")
                }
        }
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    var imageSize by remember { mutableStateOf(Size.Zero) }
    val scope = rememberCoroutineScope()
    var zoomJob by remember { mutableStateOf<Job?>(null) }
    val reduced = LocalReducedMotion.current
    fun reset() {
        zoomJob?.cancel()
        scale = 1f
        offset = Offset.Zero
    }
    Page(
        "图片",
        nav::back,
        actions = {
            AnchoredMenu(
                "图片选项",
                listOf(
                    ActionItem("还原缩放", "refresh", run = ::reset),
                    ActionItem("保存图片", "download") { saver.launch(imageName) },
                    ActionItem("分享图片", "share") {
                        ui.launch { openAttachment(context, repo, url, imageName, share = true) }
                    },
                    ActionItem("复制图片链接", "copy") {
                        copyText(context, url)
                        ui.inform("链接已复制")
                    },
                ),
            )
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(padding).background(Color.Black).clipToBounds()
        ) {
            val density = LocalDensity.current
            val width = with(density) { maxWidth.toPx() }
            val height = with(density) { maxHeight.toPx() }
            val fit =
                if (imageSize.width > 0 && imageSize.height > 0)
                    minOf(width / imageSize.width, height / imageSize.height)
                else 1f
            fun bounded(value: Offset, zoom: Float): Offset {
                val x = max(0f, (imageSize.width * fit * zoom - width) / 2)
                val y = max(0f, (imageSize.height * fit * zoom - height) / 2)
                return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
            }
            fun zoom(point: Offset) {
                zoomJob?.cancel()
                val target = if (scale > 1.2f) 1f else 2.5f
                val starting = scale
                val origin = offset
                val nextOffset =
                    if (target == 1f) Offset.Zero
                    else bounded((Offset(width / 2, height / 2) - point) * (target - 1f), target)
                if (reduced) {
                    scale = target
                    offset = nextOffset
                } else
                    zoomJob = scope.launch {
                        AnimationState(0f).animateTo(
                            1f,
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        ) {
                            scale = starting + (target - starting) * value
                            offset = bounded(origin + (nextOffset - origin) * value, scale)
                        }
                    }
            }
            AsyncImage(
                rememberMediaRequest(url),
                "图片，可双指缩放或双击放大",
                Modifier.sharedMedia(tag)
                    .fillMaxSize()
                    .pointerInput(url, width, height) {
                        detectTransformGestures { _, pan, factor, _ ->
                            zoomJob?.cancel()
                            scale = (scale * factor).coerceIn(1f, 5f)
                            offset = bounded(offset + pan, scale)
                        }
                    }
                    .pointerInput(url, width, height, imageSize) {
                        detectTapGestures(onDoubleTap = { zoom(it) })
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                onSuccess = { imageSize = it.painter.intrinsicSize },
            )
            Button(
                { zoom(Offset(width / 2, height / 2)) },
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp),
            ) {
                Text(if (scale > 1.2f) "还原" else "放大")
            }
        }
    }
}
