package com.xiaobai.rapidcsac.core.network

import org.json.JSONArray
import org.json.JSONObject

fun json(vararg values: Pair<String, Any?>): JSONObject =
    JSONObject().apply {
        values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }

fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key).objects()

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject.firstText(vararg keys: String): String =
    keys
        .firstNotNullOfOrNull { key ->
            optString(key).takeUnless { it.isBlank() || it == "null" }
        }
        .orEmpty()

fun JSONObject.firstLong(vararg keys: String): Long =
    keys.firstNotNullOfOrNull { key ->
        opt(key)?.toString()?.toLongOrNull()?.takeIf { it != 0L }
    } ?: 0L

fun JSONObject.flag(key: String): Boolean = optBoolean(key, false) || optString(key) == "1"

class ApiFailure(val code: Int, override val message: String, val details: JSONObject? = null) :
    Exception(message)

class ConnectionLost(message: String = "连接已断开，请稍后重试", cause: Throwable? = null) :
    Exception(message, cause)
