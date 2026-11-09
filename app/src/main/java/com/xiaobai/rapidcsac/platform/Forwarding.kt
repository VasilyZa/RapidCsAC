package com.xiaobai.rapidcsac.platform

import android.content.Context
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.model.Message
import com.xiaobai.rapidcsac.core.network.UploadPart
import com.xiaobai.rapidcsac.core.network.json
import java.io.File
import java.util.UUID

suspend fun forwardMessage(
    context: Context,
    repo: ChatRepository,
    target: String,
    message: Message,
) {
    require(!message.recalled && message.clientKey.isEmpty()) { "这条消息无法转发" }
    if (message.type == 5) {
        repo.execute(
            "message/send_emoji_msg",
            json(
                (if (target.startsWith("g:")) "room_id" else "friend_id") to
                    target.substringAfter(':').toLong(),
                "abbr" to message.text,
            ),
        )
        return
    }
    if (message.type == 1) {
        repo.send(target, message.text)
        return
    }
    val source =
        when (message.type) {
            2,
            5 -> message.image
            3 -> message.voice
            6 -> message.file
            else -> ""
        }
    require(source.isNotEmpty()) { "此类型无法转发" }
    val group = target.startsWith("g:")
    val params =
        json((if (group) "room_id" else "friend_id") to target.substringAfter(':').toLong())
    val file = File(context.cacheDir, "forward-${UUID.randomUUID()}")
    try {
        repo.media.download(source, file)
        val route =
            when (message.type) {
                3 -> "message/send_voice_msg"
                6 -> "message/send_file_msg"
                else -> if (group) "message/send_group_msg" else "message/send_private_msg"
            }
        val mime =
            when (message.type) {
                3 -> "audio/mp4"
                6 -> "application/octet-stream"
                else -> "image/*"
            }
        val field =
            when (message.type) {
                3 -> "voice"
                6 -> "file"
                else -> "img"
            }
        if (message.type == 3) params.put("duration", message.duration)
        repo.media.upload(
            route,
            params,
            listOf(
                UploadPart(
                    field,
                    file,
                    message.fileName.ifEmpty {
                        "forwarded.${if(message.type==3) "m4a" else "bin"}"
                    },
                    mime,
                )
            ),
        )
    } finally {
        file.delete()
    }
}
