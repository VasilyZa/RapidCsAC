package com.xiaobai.rapidcsac.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xiaobai.rapidcsac.MainActivity
import com.xiaobai.rapidcsac.R
import com.xiaobai.rapidcsac.core.model.Conversation

object Notifications {
    fun message(context: Context, conversation: Conversation, account: String = "") {
        if (
            !context
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("notifications", true)
        )
            return
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        )
            return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            manager.createNotificationChannel(
                NotificationChannel("messages", "聊天消息", NotificationManager.IMPORTANCE_DEFAULT)
            )
        val intent =
            Intent(context, MainActivity::class.java)
                .putExtra("conversation", conversation.key)
                .putExtra("name", conversation.name)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending =
            PendingIntent.getActivity(
                context,
                (account + conversation.key).hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, "messages")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(conversation.name)
                .setContentText("收到新的聊天消息")
                .setContentIntent(pending)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
        manager.notify((account + conversation.key).hashCode(), notification)
    }
}
