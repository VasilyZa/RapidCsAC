package com.xiaobai.rapidcsac.platform

import java.util.Locale
import org.json.JSONObject

enum class InterfaceFont(val label: String) {
    SYSTEM("系统字体"),
    SERIF("衬线字体"),
    ROUNDED("圆润字体"),
    MONOSPACE("等宽字体"),
}

enum class BubbleCorners(val label: String, val radius: Int) {
    STANDARD("标准", 16),
    SOFT("圆润", 24),
    SQUARE("轻圆角", 8),
}

enum class ConversationOrder(val label: String) {
    LATEST("最近消息"),
    TYPE("好友与群聊分组"),
}

enum class ConversationSubtitle(val label: String) {
    MESSAGE("最近消息"),
    STATUS("在线状态与群介绍"),
}

data class Personalization(
    val accent: Int = 0xff4169d8.toInt(),
    val font: InterfaceFont = InterfaceFont.SYSTEM,
    val fontScale: Float = 1f,
    val compact: Boolean = false,
    val highContrast: Boolean = false,
    val corners: BubbleCorners = BubbleCorners.STANDARD,
    val ownBubble: Int? = null,
    val otherBubble: Int? = null,
    val bubbleOpacity: Float = 1f,
    val backgroundTint: Int? = null,
    val wallpaperDim: Float = .55f,
    val showAvatars: Boolean = true,
    val enablePat: Boolean = true,
    val conversationOrder: ConversationOrder = ConversationOrder.LATEST,
    val conversationSubtitle: ConversationSubtitle = ConversationSubtitle.MESSAGE,
) {
    fun validate() {
        require(fontScale.isFinite() && fontScale in .85f..1.3f) { "字号比例需为 85%–130%" }
        require(bubbleOpacity.isFinite() && bubbleOpacity in .7f..1f) { "气泡不透明度需为 70%–100%" }
        require(wallpaperDim.isFinite() && wallpaperDim in 0.3f..0.8f) { "背景遮罩需为 30%–80%" }
        require(
            listOfNotNull(accent, ownBubble, otherBubble, backgroundTint).all { it ushr 24 == 255 }
        ) {
            "请选择不透明的 RGB 颜色"
        }
    }

    fun toJson(): JSONObject =
        JSONObject()
            .put("accent", accent)
            .put("font", font.name)
            .put("font_scale", fontScale)
            .put("compact", compact)
            .put("high_contrast", highContrast)
            .put("corners", corners.name)
            .put("own_bubble", ownBubble ?: JSONObject.NULL)
            .put("other_bubble", otherBubble ?: JSONObject.NULL)
            .put("bubble_opacity", bubbleOpacity)
            .put("background_tint", backgroundTint ?: JSONObject.NULL)
            .put("wallpaper_dim", wallpaperDim)
            .put("show_avatars", showAvatars)
            .put("enable_pat", enablePat)
            .put("conversation_order", conversationOrder.name)
            .put("conversation_subtitle", conversationSubtitle.name)

    companion object {
        fun fromJson(value: JSONObject): Personalization {
            val defaults = Personalization()
            return Personalization(
                accent = value.color("accent", defaults.accent)!!,
                font = value.enumValue("font", defaults.font),
                fontScale = value.ratio("font_scale", .85f..1.3f, 1f),
                compact = value.optBoolean("compact", false),
                highContrast = value.optBoolean("high_contrast", false),
                corners = value.enumValue("corners", defaults.corners),
                ownBubble = value.color("own_bubble", null),
                otherBubble = value.color("other_bubble", null),
                bubbleOpacity = value.ratio("bubble_opacity", .7f..1f, 1f),
                backgroundTint = value.color("background_tint", null),
                wallpaperDim = value.ratio("wallpaper_dim", 0.3f..0.8f, defaults.wallpaperDim),
                showAvatars = value.optBoolean("show_avatars", true),
                enablePat = value.optBoolean("enable_pat", true),
                conversationOrder =
                    value.enumValue("conversation_order", defaults.conversationOrder),
                conversationSubtitle =
                    value.enumValue("conversation_subtitle", defaults.conversationSubtitle),
            )
        }
    }
}

fun parsePersonalColor(value: String): Int {
    val raw = value.trim().removePrefix("#")
    require(raw.matches(Regex("[a-fA-F0-9]{6}"))) { "请输入六位十六进制颜色，例如 #4169D8" }
    return (0xff000000L or raw.toLong(16)).toInt()
}

fun personalColorHex(value: Int): String = String.format(Locale.ROOT, "#%06X", value and 0xffffff)

private fun JSONObject.color(key: String, default: Int?): Int? {
    val value = (opt(key) as? Number)?.toLong() ?: return default
    if (value !in Int.MIN_VALUE.toLong()..0xffffffffL) return default
    return value.toInt().takeIf { it ushr 24 == 255 } ?: default
}

private fun JSONObject.ratio(
    key: String,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
): Float =
    optDouble(key, default.toDouble()).toFloat().takeIf { it.isFinite() && it in range } ?: default

private inline fun <reified T : Enum<T>> JSONObject.enumValue(key: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == optString(key) } ?: default
