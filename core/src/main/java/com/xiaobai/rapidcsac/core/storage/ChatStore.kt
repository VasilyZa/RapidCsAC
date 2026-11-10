package com.xiaobai.rapidcsac.core.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.xiaobai.rapidcsac.core.model.Conversation
import com.xiaobai.rapidcsac.core.model.Message
import com.xiaobai.rapidcsac.core.model.PendingMessage
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Blocking, account-scoped store. Its caller dispatches all operations to an IO thread. */
class ChatStore(context: Context, accountKey: String) :
    SQLiteOpenHelper(
        context,
        "chat-${MessageDigest.getInstance("SHA-256").digest(accountKey.toByteArray()).take(12).joinToString("") { "%02x".format(it) }}.db",
        null,
        2,
    ) {
    private var closed = false

    @Synchronized
    override fun getReadableDatabase(): SQLiteDatabase {
        if (closed) throw CancellationException("Account storage is closed")
        return super.getReadableDatabase()
    }

    @Synchronized
    override fun getWritableDatabase(): SQLiteDatabase {
        if (closed) throw CancellationException("Account storage is closed")
        return super.getWritableDatabase()
    }

    @Synchronized
    override fun close() {
        closed = true
        super.close()
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE conversations (key TEXT PRIMARY KEY, body TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1, updated INTEGER NOT NULL DEFAULT 0, preview TEXT NOT NULL DEFAULT '', pinned INTEGER NOT NULL DEFAULT 0, muted INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0, draft TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL(
            "CREATE TABLE messages (conversation TEXT NOT NULL, id INTEGER NOT NULL, body TEXT NOT NULL, content TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY(conversation,id))"
        )
        db.execSQL("CREATE INDEX messages_time ON messages(created DESC)")
        db.execSQL(
            "CREATE TABLE outbox (key TEXT PRIMARY KEY, conversation TEXT NOT NULL, method TEXT NOT NULL, params TEXT NOT NULL, created INTEGER NOT NULL, state TEXT NOT NULL, error TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        addLocalPreferences(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) addLocalPreferences(db)
    }

    private fun addLocalPreferences(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversations ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversations ADD COLUMN marked_unread INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE deleted_messages (conversation TEXT NOT NULL, id INTEGER NOT NULL, PRIMARY KEY(conversation,id))"
        )
    }

    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM metadata WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun setMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "metadata",
            null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun replaceConversations(values: List<Conversation>) = transaction {
        execSQL("UPDATE conversations SET active=0")
        values.forEach { conversation ->
            val data =
                ContentValues().apply {
                    put("body", conversation.raw)
                    put("active", 1)
                }
            if (update("conversations", data, "key=?", arrayOf(conversation.key)) == 0) {
                data.put("key", conversation.key)
                insertOrThrow("conversations", null, data)
            }
        }
    }

    fun conversations(): List<Conversation> =
        readableDatabase
            .rawQuery(
                "SELECT key,body,updated,preview,pinned,muted,archived,draft,favorite,hidden,marked_unread,(SELECT body FROM messages m WHERE m.conversation=c.key ORDER BY m.id DESC LIMIT 1) FROM conversations c WHERE active=1 ORDER BY pinned DESC,updated DESC,key",
                null,
            )
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val key = cursor.getString(0)
                        val base =
                            Conversation.parse(
                                JSONObject(cursor.getString(1)),
                                key.startsWith("g:"),
                            )
                        add(
                            base.copy(
                                updated = cursor.getLong(2),
                                subtitle = cursor.getString(3).ifEmpty { base.subtitle },
                                pinned = cursor.getInt(4) == 1,
                                muted = cursor.getInt(5) == 1,
                                archived = cursor.getInt(6) == 1,
                                draft = cursor.getString(7),
                                favorite = cursor.getInt(8) == 1,
                                hidden = cursor.getInt(9) == 1,
                                markedUnread = cursor.getInt(10) == 1,
                                lastMessage =
                                    if (cursor.isNull(11)) null
                                    else Message.parse(JSONObject(cursor.getString(11)), key),
                            )
                        )
                    }
                }
            }

    fun preference(key: String, field: String, enabled: Boolean) {
        require(
            field in setOf("pinned", "muted", "archived", "favorite", "hidden", "marked_unread")
        )
        writableDatabase.update(
            "conversations",
            ContentValues().apply { put(field, if (enabled) 1 else 0) },
            "key=?",
            arrayOf(key),
        )
    }

    fun draft(key: String, text: String) {
        writableDatabase.update(
            "conversations",
            ContentValues().apply { put("draft", text) },
            "key=?",
            arrayOf(key),
        )
    }

    fun preview(key: String, content: String, time: Long) {
        writableDatabase.update(
            "conversations",
            ContentValues().apply {
                put("preview", content)
                put("updated", time)
            },
            "key=?",
            arrayOf(key),
        )
    }

    fun putMessages(key: String, messages: List<Message>) = transaction {
        val clearedThrough = meta("cleared:$key")?.toLongOrNull() ?: 0L
        messages.forEach { message ->
            if (message.id <= clearedThrough) return@forEach
            val deleted =
                rawQuery(
                        "SELECT 1 FROM deleted_messages WHERE conversation=? AND id=?",
                        arrayOf(key, message.id.toString()),
                    )
                    .use { it.moveToFirst() }
            if (deleted) return@forEach
            insertWithOnConflict(
                "messages",
                null,
                ContentValues().apply {
                    put("conversation", key)
                    put("id", message.id)
                    put("body", message.raw)
                    put("content", message.preview)
                    put("created", message.time)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    fun messages(key: String, limit: Int = 80, before: Long = Long.MAX_VALUE): List<Message> =
        readableDatabase
            .rawQuery(
                "SELECT conversation,body FROM messages WHERE conversation=? AND id<? ORDER BY id DESC LIMIT ?",
                arrayOf(key, before.toString(), limit.toString()),
            )
            .use {
                readMessages(it).reversed()
            }

    fun messageBatch(key: String, after: Long, through: Long): List<Message> =
        readableDatabase
            .rawQuery(
                "SELECT conversation,body FROM messages WHERE conversation=? AND id>? AND id<=? ORDER BY id LIMIT 200",
                arrayOf(key, after.toString(), through.toString()),
            )
            .use { readMessages(it) }

    fun statistics(): Pair<Long, Long> {
        val messages =
            readableDatabase.rawQuery("SELECT COUNT(*) FROM messages", null).use {
                it.moveToFirst()
                it.getLong(0)
            }
        val pending =
            readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox", null).use {
                it.moveToFirst()
                it.getLong(0)
            }
        return messages to pending
    }

    fun latestImageUrl(): String? =
        readableDatabase
            .rawQuery(
                "SELECT body FROM messages WHERE content=? ORDER BY created DESC",
                arrayOf("[图片]"),
            )
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val message = Message.parse(JSONObject(cursor.getString(0)), "")
                    if (!message.recalled && message.image.isNotBlank()) return@use message.image
                }
                null
            }

    fun latest(key: String): Long =
        readableDatabase
            .rawQuery("SELECT MAX(id) FROM messages WHERE conversation=?", arrayOf(key))
            .use {
                if (it.moveToFirst()) it.getLong(0) else 0
            }

    fun removeMessage(key: String, id: Long) {
        writableDatabase.delete("messages", "conversation=? AND id=?", arrayOf(key, id.toString()))
    }

    fun search(
        query: String,
        conversation: String = "",
        sender: Long = 0,
        after: Long = 0,
        before: Long = Long.MAX_VALUE,
        type: String = "all",
    ): List<Message> {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val conditions = mutableListOf("content LIKE ? ESCAPE '\\'", "created>=?", "created<=?")
        val args = mutableListOf("%$escaped%", after.toString(), before.toString())
        if (conversation.isNotEmpty()) {
            conditions += "conversation=?"
            args += conversation
        }
        return readableDatabase
            .rawQuery(
                "SELECT conversation,body FROM messages WHERE ${conditions.joinToString(" AND ")} ORDER BY created DESC",
                args.toTypedArray(),
            )
            .use { cursor ->
                buildList {
                    while (size < 200 && cursor.moveToNext()) {
                        val message =
                            Message.parse(JSONObject(cursor.getString(1)), cursor.getString(0))
                        val matchesType =
                            when (type) {
                                "private" -> message.conversation.startsWith("p:")
                                "group" -> message.conversation.startsWith("g:")
                                "image" -> message.type in listOf(2, 5)
                                "voice" -> message.type == 3
                                "file" -> message.type == 6
                                "essence" -> message.essence
                                else -> true
                            }
                        if (matchesType && (sender == 0L || message.sender == sender)) add(message)
                    }
                }
            }
    }

    fun deleteLocally(key: String, ids: List<Long>) = transaction {
        ids.filter { it > 0 }
            .forEach { id ->
                execSQL(
                    "INSERT OR IGNORE INTO deleted_messages(conversation,id) VALUES(?,?)",
                    arrayOf<Any>(key, id),
                )
                delete("messages", "conversation=? AND id=?", arrayOf(key, id.toString()))
            }
    }

    fun clearConversation(key: String, through: Long) = transaction {
        setMeta(
            "cleared:$key",
            maxOf(through, meta("cleared:$key")?.toLongOrNull() ?: 0L).toString(),
        )
        delete("messages", "conversation=?", arrayOf(key))
        update(
            "conversations",
            ContentValues().apply {
                put("preview", "")
                put("draft", "")
            },
            "key=?",
            arrayOf(key),
        )
    }

    fun updateRead(key: String, id: Long, read: Boolean) {
        val message =
            readableDatabase
                .rawQuery(
                    "SELECT body FROM messages WHERE conversation=? AND id=?",
                    arrayOf(key, id.toString()),
                )
                .use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null } ?: return
        message.put("is_read", read)
        writableDatabase.update(
            "messages",
            ContentValues().apply { put("body", message.toString()) },
            "conversation=? AND id=?",
            arrayOf(key, id.toString()),
        )
    }

    private fun readMessages(cursor: Cursor): List<Message> = buildList {
        while (cursor.moveToNext()) add(
            Message.parse(JSONObject(cursor.getString(1)), cursor.getString(0))
        )
    }

    fun enqueue(value: PendingMessage) {
        writableDatabase.insertOrThrow(
            "outbox",
            null,
            ContentValues().apply {
                put("key", value.key)
                put("conversation", value.conversation)
                put("method", value.method)
                put("params", value.params)
                put("created", value.created)
                put("state", value.state)
                put("error", value.error)
            },
        )
    }

    fun pending(): List<PendingMessage> =
        readableDatabase
            .rawQuery(
                "SELECT key,conversation,method,params,created,state,error FROM outbox ORDER BY created,key",
                null,
            )
            .use {
                buildList {
                    while (it.moveToNext()) add(
                        PendingMessage(
                            it.getString(0),
                            it.getString(1),
                            it.getString(2),
                            it.getString(3),
                            it.getLong(4),
                            it.getString(5),
                            it.getString(6),
                        )
                    )
                }
            }

    fun pendingState(key: String, state: String, error: String = "") {
        writableDatabase.update(
            "outbox",
            ContentValues().apply {
                put("state", state)
                put("error", error)
            },
            "key=?",
            arrayOf(key),
        )
    }

    fun complete(key: String) {
        writableDatabase.delete("outbox", "key=?", arrayOf(key))
    }

    fun clearMessages() = transaction { delete("messages", null, null) }

    private fun transaction(block: SQLiteDatabase.() -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
