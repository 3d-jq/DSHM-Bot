package com.dshm.bot.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件市场的纯逻辑：市场索引格式 1 的解析、registries 的解析、安装结果的解析。
 *
 * 对齐 dsh-Go `packages/dsh_client/lib/src/plugins_api.dart` 的
 * `parseMarketIndex` / `PluginRegistries` / `PluginOperationResult`。
 */
class PluginMarketTest {

    // ------------------------------------------------------------------
    // 市场索引格式 1
    // ------------------------------------------------------------------

    private fun validIndex(): String {
        return """
        {
          "format": 1,
          "title": {"en": "Plugin market", "zh": "插件市场"},
          "description": {"en": "All plugins", "zh": "全部插件"},
          "metadata": {
            "categories": {
              "tools": {"title": {"en": "Tools", "zh": "工具"}},
              "games": {"title": {"en": "Games"}}
            },
            "labels": {
              "featured": {"title": {"en": "Featured"}},
              "danger": {"title": {"en": "Risky"}}
            },
            "sortKeys": {
              "stars": {"title": {"en": "Stars"}, "order": "desc"},
              "name": {"title": {"en": "Name"}, "order": "asc"}
            }
          },
          "plugins": [
            {
              "name": "@dsh-go/dsh-weather",
              "title": {"zh": "天气", "en": "Weather"},
              "description": {"zh": "查天气", "en": "Weather lookup"},
              "icon": "https://example.com/weather.png",
              "author": "dsh-go",
              "homepage": "https://example.com/weather",
              "repository": {"url": "https://github.com/dsh-go/weather", "directory": "packages/weather"},
              "metadata": {
                "category": "tools",
                "keywords": ["weather", "天气"],
                "labels": ["featured"],
                "sortKeys": {"stars": 42}
              },
              "versions": [
                {
                  "version": "1.2.0",
                  "publishTimestamp": 1730000000,
                  "source": {"type": "npm", "registry": "https://registry.npmjs.org/"},
                  "engines": {"dsh": ">=0.2.1"}
                },
                {
                  "version": "1.1.0",
                  "publishTimestamp": 1720000000,
                  "source": {"type": "npm"}
                }
              ]
            },
            {
              "name": "dsh-writer",
              "title": "Writer",
              "description": "Writes novels",
              "metadata": {
                "category": "tools",
                "labels": ["danger"],
                "sortKeys": {"stars": 7}
              },
              "versions": [
                {
                  "version": "0.1.0",
                  "publishTimestamp": 1730000000,
                  "source": {"type": "git", "url": "https://github.com/dsh-go/writer", "commit": "0123456789abcdef0123456789abcdef01234567", "path": "packages/writer"}
                }
              ]
            }
          ]
        }
        """.trimIndent()
    }

    @Test
    fun indexParsesPluginsAndMetadata() {
        val index = MarketIndexParser.parse(validIndex(), "https://example.com/index.json")

        assertEquals(2, index.plugins.size)
        assertEquals("https://example.com/index.json", index.url)

        val weather = index.plugins.first()
        assertEquals("@dsh-go/dsh-weather", weather.name)
        assertEquals("天气", weather.title?.resolve("zh"))
        assertEquals("查天气", weather.description?.resolve("zh"))
        assertEquals("https://example.com/weather.png", weather.icon)
        assertEquals("dsh-go", weather.author)
        assertEquals("https://example.com/weather", weather.homepage)
        assertEquals("https://github.com/dsh-go/weather", weather.repositoryUrl)
        assertEquals("packages/weather", weather.repositoryDirectory)
        assertEquals("tools", weather.category)
        assertEquals(listOf("weather", "天气"), weather.keywords)
        assertEquals(listOf("featured"), weather.labels)
        assertEquals(42.0, weather.sortKeys.getValue("stars"), 0.001)

        // 版本按 SemVer 从新到旧。
        assertEquals(listOf("1.2.0", "1.1.0"), weather.versions.map { it.version })
        assertEquals(">=0.2.1", weather.versions.first().enginesDsh)
        assertEquals(1730000000L, weather.versions.first().publishedEpochSeconds)

        // 声明：分类 / 标签 / 排序键。
        // 只断言集合：单测用的 org.json jar 不保留对象键顺序（Android 的 JSONObject 保留，
        // 真机上声明顺序与索引里一致）。
        assertEquals(setOf("tools", "games"), index.categories.map { it.id }.toSet())
        assertEquals("工具", index.categories.first { it.id == "tools" }.title.resolve("zh"))
        assertEquals(setOf("featured", "danger"), index.labels.map { it.id }.toSet())
        assertEquals(setOf("stars", "name"), index.sortKeys.map { it.id }.toSet())
        assertTrue(index.sortKeys.first { it.id == "stars" }.descending)
        assertFalse(index.sortKeys.first { it.id == "name" }.descending)

        // 合法索引没有丢东西。
        assertTrue(index.issues.isEmpty())
    }

