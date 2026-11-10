package com.xiaobai.rapidcsac.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns only application-private copies. Selected originals are read through the system picker. */
class ChatWallpaperStore(private val context: Context, private val preferences: SharedPreferences) {
    private val directory = File(context.filesDir, "chat-wallpapers")
    private val mutex = Mutex()
    private val mutableImage = MutableStateFlow(read())
    val image = mutableImage.asStateFlow()

    suspend fun replace(uri: Uri) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val part = MediaFiles(context).prepare(uri, "wallpaper", true)
                val target = File(directory, "${UUID.randomUUID()}.jpg")
                try {
                    check(directory.isDirectory || directory.mkdirs()) { "无法创建壁纸目录" }
                    part.file.copyTo(target)
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) { save(target) }
                } catch (failure: Exception) {
                    if (mutableImage.value != target) target.delete()
                    throw failure
                } finally {
                    part.file.delete()
                }
            }
        }

    suspend fun clear() =
        withContext(Dispatchers.IO) {
            mutex.withLock { withContext(NonCancellable) { save(null) } }
        }

    private fun read(): File? {
        val name = preferences.getString("chat_wallpaper", "").orEmpty()
        if (!name.matches(Regex("[a-fA-F0-9-]{36}\\.jpg"))) return null
        return File(directory, name).takeIf { it.isFile }
    }

    @SuppressLint("UseKtx") // Check the durable write result before replacing the private file.
    private fun save(target: File?) {
        val previous = preferences.getString("chat_wallpaper", "").orEmpty()
        if (!preferences.edit().putString("chat_wallpaper", target?.name.orEmpty()).commit()) {
            preferences.edit().putString("chat_wallpaper", previous).apply()
            throw IOException("壁纸设置未能保存，请重试")
        }
        mutableImage.value = target
        directory
            .listFiles()
            ?.filter {
                it != target && it.name.matches(Regex("[a-fA-F0-9-]{36}\\.jpg"))
            }
            ?.forEach { it.delete() }
    }
}
