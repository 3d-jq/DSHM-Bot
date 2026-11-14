package com.dshm.bot.service

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.dshm.bot.data.runtime.RuntimeInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * 手机截图（API 30+ 走 AccessibilityService.takeScreenshot；26–29 明确不支持）。
 *
 * 结果写到 **rootfs 工作区**（`root/.dsh/bot/workspace/shots/`），因为引擎和 Bot
 * 读不到 App 私有目录；契约和 `browser_screenshot` 一样：回一个路径文本，
 * 不把 base64 塞进 result.text（工具结果是字符串，且 30s read timeout）。
 */
internal object PhoneScreenshot {

    /** API 26–29：AccessibilityService.takeScreenshot 不存在，MediaProjection 又要授权弹窗，先明确拒绝。 */
    fun unsupportedReason(): String? = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
            "本机 Android 低于 11，不支持无障碍截图（需要 Android 11+）。"
        else -> null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun capture(
        context: Context,
        service: DshmAccessibilityService,
    ): Result<String> = withContext(Dispatchers.IO) {
        // 悬浮胶囊会挡在截图里；PhoneOverlay.hideForCapture/restore 本来就是为此预留的。
        PhoneOverlay.hideForCapture()
        try {
            val bitmap = captureWithRetry(service)
                ?: return@withContext Result.failure(IllegalStateException("系统没返回截图（可能是安全窗口或截图太频繁）"))
            val file = save(context, bitmap)
            Result.success(file.absolutePath)
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            PhoneOverlay.restore()
        }
    }

    /** ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT：系统限制 1 秒内只能截一张。 */
    private const val ERROR_INTERVAL_TIME_SHORT = 1

    /**
     * 截图 + 限频重试：1 秒内连续截两张会被系统拒绝，遇到该错误码等 1 秒再试一次。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureWithRetry(service: DshmAccessibilityService): Bitmap? {
        val (first, code) = takeOnce(service)
        if (first != null) return first
        if (code != ERROR_INTERVAL_TIME_SHORT) return null
        delay(1_000)
        return takeOnce(service).first
    }

    /** 请求一次截图：成功回 Bitmap；失败回 null 和系统错误码（有返回但转不成 Bitmap 时码为 -1）。 */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeOnce(service: DshmAccessibilityService): Pair<Bitmap?, Int> =
        suspendCancellableCoroutine { cont ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                { runnable -> runnable.run() },
                object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        val bmp = runCatching {
                            Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        buffer.close()
                        cont.resume(bmp to if (bmp == null) -1 else 0)
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resume(null to errorCode)
                    }
                },
            )
        }

    /** 长边压到 1300，JPEG 80，落到 rootfs 工作区让引擎能读。 */
    private fun save(context: Context, bitmap: Bitmap): File {
        val scale = minOf(1f, 1300f / maxOf(bitmap.width, bitmap.height))
        val out = if (scale < 1f) {
            bitmap.scale(
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
            )
        } else {
            bitmap
        }
        val dir = File(RuntimeInstaller(context).rootfsDir, "root/.dsh/bot/workspace/shots")
        dir.mkdirs()
        val file = File(dir, "phone-${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { stream ->
            out.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        }
        if (out !== bitmap) out.recycle()
        return file
    }

    /** 占位：避免 lint 警告未使用的 import（DisplayManager/ImageReader 留给 MediaProjection 路线）。 */
    @Suppress("UNUSED_PARAMETER")
    private fun unused(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(DisplayManager::class.java)
        }
    }
}
