package com.xiaobai.rapidcsac.core.model

import com.xiaobai.rapidcsac.core.network.firstLong
import com.xiaobai.rapidcsac.core.network.firstText
import com.xiaobai.rapidcsac.core.network.flag
import com.xiaobai.rapidcsac.core.network.json
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

data class Conversation(
    val key: String,
    val id: Long,
    val group: Boolean,
    val name: String,
    val avatar: String,
    val subtitle: String,
    val unread: Int,
    val updated: Long,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val archived: Boolean = false,
    val draft: String = "",
    val raw: String = "{}",
    val favorite: Boolean = false,
    val hidden: Boolean = false,
    val markedUnread: Boolean = false,
    val lastMessage: Message? = null,
    val banned: Boolean = false,
    val banReason: String = "",
) {
    val params: JSONObject
        get() = json((if (group) "room_id" else "friend_id") to id)

    companion object {
        fun parse(value: JSONObject, group: Boolean): Conversation {
            val id =
                if (group) value.firstLong("room_id", "id")
                else value.firstLong("uid", "friend_id", "id")
            val name =
                if (group) value.firstText("room_name", "name")
                else value.firstText("display_name", "remark", "nickname", "username")
            return Conversation(
                (if (group) "g:" else "p:") + id,
                id,
                group,
                name.ifEmpty { if (group) "群组 $id" else "用户 $id" },
                value.firstText("avatar"),
                value.firstText("last_message", "intro", "online_status"),
                value.optInt("unread_count"),
                timestamp(value.firstText("last_time", "last_msg_time")),
                raw = value.toString(),
                banned = value.flag("is_banned"),
                banReason = value.firstText("ban_reason"),
            )
        }
    }
}

data class Message(
    val id: Long,
    val conversation: String,
    val sender: Long,
    val name: String,
    val text: String,
    val type: Int,
    val time: Long,
    val avatar: String = "",
    val image: String = "",
    val voice: String = "",
    val file: String = "",
    val fileName: String = "",
    val duration: Int = 0,
    val reply: Long = 0,
    val replyName: String = "",
    val replyText: String = "",
    val read: Boolean = false,
    val recalled: Boolean = false,
    val essence: Boolean = false,
    val canRecall: Boolean = false,
    val clientKey: String = "",
    val delivery: String = "",
    val raw: String = "{}",
) {
    val preview: String
        get() =
            when {
                recalled -> "消息已撤回"
                type == 2 -> "[图片]"
                type == 3 -> "[语音]"
                type == 5 -> "[表情]"
                type == 6 -> "[文件] $fileName"
                else -> text
            }

    companion object {
        fun parse(value: JSONObject, conversation: String): Message {
            val type = value.optInt("msg_type", 1)
            val content = value.firstText("content")
            val metadata =
                value.optJSONObject("file_meta")
                    ?: runCatching { JSONObject(value.optString("file_meta")) }.getOrNull()
            return Message(
                value.firstLong("msg_id", "id"),
                conversation,
                value.firstLong("from_uid", "uid"),
                value.firstText("nickname", "sender_name"),
                content,
                type,
                timestamp(value.firstText("created_at", "add_time", "time")),
                value.firstText("avatar"),
                when (type) {
                    2 -> value.firstText("image_url", "image").ifEmpty { content }
                    5 -> value.firstText("emoji_address", "address", "image_url")
                    else -> ""
                },
                value.firstText("voice_url").ifEmpty { if (type == 3) content else "" },
                metadata?.firstText("public_url", "file_path").orEmpty(),
                metadata?.firstText("file_name").orEmpty(),
                value.firstLong("duration", "voice_duration").toInt(),
                value.optLong("reply_to"),
                value.firstText("reply_nickname"),
                value.firstText("reply_content"),
                value.flag("is_read"),
                value.flag("is_recalled"),
                value.flag("is_essence"),
                value.flag("can_recall"),
                raw = value.toString(),
            )
        }
    }
}

data class PendingMessage(
    val key: String,
    val conversation: String,
    val method: String,
    val params: String,
    val created: Long,
    val state: String,
    val error: String,
)

fun timestamp(value: String): Long {
    value.toLongOrNull()?.let {
        return if (it > 10_000_000_000L) it / 1000 else it
    }
    for (pattern in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd")) {
        val parsed = runCatching {
            SimpleDateFormat(pattern, Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(value)
        }
            .getOrNull()
        if (parsed != null) return parsed.time / 1000
    }
    return 0
}
