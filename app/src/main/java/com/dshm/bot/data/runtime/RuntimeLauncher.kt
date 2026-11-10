package com.dshm.bot.data.runtime

import android.content.Context
import android.os.SystemClock
import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
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

    /**
     * 上次 App 进程被杀之后，proot/node 常常还活着。
     *
     * 能接上就直接接上：从 `engine.log` 取回上次的连接信息，确认进程与端口都还在，
     * 并且这次安装的 APK / 运行时版本没变过（否则连到的是旧插件）。返回非空就说明
     * 不用重启引擎 —— 冷启动从十几秒变成一秒。
     */
    fun adoptAlive(manifest: RuntimeManifest): RuntimeEndpoint? {
        val rootfs = installer.rootfsDir
        if (!rootfs.isDirectory) return null
        if (engineProcesses(rootfs).isEmpty()) return null
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_APK_STAMP, -1L) != appStamp()) return null
        if (prefs.getString(KEY_RUNTIME_VERSION, null) != manifest.runtimeVersion) return null
        if (prefs.getBoolean(KEY_PHONE_CONTROL, false) != phoneControlEnabled()) return null
        if (prefs.getString(KEY_PHONE_OWNER, null) != phoneControlOwner()) return null
        val endpoint = lastEndpoint() ?: return null
        if (!listening(endpoint.baseUrl)) return null
        return endpoint
    }

    /** 启动并等待 token；[guestEnv] 是可选的 KEY=VALUE（如 TZ、API Key）。 */
    fun start(manifest: RuntimeManifest, guestEnv: List<String> = emptyList()): RuntimeEndpoint {
        val requested = SystemClock.elapsedRealtime()
        val rootfs = installer.rootfsDir
        require(rootfs.isDirectory) { "运行时尚未安装" }
        GuestSetup.apply(rootfs)
        clearOrphanEngines(rootfs)

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
        // 「访问手机文件」：把手机共享存储挂到 guest 的 /sdcard。
        // 对齐 dsh-Go `_onPhoneFiles`——bind 是启动时固定的，所以开关变化后必须重启引擎。
        if (PhoneStorageAccess.enabled(appContext)) addPhoneStorageBind(argv)
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
        val spawned = SystemClock.elapsedRealtime()
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_APK_STAMP, appStamp())
            .putString(KEY_RUNTIME_VERSION, manifest.runtimeVersion)
            .putBoolean(KEY_PHONE_CONTROL, phoneControlEnabled())
            .putString(KEY_PHONE_OWNER, phoneControlOwner())
            .apply()

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

        // 插件刚覆盖、设备又忙的时候，冷启动会明显超过两分钟；token 到了才是真的起来了。
        val deadline = System.currentTimeMillis() + 180_000
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
                EngineLog.log("startup: ${startupLine(requested, spawned, SystemClock.elapsedRealtime())}")
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

    /**
     * 停止引擎（对齐 manifest `launch.stop`）：
     * 先给 guest 的 `dsh` 进程 SIGTERM，让它正常退出；proot 不响应 SIGTERM，
     * 再对 proot 发 SIGQUIT 杀掉所有 tracee，最后才强制结束并清扫残留。
     */
    fun stop() {
        val running = process ?: return
        EngineLog.log("stopping engine")
        val rootfs = installer.rootfsDir
        val records = engineProcesses(rootfs)
        val node = records.filter { it.cmdline.contains("/opt/node") || it.cmdline.contains("/usr/local/bin/dsh") }
        node.forEach { runCatching { Os.kill(it.pid, OsConstants.SIGTERM) } }
        if (!waitExit(running, 5_000)) {
            records.firstOrNull { it.cmdline.contains("libproot.so") }?.let {
                runCatching { Os.kill(it.pid, OsConstants.SIGQUIT) }
            }
            if (!waitExit(running, 2_000)) runCatching { running.destroyForcibly() }
        }
        // 仍属于本 rootfs 的 tracee 一并清掉（node 可能被 init 收养）。
        engineProcesses(rootfs).forEach { record ->
            runCatching { Os.kill(record.pid, OsConstants.SIGQUIT) }
        }
        runCatching { running.waitFor() }
        process = null
        runCatching { File(rootfs, "tmp/dsh-web.pid").delete() }
    }


    private data class EngineProcess(val pid: Int, val cmdline: String)

    /**
     * 启动前清掉上一次的残留引擎进程。
     *
     * App 被系统杀掉时走不到 [stop]，proot 与 node 会变成孤儿继续跑着，
     * 还握着 `~/.dsh/bot/state.lock` —— 于是新起的引擎只能只读，
     * 用户看到「The Bot team … is open in another dsh」。
     * 那个 pid 其实就是上次的自己。
     */
    private fun clearOrphanEngines(rootfs: File) {
        val orphans = engineProcesses(rootfs)
        if (orphans.isEmpty()) return
        EngineLog.log("clearing ${orphans.size} orphan engine process(es)")
        // 先给 node / dsh SIGTERM 让它正常退出（会释放锁）。
        orphans
            .filter { it.cmdline.contains("/opt/node") || it.cmdline.contains("/usr/local/bin/dsh") }
            .forEach { runCatching { Os.kill(it.pid, OsConstants.SIGTERM) } }
        // 等一会儿；还不走就对 proot 发 SIGQUIT（杀掉所有 tracee）。
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && engineProcesses(rootfs).isNotEmpty()) {
            Thread.sleep(100)
        }
        engineProcesses(rootfs)
            .filter { it.cmdline.contains("libproot.so") }
            .forEach { runCatching { Os.kill(it.pid, OsConstants.SIGQUIT) } }
        // 锁文件也清掉：持有者已经没了，锁留着只会挡住下一个引擎。
        runCatching { File(rootfs, "root/.dsh/bot/state.lock").delete() }
    }

    private fun engineProcesses(rootfs: File): List<EngineProcess> {
        val dir = File("/proc")
        val uid = AndroidProcess.myUid()
        return dir.listFiles()?.mapNotNull { entry ->
            val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == AndroidProcess.myPid()) return@mapNotNull null
            val cmdline = runCatching {
                File(entry, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
            }.getOrNull() ?: return@mapNotNull null
            if (!cmdline.contains(rootfs.absolutePath)) return@mapNotNull null
            val owner = runCatching {
                File(entry, "status").readLines()
                    .firstOrNull { it.startsWith("Uid:") }
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(1)
                    ?.toInt()
            }.getOrNull()
            if (owner != uid) return@mapNotNull null
            EngineProcess(pid, cmdline)
        }.orEmpty()
    }

    private fun waitExit(running: Process, millis: Long): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (running.isAlive && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        return !running.isAlive
    }

    val isRunning: Boolean
        get() = process?.isAlive == true

    /** 对齐 dsh-Go `StartupTimeline.line`：进程起点 → 请求启动 → 进程拉起 → 开始监听。 */
    private fun startupLine(requested: Long, spawned: Long, ready: Long): String {
        val processStart = runCatching { AndroidProcess.getStartElapsedRealtime() }.getOrNull()
        val parts = mutableListOf<String>()
        if (processStart != null && processStart in 1..requested && requested - processStart < 30_000) {
            parts += "app ${requested - processStart} ms"
        }
        parts += "prepare ${spawned - requested} ms"
        parts += "dsh ${ready - spawned} ms"
        return parts.joinToString(", ")
    }

    /** 从日志末尾取回上一次的 token / 端口（只看最后 64KB，日志可能很长）。 */
    private fun lastEndpoint(): RuntimeEndpoint? {
        val log = File(appContext.filesDir, "engine.log").takeIf { it.isFile } ?: return null
        val text = runCatching {
            log.inputStream().use { input ->
                val skip = (log.length() - 64 * 1024).coerceAtLeast(0)
                if (skip > 0) input.skip(skip)
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull() ?: return null
        return text.lineSequence().toList().asReversed()
            .firstNotNullOfOrNull { ProotCommand.parseToken(it) }
    }

    private fun listening(baseUrl: String): Boolean = runCatching {
        val url = java.net.URL(baseUrl)
        val port = if (url.port > 0) url.port else 80
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 400)
        }
        true
    }.getOrDefault(false)

    private fun appStamp(): Long = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    private fun phoneControlEnabled(): Boolean =
        appContext.getSharedPreferences("dshm_settings", Context.MODE_PRIVATE)
            .getBoolean("phone_control_enabled", false)

    private fun phoneControlOwner(): String? =
        appContext.getSharedPreferences("dshm_settings", Context.MODE_PRIVATE)
            .getString("phone_control_bot_id", null)

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
        const val PREFS = "runtime-launch"
        const val KEY_APK_STAMP = "apk-stamp"
        const val KEY_RUNTIME_VERSION = "runtime-version"
        const val KEY_PHONE_CONTROL = "phone-control"
        const val KEY_PHONE_OWNER = "phone-owner"
    }
}

/** 把 `/sdcard` 插到 `env` 之前；proot 的 `-b` 必须在解释器之前。 */
internal fun addPhoneStorageBind(argv: MutableList<String>) {
    val envIndex = argv.indexOf("/usr/bin/env")
    if (envIndex > 0) argv.addAll(envIndex, listOf("-b", "/sdcard"))
}


