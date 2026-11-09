package com.xiaobai.rapidcsac.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaobai.rapidcsac.core.model.Message
import com.xiaobai.rapidcsac.platform.ChatMedia
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun VoiceMessage(message: Message?, media: ChatMedia) {
    val id = message?.id ?: -1L
    val active = media.playing == id
    val preparing = media.preparing == id
    var dragging by remember(id) { mutableStateOf<Float?>(null) }
    LaunchedEffect(active, media.paused) {
        while (active && !media.paused) {
            media.updatePlaybackPosition()
            delay(250)
        }
    }
    Column(Modifier.widthIn(min = 160.dp, max = 240.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RapidIconButton(
                if (active && !media.paused) "pause" else "play",
                if (active && !media.paused) "暂停语音" else "播放语音",
                !preparing,
            ) {
                if (message == null) media.previewVoice() else media.play(message)
            }
            Text(
                if (preparing) "加载语音…"
                else if (active) "${media.positionMs/1000} / ${media.durationMs/1000} 秒"
                else "${message?.duration?:media.voice?.seconds?:0} 秒",
                fontSize = 14.sp,
            )
        }
        Slider(
            value = if (active) dragging ?: media.positionMs.toFloat() else 0f,
            enabled = active,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { media.seek(it.toInt()) }
                dragging = null
            },
            valueRange =
                0f..maxOf(
                            1,
                            if (active) media.durationMs
                            else (message?.duration ?: media.voice?.seconds ?: 0) * 1000,
                        )
                        .toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun RecordingMeter(media: ChatMedia) {
    val samples = remember { mutableStateListOf<Float>() }
    val color = MiuixTheme.colorScheme.primary
    LaunchedEffect(media.recordingSince) {
        while (media.recordingSince > 0) {
            samples.add(media.recordingAmplitude())
            if (samples.size > 32) samples.removeAt(0)
            delay(100)
        }
    }
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val gap = size.width / 32
        samples.forEachIndexed { index, value ->
            val height = (value.coerceIn(.08f, 1f) * size.height * .9f) / 2
            val x = gap * (index + .5f)
            drawLine(
                color,
                Offset(x, size.height / 2 - height),
                Offset(x, size.height / 2 + height),
                3.dp.toPx(),
                StrokeCap.Round,
            )
        }
    }
}
