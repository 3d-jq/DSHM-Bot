package com.dshm.bot.data.runtime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 「访问手机文件」的系统权限与偏好（对齐 dsh-Go `RuntimeBridge.requestStorage` /
 * `openAllFilesAccess` / `storageStatus`）。
 *
 * Android 11+ 需要「所有文件访问」(`MANAGE_EXTERNAL_STORAGE`)；旧系统退回
 * `READ/WRITE_EXTERNAL_STORAGE`。只有拿到授权才把 `/sdcard` bind 进引擎。
 */
object PhoneStorageAccess {

    private const val PREFS_NAME = "dshm_settings"
    private const val KEY_PHONE_STORAGE = "phone_storage_enabled"

    fun enabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PHONE_STORAGE, false) && granted(context)
    }

    fun granted(context: Context): Boolean {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            app.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
                app.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /** 打开系统「所有文件访问」页；旧系统的运行时权限由调用方自行申请。 */
    fun openSystemSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isFailure) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }
}
