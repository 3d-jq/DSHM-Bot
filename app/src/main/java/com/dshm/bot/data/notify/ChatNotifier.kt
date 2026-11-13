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
import java.util.concurrent.ConcurrentHashMap

/**
 * 对话通知：回复完成 / 出错、等待审批、等待回答。
 *
 * 只在 App 不在前台时发系统通知；用户在对话里看到的内容不再打扰。
 * 每条通知能点开 App，回到对应 Bot 的对话由入口页负责。
 */
object ChatNotifier {
    private const val CHANNEL_CHAT = "chat"
    private const val CHANNEL_ATTENTION = "chat_attention"
    private val recent = ConcurrentHashMap<String, Long>()

    enum class Kind { DONE, ERROR, APPROVAL, QUESTION }

    fun notify(context: Context, botId: String, kind: Kind, title: String, text: String) {
        if (MainActivity.isForeground) return
        if (kind == Kind.DONE && wasNotifiedRecently(botId, Kind.ERROR)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannels(context, manager)

        val attention = kind == Kind.APPROVAL || kind == Kind.QUESTION
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_BOT_ID, botId)
        val pending = PendingIntent.getActivity(
            context,
            notificationId(botId, kind),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(
            context,
            if (attention) CHANNEL_ATTENTION else CHANNEL_CHAT,
        )
            .setSmallIcon(
                when (kind) {
                    Kind.DONE -> R.drawable.ic_lucide_check
                    Kind.ERROR -> R.drawable.ic_lucide_circle_stop
                    Kind.APPROVAL -> R.drawable.ic_lucide_shield
                    Kind.QUESTION -> R.drawable.ic_lucide_info
                },
            )
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(notificationId(botId, kind), notification)
        recent["$botId:${kind.name}"] = System.currentTimeMillis()
    }

    /** per-chat 的错误/完成通知与全局 tracker 共用：全局不要重复补一条。 */
    fun wasNotifiedRecently(botId: String, kind: Kind, withinMs: Long = 20_000L): Boolean {
        val at = recent["$botId:${kind.name}"] ?: return false
        return System.currentTimeMillis() - at < withinMs
    }

    /** 用户打开这个 Bot 的对话：它的通知都不再需要。 */
    fun cancel(context: Context, botId: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        Kind.entries.forEach { manager.cancel(notificationId(botId, it)) }
    }

    private fun notificationId(botId: String, kind: Kind): Int =
        (botId + ":" + kind.name).hashCode() and 0x7fffffff

    private fun ensureChannels(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_CHAT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CHAT,
                    context.getString(R.string.notify_channel_chat),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        if (manager.getNotificationChannel(CHANNEL_ATTENTION) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ATTENTION,
                    context.getString(R.string.notify_channel_attention),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }
}
