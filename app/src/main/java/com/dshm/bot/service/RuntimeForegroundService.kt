package com.dshm.bot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dshm.bot.MainActivity
import com.dshm.bot.R
import com.dshm.bot.data.runtime.EngineLog
import com.dshm.bot.data.runtime.RuntimeLauncher
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 引擎常驻前台服务。
 *
 * 为什么必须有它：引擎是 PRoot + Node 子进程（`libproot.so` / `node`），在 Android 12+
 * 被登记成 **PhantomProcessRecord**。父 App 一旦落进 cached，系统裁剪幽灵进程时引擎
 * 就可能被单独杀掉 —— 表现是「刚才还在，切出去回来引擎没了、又要冷启动十几秒」。
 *
 * 挂上前台服务后进程 importance 落到 `FOREGROUND_SERVICE`，不再进 cached，
 * 幽灵进程也就稳住了。dsh-Go 用的是同一招（`runtime.DshService`，通知渠道
 * `dsh-runtime`，标题 = App 名，正文 = 动态状态，带一个「停止引擎」动作）。
 *
 * targetSdk 28（PRoot 的取舍，见 `docs/architecture.md`）让我们不需要
 * `POST_NOTIFICATIONS` 运行时授权，也不受 Android 12+ 前台服务启动时机限制。
 */
class RuntimeForegroundService : Service() {

    /**
     * 「启动引擎」请求代数：每次非 STOP 的 [onStartCommand] 自增。
     * 停止请求记下当时的代数，清扫前再核对 —— 期间来了新的启动指令就放弃，
     * 不然按 `/proc` 反查的清扫会把刚重启的引擎当孤儿杀掉。
     */
    private val startGeneration = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngine(startId)
                return START_NOT_STICKY
            }
        }
        startGeneration.incrementAndGet()
        val text = intent?.getStringExtra(EXTRA_STATUS)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.engine_notification_running)
        // 只有「正在启动/校验」才是进行中；引擎跑起来后进度条要收掉，
        // 否则一个已经就绪的引擎会一直转圈，看起来像卡住了。
        promote(text, busy = intent?.getBooleanExtra(EXTRA_BUSY, false) == true)
        return START_STICKY
    }

    private fun promote(text: String, busy: Boolean) {
        val notification = build(this, text, busy)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 通知里的「停止引擎」。
     *
     * 这里拿不到 `RuntimeLauncher` 里那个 `Process` 句柄（App 可能已经被系统杀过，
     * 引擎是孤儿进程），所以走按 `/proc` 反查的 [RuntimeLauncher.stopAll]。
     */
    private fun stopEngine(startId: Int) {
        EngineLog.log("foreground service: stop requested from notification")
        val requestedGeneration = startGeneration.get()
        thread(name = "dshm-engine-stop") {
            // 请求停止之后引擎又被重启（例如用户马上点了启动）：放弃清扫，
            // 也不要广播「已停止」/拆掉前台服务 —— 新引擎还需要这条常驻通知保活。
            if (startGeneration.get() != requestedGeneration) {
                EngineLog.log("foreground service: stop aborted, engine restarted meanwhile")
                return@thread
            }
            runCatching { RuntimeLauncher(this).stopAll() }
                .onFailure { EngineLog.log("foreground service: stop failed: ${it.message}") }
            // 通知栏上的「停止引擎」已经生效，让 App 里的引擎页也跟着回到「已停止」。
            sendBroadcast(Intent(ACTION_ENGINE_STOPPED).setPackage(packageName))
            runCatching {
                stopForeground(STOP_FOREGROUND_REMOVE)
                // stopSelf(startId) 只在 startId 仍是最新一次启动时才真正停止：
                // 清扫期间服务又被启动的话，前台通知留给新引擎。
                stopSelf(startId)
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.dshm.bot.action.ENGINE_STOP"
        const val ACTION_ENGINE_STOPPED = "com.dshm.bot.action.ENGINE_STOPPED"
        const val EXTRA_STATUS = "status"

        private const val CHANNEL_ID = "dshm-runtime"
        private const val NOTIFICATION_ID = 3080

        /**
         * 让常驻通知跟着引擎状态更新（同 ID 重发即可，不会再多一条）。
         *
         * 引擎在跑的时候调用；即使引擎已经起来（热启动接上的），也要走一次，
         * 否则上次被系统杀掉的服务没恢复，进程又会掉回 cached。
         */
        fun start(context: Context, status: String? = null, busy: Boolean = false) {
            val intent = Intent(context, RuntimeForegroundService::class.java)
                .putExtra(EXTRA_STATUS, status)
                .putExtra(EXTRA_BUSY, busy)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                EngineLog.log("foreground service: start failed: ${it.message}")
            }
        }

        /** 引擎停止 / 重置后撤掉通知与服务。 */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, RuntimeForegroundService::class.java))
            }
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.engine_notification_channel),
                // 低重要性 + 静音：常驻但不打扰，对齐 dsh-Go 的 `dsh-runtime`。
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.engine_notification_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }

        private fun build(context: Context, status: String, busy: Boolean): Notification {
            ensureChannel(context)
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val stop = PendingIntent.getService(
                context,
                1,
                Intent(context, RuntimeForegroundService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(status)
                .setContentIntent(open)
                .addAction(0, context.getString(R.string.engine_notification_stop), stop)
                .setOngoing(true)
                .setShowWhen(false)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                // 启动阶段画无限进度条（跟 dsh-Go 一致），就绪后必须去掉。
                .apply { if (busy) setProgress(0, 0, true) else setProgress(0, 0, false) }
                .build()
        }

        private const val EXTRA_BUSY = "busy"
    }
}
