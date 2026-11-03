package com.dshm.bot.data.runtime

import android.content.Context
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import com.dshm.bot.data.settings.ApiKeyStore
import java.util.concurrent.TimeUnit

/**
 * 用 proot 启动 rootfs 里的 `dsh web`，从 stdout 解析 token 得到连接信息。
 * 端口默认自动挑选空闲端口；启动各阶段写入 [EngineLog]。
 */
class RuntimeLauncher(
    context: Context,
    private val installer: RuntimeInstaller = RuntimeInstaller(context),
    private val port: Int = 0,
) {
    private val appContext = context.applicationContext
    @Volatile
    private var process: Process? = null

    init {
        EngineLog.init(appContext)
    }

    /** 启动并等待 token；[guestEnv] 是可选的 KEY=VALUE（如 TZ、API Key）。 */
    fun start(manifest: RuntimeManifest, guestEnv: List<String> = emptyList()): RuntimeEndpoint {
        val rootfs = installer.rootfsDir
        require(rootfs.isDirectory) { "运行时尚未安装" }

        EngineLog.reset()
        EngineLog.log("launch method: proot")
        val tmp = File(appContext.cacheDir, "proot-tmp").apply { mkdirs() }
        val actualPort = if (port > 0) port else pickPort()
        val placeholders = mapOf(
            "{NATIVE_LIB_DIR}" to appContext.applicationInfo.nativeLibraryDir,
            "{ROOTFS}" to rootfs.absolutePath,
            "{TMP}" to tmp.absolutePath,
            "{PORT}" to actualPort.toString(),
        )
        val argv = ProotCommand.buildArgv(manifest.launchArgv, placeholders, guestEnv).toMutableList()
        // 账号登录桥：文件写入 rootfs 后才追加 --patch（失败则跳过，避免 DSH 起不来）。
        if (AuthGuest.writeFiles(appContext, rootfs)) {
            val webIndex = argv.indexOfFirst { it.contains("/usr/local/bin/dsh web") }
            if (webIndex >= 0) AuthGuest.launchArg(argv[webIndex])?.let { argv[webIndex] = it }
        }
        val env = ProotCommand.buildEnv(manifest.launchEnv, placeholders)
        EngineLog.log("launching ${argv.joinToString(" ")}")

        val builder = ProcessBuilder(argv).redirectErrorStream(true)
        builder.environment().putAll(env)
        ApiKeyStore(appContext).key()?.let { key ->
            builder.environment()["DEEPSEEK_API_KEY"] = key
            EngineLog.log("api key: injected")
        }
        builder.environment()["PROOT_NO_SECCOMP"] = "1"
        val started = builder.start()
        process = started

        val queue = java.util.concurrent.LinkedBlockingQueue<String>()
        val tail = ArrayDeque<String>()
        val reader = Thread {
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line ->
                    EngineLog.raw(line)
                    queue.put(line)
                }
            }
        }.apply { isDaemon = true; start() }

        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val line = queue.poll(500, TimeUnit.MILLISECONDS)
            if (line == null) {
                if (!started.isAlive) break
                continue
            }
            tail.addLast(line)
            if (tail.size > 30) tail.removeFirst()
            ProotCommand.parseToken(line)?.let { endpoint ->
                EngineLog.log("DSH is listening at ${endpoint.baseUrl}")
                EngineLog.log("startup: app ${EngineLog.elapsedMs()} ms (port $actualPort)")
                return endpoint
            }
        }
        val exit = runCatching { started.exitValue() }.getOrNull()
        EngineLog.log("startup failed: no token (exit=$exit)")
        throw IllegalStateException(
            "引擎启动失败：未收到 token（exit=$exit）\n" + tail.joinToString("\n"),
        )
    }

    /** 停止引擎：先结束 dsh 进程，再强制结束 proot；同时清理引用。 */
    fun stop() {
        val running = process ?: return
        EngineLog.log("stopping engine")
        runCatching {
            running.destroy()
            if (!running.waitFor(5, TimeUnit.SECONDS)) running.destroyForcibly()
        }
        process = null
    }

    val isRunning: Boolean
        get() = process?.isAlive == true

    /**
     * 与 dsh-Go 一致：先在 3080–3100 找空闲端口（地址稳定），全被占才用系统临时端口。
     */
    private fun pickPort(): Int {
        for (candidate in DEFAULT_PORT..DEFAULT_PORT + 20) {
            try {
                ServerSocket(candidate, 1, InetAddress.getByName("127.0.0.1")).close()
                return candidate
            } catch (_: Exception) {
            }
        }
        return ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    }

    private companion object {
        const val DEFAULT_PORT = 3080
    }
}


