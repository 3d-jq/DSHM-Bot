package com.dshm.bot.data.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.dshm.bot.MainActivity
import com.dshm.bot.R

/** 本机引擎状态通知（对齐 dsh-Go：引擎就绪时通知，受「通知」开关控制）。 */
object EngineNotifier {
    private const val CHANNEL_ID = "engine"

    fun notifyReady(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notify_channel_engine),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lucide_server)
            .setContentTitle(context.getString(R.string.notify_engine_ready_title))
            .setContentText(context.getString(R.string.notify_engine_ready_body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(1, notification)
    }
}
