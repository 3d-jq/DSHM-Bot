package com.dshm.bot.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.ConnectorsView
import com.dshm.bot.data.remote.ConnectorRow
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.DshRemoteApi
import com.dshm.bot.data.remote.RemoteDshApi
import com.dshm.bot.data.remote.parseConnectorsView
import com.dshm.bot.data.remote.parseMailView
import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** DS Bot 的密钥（值永远不出主机，这里只有名字/用途/范围）。 */
data class TeamSecret(
    val name: String,
    val purpose: String?,
    /** `"all"` 或 Bot id 列表。 */
    val scope: String,
    val updatedAt: Long,
    val lastUsedAt: Long?,
)


data class TeamMember(val id: String, val name: String)

/** 能力库里的一项（MCP 服务 / 技能 / 组合包）。 */
data class TeamCapability(
    val name: String,
    val kind: String,
    val description: String?,
    val transport: String?,
    val approved: Boolean,
)

/** 团队用量里的一个归属（某个 Bot / 群聊 / 其他）。 */
data class TeamUsageOwner(
    val key: String,
    val kind: String,
    val name: String,
)

/** `usage` 的一行：一小时里一个归属用一个模型花的量。 */
data class TeamUsageRow(
    /** `2026-11-08T14` 这样的小时串。 */
    val hour: String,
    val owner: Int,
    val model: Int,
    val input: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val output: Long,
    val calls: Long,
) {
    val tokens: Long get() = input + cacheRead + cacheWrite + output
}

data class TeamUsageModel(val key: String, val name: String, val provider: String?)

data class TeamSettingsUi(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val secrets: List<TeamSecret> = emptyList(),
    /** 0.3.6 的连接器目录 + 邮箱；`connectors` 里不含邮箱（邮箱在 `mail`）。 */
    val connectorsView: ConnectorsView = ConnectorsView.EMPTY,
    val members: List<TeamMember> = emptyList(),
    val capabilities: List<TeamCapability> = emptyList(),
    /** Bot id → 已挂上的能力名。 */
    val attached: Map<String, List<String>> = emptyMap(),
    /** Bot id → 能力名 → 运行状态（ready / connecting / needs-approval / error）。 */
    val statuses: Map<String, Map<String, String>> = emptyMap(),
    val usageOwners: List<TeamUsageOwner> = emptyList(),
    val usageRows: List<TeamUsageRow> = emptyList(),
    val usageModels: List<TeamUsageModel> = emptyList(),
    val usageNow: String = "",
    val usageScanning: Boolean = false,
)

/**
 * DS Bot 的团队设置：`state` 里的 `secrets` / `connectors`，
 * 写回走 `secret-set|update|delete` 与 `connector-connect|disconnect|retry`。
 */
class TeamSettingsViewModel(private val endpoint: RuntimeEndpoint?) : ViewModel() {

    private val client: DshRemoteApi? = endpoint?.let { RemoteDshApi(DshClients.of(it)) }
    private val _ui = MutableStateFlow(TeamSettingsUi(loading = client != null))
    val ui: StateFlow<TeamSettingsUi> = _ui.asStateFlow()

    init {
        if (client == null) {
            _ui.update { it.copy(loading = false, error = "本机引擎未运行") }
        } else {
            load()
        }
    }

    fun load() {
        val connection = client ?: return
        viewModelScope.launch {
            runCatching { connection.bot("state") }
                .onSuccess { applyState(it) }
                .onFailure { error ->
                    _ui.update { it.copy(loading = false, error = error.message ?: "读取失败") }
                }
        }
    }

