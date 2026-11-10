package com.dshm.bot.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import com.dshm.bot.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 手机操控的确认门。动作需要确认时发一条带「允许 / 拒绝」的通知，
 * 等用户点按后再把结果交回执行器；没有通知权限时明确失败，不静默放行。
 */
object PhoneApprovalGate {
    private data class Pending(
        val notificationId: Int,
        val result: CompletableDeferred<Boolean>,
    )

    private const val CHANNEL = "phone_control_approval"
    private val pending = ConcurrentHashMap<String, Pending>()
    private val counter = AtomicInteger(7000)
    @Volatile
    private var latest: String? = null

    /**
     * @return true 允许，false 拒绝/超时，null 没有通知权限或通知发不出去。
     */
    suspend fun request(
        context: Context,
        title: String,
        body: String,
        allowLabel: String,
        denyLabel: String,
        timeoutMs: Long,
    ): Boolean? {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val id = "phone-approval-${counter.incrementAndGet()}"
        val notificationId = counter.get()
        val result = CompletableDeferred<Boolean>()
        pending[id] = Pending(notificationId, result)
        latest = id
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context, manager)
        val allow = action(context, id, notificationId, true, allowLabel, notificationId + 1)
        val deny = action(context, id, notificationId, false, denyLabel, notificationId + 2)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_lucide_smartphone)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(allow)
            .addAction(deny)
            .build()
        return try {
            manager.notify(notificationId, notification)
            val answer = withTimeoutOrNull(timeoutMs) { result.await() }
            manager.cancel(notificationId)
            answer ?: false
        } catch (_: Throwable) {
            null
        } finally {
            pending.remove(id)
            if (latest == id) latest = null
            manager.cancel(notificationId)
        }
    }

    fun complete(id: String, allowed: Boolean) {
        pending[id]?.result?.complete(allowed)
    }

    /** 浮条上直接点了「允许」。 */
    fun allowLatest(): Boolean {
        val id = latest ?: return false
        complete(id, true)
        return true
    }

    /** 浮条上直接点了「拒绝」。 */
    fun denyLatest(): Boolean {
        val id = latest ?: return false
        complete(id, false)
        return true
    }

    private fun action(
        context: Context,
        id: String,
        notificationId: Int,
        allow: Boolean,
        label: String,
        requestCode: Int,
    ): Notification.Action {
        val intent = Intent(context, PhoneApprovalReceiver::class.java)
            .putExtra(PhoneApprovalReceiver.EXTRA_ID, id)
            .putExtra(PhoneApprovalReceiver.EXTRA_ALLOW, allow)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(
                context,
                if (allow) R.drawable.ic_lucide_check else R.drawable.ic_lucide_x,
            ),
            label,
            pendingIntent,
        ).build()
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.phone_approval_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.phone_approval_channel_hint)
                    setSound(null, null)
                },
            )
        }
    }
}
