package com.xiaobai.rapidcsac.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale
import java.util.TimeZone

data class MessageDateContext(val nowMillis: Long, val zone: TimeZone, val locale: Locale)

/** One clock snapshot per screen, refreshed by system events and foregrounding. */
@Composable
fun rememberMessageDateContext(): MessageDateContext {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val locale = LocalConfiguration.current.locales[0]
    fun snapshot() = MessageDateContext(System.currentTimeMillis(), TimeZone.getDefault(), locale)
    var value by remember { mutableStateOf(snapshot()) }
    DisposableEffect(context, lifecycle, locale) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    value = snapshot()
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_LOCALE_CHANGED)
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) value = snapshot()
        }
        lifecycle.addObserver(observer)
        value = snapshot()
        onDispose {
            context.unregisterReceiver(receiver)
            lifecycle.removeObserver(observer)
        }
    }
    return value
}
