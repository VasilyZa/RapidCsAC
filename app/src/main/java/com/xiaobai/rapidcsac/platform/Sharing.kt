package com.xiaobai.rapidcsac.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.xiaobai.rapidcsac.core.data.ChatRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun copyText(context: Context, text: String) {
    context
        .getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("RapidCsAC", text))
}

fun shareText(context: Context, text: String) {
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            "分享",
        )
    )
}

suspend fun openAttachment(
    context: Context,
    repo: ChatRepository,
    path: String,
    name: String,
    share: Boolean = false,
) {
    val directory = File(context.cacheDir, "exports/${UUID.randomUUID()}").apply { mkdirs() }
    val safeName =
        name.substringAfterLast('/').substringAfterLast('\\').take(120).takeUnless {
            it in setOf("", ".", "..")
        } ?: "attachment"
    val file = File(directory, safeName)
    repo.media.download(path, file)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val mime =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    val intent =
        if (share)
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).apply {
                clipData = ClipData.newRawUri("RapidCsAC", uri)
            }
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
    context.startActivity(
        Intent.createChooser(
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            if (share) "分享文件" else "打开文件",
        )
    )
}

suspend fun exportChat(context: Context, repo: ChatRepository, key: String) {
    val file = File(context.cacheDir, "exports/RapidCsAC-${UUID.randomUUID()}.txt")
    withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { output ->
            repo.exportMessages(key).collect { messages ->
                messages.forEach {
                    output.appendLine(
                        "${it.name} · ${java.util.Date(it.time * 1000)}\n${it.preview}\n"
                    )
                }
            }
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "导出本机聊天记录",
        )
    )
}

suspend fun saveAttachment(
    context: Context,
    repo: ChatRepository,
    path: String,
    destination: android.net.Uri,
) {
    val file = File(context.cacheDir, "save-${UUID.randomUUID()}")
    try {
        repo.media.download(path, file)
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(destination)?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            } ?: error("无法写入所选位置")
        }
    } finally {
        file.delete()
    }
}
