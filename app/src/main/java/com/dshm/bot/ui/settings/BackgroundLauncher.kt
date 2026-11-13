package com.dshm.bot.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri

/**
 * 后台保活跳转（对齐 dsh-Go `VendorSettings` + `RuntimePlugin.openVendorSettings`）。
 *
 * 每个厂商的后台保活开关在它自己的系统页里，Android 没有统一入口。这里的表是
 * 「逐个 intent 尝试，能解析成已导出的 Activity 就先跳它」，全部失败退回应用详情页。
 */
object BackgroundLauncher {

    private val VIVO_PERMISSIONS = "com.vivo.permissionmanager"
    private val IQOO_SECURE = "com.iqoo.secure"
    private val HUAWEI_MANAGER = "com.huawei.systemmanager"
    private val HONOR_MANAGER = "com.hihonor.systemmanager"

    private data class Page(
        val pkg: String,
        val cls: String? = null,
        val action: String? = null,
        val packageExtra: String? = null,
        val labelExtra: String? = null,
    )

    /** 后台保活页的厂商特异性列表（每个厂商的「允许后台高耗电」那一处）。 */
    private fun vendorPages(vendor: String?): List<Page> = when (vendor) {
        "vivo" -> listOf(
            Page("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
            Page("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity"),
        )
        "xiaomi" -> listOf(
            Page(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                packageExtra = "package_name",
                labelExtra = "package_label",
            ),
        )
        "huawei" -> listOf(
            Page(HUAWEI_MANAGER, "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Page(HUAWEI_MANAGER, "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            Page(HUAWEI_MANAGER, "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        "honor" -> listOf(
            Page(HONOR_MANAGER, "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Page(HUAWEI_MANAGER, "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        "oppo" -> emptyList() // OPPO 的后台保活开关在应用详情页里，退回系统详情页
        "samsung" -> emptyList() // 同理
        else -> emptyList()
    }

    /** 打开本机厂商的后台保活页；没有厂商专用页就退回系统「忽略电池优化」再退回应用详情。 */
    fun openVendorBackground(context: Context) {
        val vendor = vendorOf(Build.MANUFACTURER, Build.BRAND)
        if (launchVendorPages(context, vendor)) return
        // dsh-Go：厂商没有专用页时退回**应用详情页**，不是系统电池优化列表。
        // OPPO / 三星的后台开关就在应用详情里，跳系统电池优化会落到原生安卓页。
        openAppDetails(context)
    }

    /** 系统电池优化（对齐 dsh-Go `requestBatteryExempt`）：对话框 → 列表 → 应用详情。 */
    fun openSystemBattery(context: Context) {
        val packageUri = "package:${context.packageName}".toUri()
        val targets = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        )
        for (intent in targets) {
            if (launch(context, intent)) return
        }
    }

    /** 应用详情页（厂商没有专用后台页时的兜底，和 dsh-Go `openVendorSettings` 一致）。 */
    fun openAppDetails(context: Context) {
        launch(
            context,
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${context.packageName}".toUri(),
            ),
        )
    }

    private fun launchVendorPages(context: Context, vendor: String?): Boolean {
        val pm = context.packageManager
        val packageName = context.packageName
        val appLabel = runCatching {
            context.applicationInfo.loadLabel(pm).toString()
        }.getOrDefault(packageName)
        for (page in vendorPages(vendor)) {
            val intent = if (page.cls != null) {
                Intent().setComponent(ComponentName(page.pkg, page.cls))
            } else {
                Intent(page.action ?: continue).setPackage(page.pkg)
            }
            page.packageExtra?.let { intent.putExtra(it, packageName) }
            page.labelExtra?.let { intent.putExtra(it, appLabel) }
            // 只跳导出的 Activity；解析失败/没导出就跳过，别让厂商页崩掉。
            val info = runCatching { intent.resolveActivityInfo(pm, 0) }.getOrNull()
            if (info?.exported != true) continue
            if (page.cls == null) intent.setClassName(info.packageName, info.name)
            if (launch(context, intent)) return true
        }
        return false
    }

    private fun launch(context: Context, intent: Intent): Boolean =
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess

    private fun vendorOf(manufacturer: String?, brand: String?): String? =
        match(manufacturer) ?: match(brand)

    private fun match(name: String?): String? {
        val n = name?.trim()?.lowercase() ?: return null
        fun any(vararg keys: String) = keys.any { n.startsWith(it) }
        return when {
            any("vivo", "iqoo") -> "vivo"
            any("xiaomi", "redmi", "poco") -> "xiaomi"
            any("huawei") -> "huawei"
            any("honor", "hihonor") -> "honor"
            any("oppo", "oneplus", "realme") -> "oppo"
            any("samsung") -> "samsung"
            else -> null
        }
    }
}
