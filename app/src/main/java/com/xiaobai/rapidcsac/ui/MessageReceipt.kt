package com.xiaobai.rapidcsac.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MessageReceipt(read: Boolean, modifier: Modifier = Modifier, delivery: String = "") {
    val label =
        when (delivery) {
            "queued" -> "等待连接"
            "sending" -> "正在发送"
            "uncertain" -> "发送状态待确认"
            "failed" -> "发送失败，长按重试"
            else -> if (read) "对方已读" else "服务端已接收"
        }
    val color by
        animateColorAsState(
            when {
                delivery == "failed" -> MiuixTheme.colorScheme.error
                read -> MiuixTheme.colorScheme.primary
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            tween(RapidMotion.feedbackMillis),
            label = "receipt",
        )
    Canvas(modifier.size(22.dp, 16.dp).semantics { contentDescription = label }) {
        val unit = size.height / 16f
        val stroke = 1.6f * unit
        if (delivery.isNotEmpty()) {
            drawCircle(color, 5.5f * unit, Offset(11f * unit, 8f * unit), style = Stroke(stroke))
            if (delivery in listOf("failed", "uncertain")) {
                drawLine(
                    color,
                    Offset(11f * unit, 4.5f * unit),
                    Offset(11f * unit, 8.5f * unit),
                    stroke,
                    StrokeCap.Round,
                )
                drawCircle(color, unit * .7f, Offset(11f * unit, 11f * unit))
            } else {
                drawLine(
                    color,
                    Offset(11f * unit, 4.5f * unit),
                    Offset(11f * unit, 8f * unit),
                    stroke,
                    StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(11f * unit, 8f * unit),
                    Offset(14f * unit, 8f * unit),
                    stroke,
                    StrokeCap.Round,
                )
            }
        } else {
            val first =
                Path().apply {
                    moveTo(1f * unit, 8f * unit)
                    lineTo(5f * unit, 12f * unit)
                    lineTo(14f * unit, 3f * unit)
                }
            val second =
                Path().apply {
                    moveTo(9f * unit, 10f * unit)
                    lineTo(11f * unit, 12f * unit)
                    lineTo(20f * unit, 3f * unit)
                }
            drawPath(first, color, style = Stroke(stroke, cap = StrokeCap.Round))
            drawPath(second, color, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}