    @Test
    fun indexTitleFallsBackToHostWhenMissing() {
        val index = MarketIndexParser.parse(
            """{"format": 1, "plugins": []}""",
            "https://market.example.com/index.json",
        )
        assertEquals("market.example.com", index.title.resolve("zh"))
        assertTrue(index.plugins.isEmpty())
    }

    @Test
    fun indexRejectsDocumentsThatAreNotFormatOne() {
        // 不是 JSON。
        val notJson = runCatching { MarketIndexParser.parse("not json at all", "https://x/i.json") }
        assertTrue(notJson.exceptionOrNull() is MarketIndexException)

        // format 不对。
        val wrongFormat = runCatching {
            MarketIndexParser.parse("""{"format": 2, "plugins": []}""", "https://x/i.json")
        }
        val formatError = wrongFormat.exceptionOrNull() as? MarketIndexException
        assertNotNull(formatError)
        assertEquals("$.format", formatError?.path)

        // 没有插件列表。
        val noPlugins = runCatching {
            MarketIndexParser.parse("""{"format": 1}""", "https://x/i.json")
        }
        val pluginsError = noPlugins.exceptionOrNull() as? MarketIndexException
        assertNotNull(pluginsError)
        assertEquals("$.plugins", pluginsError?.path)
    }

    @Test
    fun indexSkipsBrokenPluginsAndRecordsIssues() {
        val text = """
        {
          "format": 1,
          "plugins": [
            {"name": "Bad Name", "versions": []},
            {"name": "no-versions", "versions": []},
            {"name": "good", "versions": [
              {"version": "1.0.0", "publishTimestamp": 1730000000, "source": {"type": "npm"}}
            ]},
            {"name": "good", "versions": [
              {"version": "2.0.0", "publishTimestamp": 1730000000, "source": {"type": "npm"}}
            ]}
          ]
        }
        """.trimIndent()

        val index = MarketIndexParser.parse(text, "https://x/i.json")

        // 只有合法的那个留下。
        assertEquals(listOf("good"), index.plugins.map { it.name })
        // 包名不合法、没有合法版本、名字重复 —— 三种都被记下来。
        assertEquals(3, index.issues.size)
        assertTrue(index.issues.any { it.path.endsWith(".name") && it.reason.contains("npm") })
        assertTrue(index.issues.any { it.path.endsWith(".versions") && it.reason.contains("合法") })
    }

    @Test
    fun indexDropsInvalidVersionsButKeepsThePlugin() {
        val text = """
        {
          "format": 1,
          "plugins": [
            {"name": "pkg", "versions": [
              {"version": "not-semver", "publishTimestamp": 1730000000, "source": {"type": "npm"}},
              {"version": "1.0.0", "publishTimestamp": -1, "source": {"type": "npm"}},
              {"version": "1.0.0", "publishTimestamp": 1730000000, "source": {"type": "unknown"}},
              {"version": "1.0.0", "publishTimestamp": 1730000000, "source": {"type": "npm"}}
            ]}
          ]
        }
        """.trimIndent()

        val index = MarketIndexParser.parse(text, "https://x/i.json")

        assertEquals(1, index.plugins.size)
        assertEquals(listOf("1.0.0"), index.plugins.first().versions.map { it.version })
        assertEquals(3, index.issues.size)
    }

    @Test
    fun indexIgnoresUndeclaredCategoryAndLabels() {
        val text = """
        {
          "format": 1,
          "metadata": {"categories": {"tools": {"title": {"en": "Tools"}}}},
          "plugins": [
            {"name": "pkg",
             "metadata": {"category": "undeclared", "labels": ["undeclared"], "sortKeys": {"nope": 1}},
             "versions": [
              {"version": "1.0.0", "publishTimestamp": 1730000000, "source": {"type": "npm"}}
             ]}
          ]
        }
        """.trimIndent()

        val index = MarketIndexParser.parse(text, "https://x/i.json")
        val plugin = index.plugins.first()

        assertNull(plugin.category)
        assertTrue(plugin.labels.isEmpty())
        assertTrue(plugin.sortKeys.isEmpty())
    }