    private fun applyState(state: JSONObject) {
        val secrets = state.optJSONArray("secrets")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val name = item.optString("name")
                    if (name.isEmpty()) continue
                    add(
                        TeamSecret(
                            name = name,
                            purpose = item.optString("purpose").takeIf { it.isNotEmpty() },
                            scope = item.opt("scope")?.toString().orEmpty(),
                            updatedAt = item.optLong("updatedAt", 0L),
                            lastUsedAt = item.optLong("lastUsedAt", 0L).takeIf { it > 0L },
                        ),
                    )
                }
            }
        }.orEmpty()
        // 0.3.6 的 `state.connectors` 就是 `connectorsView()` 对象 `{connectors, mail}`
        // （不是配置字典，也不是旧的裸数组）—— 直接解析它，省一次 RPC。
        val connectorsView = parseConnectorsView(state.opt("connectors"))
        val members = state.optJSONArray("bots")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isEmpty()) continue
                    add(TeamMember(id, item.optString("name").ifEmpty { id }))
                }
            }
        }.orEmpty()
        val attached = state.optJSONArray("bots")?.let { array ->
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isEmpty()) continue
                    val names = item.optJSONArray("capabilities")?.let { list ->
                        (0 until list.length()).mapNotNull { at ->
                            list.optString(at).takeIf { it.isNotEmpty() }
                        }
                    }.orEmpty()
                    put(id, names)
                }
            }
        }.orEmpty()
        val capabilities = state.optJSONArray("capabilities")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val name = item.optString("name")
                    if (name.isEmpty()) continue
                    add(
                        TeamCapability(
                            name = name,
                            kind = item.optString("kind").ifEmpty { "mcp" },
                            description = item.optString("description").takeIf { it.isNotEmpty() },
                            transport = item.optString("transport").takeIf { it.isNotEmpty() },
                            approved = item.optBoolean("approved", true),
                        ),
                    )
                }
            }
        }.orEmpty()
        val statuses = state.optJSONObject("capabilityStatuses")?.let { json ->
            buildMap {
                json.keys().forEach { botId ->
                    val item = json.optJSONObject(botId) ?: return@forEach
                    val inner = buildMap {
                        item.keys().forEach { name ->
                            val stateName = item.optJSONObject(name)?.optString("state").orEmpty()
                            if (stateName.isNotEmpty()) put(name, stateName)
                        }
                    }
                    if (inner.isNotEmpty()) put(botId, inner)
                }
            }
        }.orEmpty()
        _ui.update {
            it.copy(
                loading = false,
                error = null,
                secrets = secrets,
                connectorsView = connectorsView,
                members = members,
                capabilities = capabilities,
                attached = attached,
                statuses = statuses,
            )
        }
    }

    /** 团队用量：`usage` 按小时、按归属给行，这里按归属合计。 */
    fun loadUsage() {
        val connection = client ?: return
        viewModelScope.launch {
            runCatching {
                connection.bot(
                    "usage",
                    JSONObject().put("timeZone", java.util.TimeZone.getDefault().id),
                )
            }.onSuccess { applyUsage(it) }
        }
    }

    private fun applyUsage(view: JSONObject) {
        val owners = view.optJSONArray("owners") ?: return
        val rows = view.optJSONArray("rows") ?: return
        val ownerList = buildList {
            for (index in 0 until owners.length()) {
                val owner = owners.optJSONObject(index) ?: continue
                add(
                    TeamUsageOwner(
                        key = owner.optString("key"),
                        kind = owner.optString("kind"),
                        name = owner.optString("name").ifEmpty { owner.optString("id") },
                    ),
                )
            }
        }
        val models = view.optJSONArray("models")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        TeamUsageModel(
                            key = item.optString("key"),
                            name = item.optString("name").ifEmpty { item.optString("model") },
                            provider = item.optString("providerName").ifEmpty { item.optString("provider") }
                                .takeIf { it.isNotEmpty() },
                        ),
                    )
                }
            }
        }.orEmpty()
        val rowList = buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONArray(index) ?: continue
                add(
                    TeamUsageRow(
                        hour = row.optString(0),
                        owner = row.optInt(1, -1),
                        model = row.optInt(2, -1),
                        calls = row.optLong(8, 0L),
                        input = row.optLong(4, 0L),
                        cacheRead = row.optLong(5, 0L),
                        cacheWrite = row.optLong(6, 0L),
                        output = row.optLong(7, 0L),
                    ),
                )
            }
        }
        _ui.update {
            it.copy(
                usageOwners = ownerList,
                usageModels = models,
                usageRows = rowList,
                usageNow = view.optString("now"),
                usageScanning = view.optBoolean("scanning", false),
            )
        }
    }

    /** 把一项能力挂到某个 Bot 上 / 摘下来：`bot-capability {botId, name, attached}`。 */
    fun attachCapability(botId: String, name: String, attached: Boolean, onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        run(
            connection,
            "bot-capability",
            JSONObject().put("botId", botId).put("name", name).put("attached", attached),
            onDone,
        )
    }

    fun approveCapability(name: String, onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        run(connection, "capability-approve", JSONObject().put("name", name), onDone)
    }

    fun deleteCapability(name: String, onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        run(connection, "capability-delete", JSONObject().put("name", name), onDone)
    }

    /** 新增一条密钥：`secret-set {name, value, purpose, scope}`。 */
    fun addSecret(
        name: String,
        value: String,
        purpose: String,
        scope: List<String>?,
        onDone: (String?) -> Unit = {},
    ) {
        val connection = client ?: return
        if (name.isBlank() || value.isBlank()) {
            onDone("名字和值都要填")
            return
        }
        val body = JSONObject().put("name", name.trim()).put("value", value)
        if (purpose.isNotBlank()) body.put("purpose", purpose.trim())
        body.put("scope", scopeJson(scope))
        run(connection, "secret-set", body, onDone)
    }

    /**
     * 改一条密钥：`secret-update {name, purpose?, scope?}`。
     *
     * 换值走 `secret-set`（同名的会覆盖），DS Bot 的面板也是这么做的。
     */
    fun updateSecret(
        secret: TeamSecret,
        purpose: String,
        scope: List<String>?,
        newValue: String,
        onDone: (String?) -> Unit = {},
    ) {
        val connection = client ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val error = runCatching {
                if (newValue.isNotBlank()) {
                    connection.bot(
                        "secret-set",
                        JSONObject()
                            .put("name", secret.name)
                            .put("value", newValue)
                            .put("purpose", purpose.trim())
                            .put("scope", scopeJson(scope)),
                    )
                } else {
                    connection.bot(
                        "secret-update",
                        JSONObject()
                            .put("name", secret.name)
                            .put("purpose", purpose.trim())
                            .put("scope", scopeJson(scope)),
                    )
                }
            }.exceptionOrNull()?.message
            _ui.update { it.copy(busy = false) }
            if (error == null) load()
            onDone(error)
        }
    }

    fun deleteSecret(name: String, onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        run(connection, "secret-delete", JSONObject().put("name", name), onDone)
    }

    /** 非连接器的写操作（密钥 / 能力）：成功后重读 state。 */
    private fun run(
        connection: DshRemoteApi,
        endpoint: String,
        body: JSONObject,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val result = runCatching { connection.botValue(endpoint, body) }
            val error = result.exceptionOrNull()?.message
            _ui.update { it.copy(busy = false, error = error) }
            if (error == null) load()
            onDone(error)
        }
    }

    // ---- 连接器 0.3.6 --------------------------------------------------------
    // 所有 connector-*/mail-* 端点都返回完整的 `connectorsView()` 对象，统一走 run()。

    /** OAuth 浏览器/设备码登录：宿主在 pending 里给出 url 或 userCode。 */
    fun connectorConnect(id: String, body: JSONObject = JSONObject(), onDone: (String?) -> Unit = {}) =
        runConnector("connector-connect", body.put("id", id), onDone)

    fun connectorDisconnect(id: String, onDone: (String?) -> Unit = {}) =
        runConnector("connector-disconnect", JSONObject().put("id", id), onDone)

    fun connectorRetry(id: String, onDone: (String?) -> Unit = {}) =
        runConnector("connector-retry", JSONObject().put("id", id), onDone)

    fun connectorCancel(id: String, onDone: (String?) -> Unit = {}) =
        runConnector("connector-cancel", JSONObject().put("id", id), onDone)

    /** 浏览器 OAuth 回调：粘贴跳转到的完整地址或 code。 */
    fun connectorSignInCode(id: String, input: String, onDone: (String?) -> Unit = {}) =
        runConnector("connector-sign-in-code", JSONObject().put("id", id).put("input", input.trim()), onDone)

    /** `needs-client`：填自己的 OAuth client id（密钥另存）。 */
    fun connectorClient(id: String, clientId: String, clientSecret: String, onDone: (String?) -> Unit = {}) {
        val body = JSONObject().put("id", id).put("clientId", clientId.trim())
        if (clientSecret.isNotBlank()) body.put("clientSecret", clientSecret.trim())
        runConnector("connector-client", body, onDone)
    }

    /** 只读开关 + 「哪些 Bot 用它」（空列表 = 所有 Bot）。 */
    fun connectorSet(id: String, readOnly: Boolean?, offFor: List<String>?, onDone: (String?) -> Unit = {}) {
        val body = JSONObject().put("id", id)
        if (readOnly != null) body.put("readOnly", readOnly)
        if (offFor != null) body.put("offFor", JSONArray().apply { offFor.forEach(::put) })
        runConnector("connector-set", body, onDone)
    }

    /** 自定义 MCP server：url 或 command 至少一个。 */
    fun connectorAdd(name: String, url: String?, command: String?, onDone: (String?) -> Unit = {}) {
        val body = JSONObject().put("name", name.trim())
        if (!url.isNullOrBlank()) body.put("url", url.trim())
        if (!command.isNullOrBlank()) body.put("command", command.trim())
        runConnector("connector-add", body, onDone)
    }

    fun connectorRemove(id: String, onDone: (String?) -> Unit = {}) =
        runConnector("connector-remove", JSONObject().put("id", id), onDone)

    // ---- 邮箱 ---------------------------------------------------------------

    /** 授权码 / app password 登录 IMAP 邮箱（provider 可省，按地址识别）。 */
    fun mailAccountAdd(address: String, password: String, provider: String?, onDone: (String?) -> Unit = {}) {
        val body = JSONObject().put("address", address.trim()).put("password", password.trim())
        if (!provider.isNullOrBlank()) body.put("provider", provider)
        runConnector("mail-account-add", body, onDone)
    }

    fun mailAccountRemove(id: String, onDone: (String?) -> Unit = {}) =
        runConnector("mail-account-remove", JSONObject().put("id", id), onDone)

    /** 邮箱规则：允许的收件人 / 不发草稿的 Bot。 */
    fun mailRuleSet(allowedRecipients: List<String>?, noDraftFor: List<String>?, onDone: (String?) -> Unit = {}) {
        val body = JSONObject()
        if (allowedRecipients != null) body.put("allowedRecipients", JSONArray().apply { allowedRecipients.forEach(::put) })
        if (noDraftFor != null) body.put("noDraftFor", JSONArray().apply { noDraftFor.forEach(::put) })
        runConnector("mail-rule-set", body, onDone)
    }

    private fun runConnector(endpoint: String, body: JSONObject, onDone: (String?) -> Unit) {
        val connection = client ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            val result = runCatching { connection.botValue(endpoint, body) }
            val error = result.exceptionOrNull()?.message
            if (error == null) {
                result.getOrNull()?.let { raw ->
                    if (endpoint.startsWith("mail-")) {
                        // 邮箱写端点返回裸 mailView()，不是 connectorsView()：只更新 mail，
                        // 保留连接器目录（以前用 parseConnectorsView 会把整页读空）。
                        parseMailView(raw)?.let { mail ->
                            _ui.update { it.copy(connectorsView = it.connectorsView.copy(mail = mail)) }
                        }
                    } else {
                        _ui.update { it.copy(connectorsView = parseConnectorsView(raw)) }
                    }
                }
            }
            _ui.update { it.copy(busy = false, error = error) }
            onDone(error)
        }
    }

    /** `null` = 所有 Bot；空列表也是「所有」之外的空集合，按 DS Bot 的约定发 `all`。 */
    private fun scopeJson(scope: List<String>?): Any =
        if (scope == null) "all" else JSONArray().apply { scope.forEach(::put) }

    companion object {
        fun factory(endpoint: RuntimeEndpoint?): ViewModelProvider.Factory = viewModelFactory {
            initializer { TeamSettingsViewModel(endpoint) }
        }
    }
}
