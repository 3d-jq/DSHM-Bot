package com.dshm.bot.data.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 本机引擎运行时的状态。 */
sealed interface RuntimeState {
    data object NotInstalled : RuntimeState
    data class Installing(val progress: Int, val message: String) : RuntimeState
    data class Installed(val version: String) : RuntimeState
    data object Starting : RuntimeState
    data class Running(val endpoint: RuntimeEndpoint) : RuntimeState
    data object Stopped : RuntimeState
    data class Failed(val message: String) : RuntimeState
}

/**
 * 运行时管理：下载 zip（进度）→ 交给 [RuntimeInstaller] 安装 → 维护 [state]。
 * 下载源可配置，默认用我们 Release 的运行时包。
 */
class RuntimeManager(
    context: Context,
    private val installer: RuntimeInstaller = RuntimeInstaller(context),
    private val manifest: RuntimeManifest = RuntimeManifest.load(context),
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<RuntimeState>(
        if (installer.isInstalled()) {
            RuntimeState.Installed(installer.installedVersion() ?: manifest.runtimeVersion)
        } else {
            RuntimeState.NotInstalled
        },
    )
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    suspend fun installFromUrl(url: String) {
        _state.value = RuntimeState.Installing(0, "准备安装")
        try {
            // 开发/调试：外部目录放了 runtime.zip 就直接本地安装，省去下载。
            val localZip = File(appContext.getExternalFilesDir(null), "runtime.zip")
            if (localZip.isFile) {
                withContext(Dispatchers.IO) {
                    val version = installer.installFromZip(localZip, manifest) { p, m ->
                        _state.value = RuntimeState.Installing(p, m)
                    }
                    _state.value = RuntimeState.Installed(version)
                }
                return
            }
            val zip = withContext(Dispatchers.IO) { download(url) }
            val version = withContext(Dispatchers.IO) {
                installer.installFromZip(zip, manifest) { progress, message ->
                    _state.value = RuntimeState.Installing(progress, message)
                }
            }
            zip.delete()
            _state.value = RuntimeState.Installed(version)
        } catch (t: Throwable) {
            _state.value = RuntimeState.Failed(t.message ?: "安装失败")
        }
    }

    private fun download(url: String): File {
        val target = File(appContext.cacheDir, "dshm-runtime.zip")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            require(connection.responseCode in 200..299) { "下载失败：HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong
            var read = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            _state.value = RuntimeState.Installing(
                                (read * 100 / total).toInt().coerceIn(0, 100),
                                "下载中",
                            )
                        }
                    }
                }
            }
            return target
        } finally {
            connection.disconnect()
        }
    }
}