    @Test
    fun indexReadsDangerLabelAsRiskMarker() {
        val index = MarketIndexParser.parse(validIndex(), "https://x/i.json")
        val writer = index.plugins.first { it.name == "dsh-writer" }

        // 索引里带 danger 标签的插件被标成有风险。
        assertTrue(writer.dangerous)
        assertFalse(index.plugins.first().dangerous)
    }

    @Test
    fun installSpecIsPinnedVersionForNpmAndCommitForGit() {
        val index = MarketIndexParser.parse(validIndex(), "https://x/i.json")
        val weather = index.plugins.first { it.name == "@dsh-go/dsh-weather" }
        val writer = index.plugins.first { it.name == "dsh-writer" }

        assertEquals("@dsh-go/dsh-weather@1.2.0", weather.installSpec())
        assertEquals("https://registry.npmjs.org/", weather.registry)

        assertEquals(
            "git+https://github.com/dsh-go/writer.git#0123456789abcdef0123456789abcdef01234567&path:/packages/writer",
            writer.installSpec(),
        )
        assertNull(writer.registry)
    }

    @Test
    fun semverComparesByPrecedence() {
        assertTrue(MarketSemver.compare("1.0.0", "1.0.1") < 0)
        assertTrue(MarketSemver.compare("1.2.0", "1.10.0") < 0)
        assertTrue(MarketSemver.compare("1.0.0", "1.0.0-rc.1") > 0)
        assertTrue(MarketSemver.compare("1.0.0-alpha.1", "1.0.0-alpha.2") < 0)
        assertTrue(MarketSemver.compare("1.0.0-alpha", "1.0.0-beta") < 0)
        assertEquals(0, MarketSemver.compare("1.0.0", "1.0.0"))
        assertTrue(MarketSemver.isExact("1.0.0"))
        assertFalse(MarketSemver.isExact("1.0"))
        assertFalse(MarketSemver.isExact("v1.0.0"))
    }

    // ------------------------------------------------------------------
    // registries
    // ------------------------------------------------------------------

    @Test
    fun registriesParseAllFields() {
        val registries = PluginRegistries.fromJson(
            JSONObject(
                """
                {
                  "registry": "https://registry.npmmirror.com/",
                  "fallbackRegistries": ["https://registry.npmjs.org/"],
                  "resolved": "https://registry.npmmirror.com/"
                }
                """.trimIndent(),
            ),
        )

        assertEquals("https://registry.npmmirror.com/", registries.registry)
        assertEquals(listOf("https://registry.npmjs.org/"), registries.fallbackRegistries)
        assertEquals("https://registry.npmmirror.com/", registries.resolved)
    }

    @Test
    fun registriesToleratesMissingFields() {
        val registries = PluginRegistries.fromJson(JSONObject())
        assertNull(registries.registry)
        assertNull(registries.resolved)
        assertTrue(registries.fallbackRegistries.isEmpty())

        val nulled = PluginRegistries.fromJson(null)
        assertNull(nulled.registry)
        assertTrue(nulled.fallbackRegistries.isEmpty())
    }

    @Test
    fun indexCandidatesPreferResolvedAndKeepOrder() {
        val candidates = MarketIndexParser.marketIndexCandidates(
            resolved = "https://a/index.json",
            settingsIndexes = listOf("https://a/index.json", "https://b/index.json"),
        )
        assertEquals(listOf("https://a/index.json", "https://b/index.json"), candidates)

        // 没有 resolved 时只剩设置里的。
        assertEquals(
            listOf("https://b/index.json"),
            MarketIndexParser.marketIndexCandidates(null, listOf("https://b/index.json")),
        )
        assertTrue(MarketIndexParser.marketIndexCandidates(null, emptyList()).isEmpty())
    }

    // ------------------------------------------------------------------
    // 安装 / 卸载结果
    // ------------------------------------------------------------------

    @Test
    fun operationResultParsesSuccess() {
        val result = PluginOperationResult.fromJson(
            JSONObject(
                """
                {
                  "changed": true,
                  "application": "applied",
                  "stage": "install",
                  "target": "dsh-writer",
                  "bundle": "dsh-writer",
                  "version": "0.1.0",
                  "enabled": true,
                  "warnings": ["peer dependency missing"]
                }
                """.trimIndent(),
            ),
        )

        assertTrue(result.succeeded)
        assertFalse(result.needsRestart)
        assertEquals(PluginApplication.APPLIED, result.application)
        assertEquals("dsh-writer", result.target)
        assertEquals("dsh-writer", result.bundle)
        assertEquals("0.1.0", result.version)
        assertEquals(true, result.enabled)
        assertEquals(listOf("peer dependency missing"), result.warnings)
    }

    @Test
    fun operationResultParsesRestartRequired() {
        val result = PluginOperationResult.fromJson(
            JSONObject("""{"application": "restart-required", "restartReason": "no-hmr"}"""),
        )
        assertTrue(result.succeeded)
        assertTrue(result.needsRestart)
        assertEquals("no-hmr", result.restartReason)
    }

    @Test
    fun operationResultParsesFailureWithDiagnostic() {
        val result = PluginOperationResult.fromJson(
            JSONObject(
                """
                {
                  "application": "failed",
                  "stage": "install",
                  "error": {"code": "not-found", "diagnostic": "404 from registry"},
                  "packageResult": {"exitCode": 1, "kind": "not-found", "output": "boom", "timedOut": false}
                }
                """.trimIndent(),
            ),
        )

        assertFalse(result.succeeded)
        assertEquals("not-found", result.error?.code)
        assertEquals("404 from registry", result.error?.diagnostic)
        assertEquals("not-found", result.failureKind)
        assertEquals(1, result.packageResult?.exitCode)
        assertTrue(pluginFailureText(result).contains("404 from registry"))
    }

    @Test
    fun operationResultParsesPendingBuilds() {
        val result = PluginOperationResult.fromJson(
            JSONObject(
                """
                {
                  "application": "failed",
                  "pendingBuilds": ["esbuild", "sharp"],
                  "approvedBuilds": []
                }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("esbuild", "sharp"), result.pendingBuilds)
        assertTrue(result.approvedBuilds.isEmpty())
    }

    @Test
    fun operationResultParsesIncompatibleList() {
        val result = PluginOperationResult.fromJson(
            JSONObject(
                """
                {
                  "application": "failed",
                  "error": {
                    "code": "incompatible-version",
                    "incompatible": [
                      {"name": "dsh-old", "version": "1.0.0", "runtimeVersion": "0.2.1",
                       "requirements": {"engines.dsh": ">=0.3.0"}}
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )
        val incompatible = result.error?.incompatible.orEmpty()
        assertEquals(1, incompatible.size)
        assertEquals("dsh-old", incompatible.first().name)
        assertEquals(">=0.3.0", incompatible.first().requirements["engines.dsh"])
    }

    @Test
    fun cancelStatusParsesWireValues() {
        assertEquals(PluginCancelStatus.CANCELLED, PluginCancelStatus.parse("cancelled"))
        assertEquals(PluginCancelStatus.TOO_LATE, PluginCancelStatus.parse("too-late"))
        assertEquals(PluginCancelStatus.NOT_RUNNING, PluginCancelStatus.parse("nonsense"))
        assertEquals(PluginCancelStatus.NOT_RUNNING, PluginCancelStatus.parse(null))
    }

    @Test
    fun inspectionParsesAcceptedAndRefused() {
        val accepted = PluginInspection.fromJson(
            JSONObject("""{"kind": "registry", "name": "dsh-writer", "version": "0.1.0", "bundle": true}"""),
        ) as PluginInspection.Accepted
        assertEquals("registry", accepted.kind)
        assertEquals("dsh-writer", accepted.name)
        assertEquals(true, accepted.bundle)

        val refused = PluginInspection.fromJson(
            JSONObject("""{"status": "refused", "problem": "not-found", "reason": "404"}"""),
        ) as PluginInspection.Refused
        assertEquals("not-found", refused.problem)
        assertEquals("404", refused.reason)
    }

    @Test
    fun applicationParsesUnknownAsUnknown() {
        assertEquals(PluginApplication.APPLIED, PluginApplication.parse("applied"))
        assertEquals(PluginApplication.UNKNOWN, PluginApplication.parse("whatever"))
        assertEquals(PluginApplication.UNKNOWN, PluginApplication.parse(null))
    }
}
