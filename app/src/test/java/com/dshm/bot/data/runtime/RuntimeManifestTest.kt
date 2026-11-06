package com.dshm.bot.data.runtime

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 运行时清单是安装引擎的唯一依据：字段缺失、占位符、entries 统计都要有兜底。 */
class RuntimeManifestTest {

    private fun fullJson() = """
        {
          "runtimeVersion": "0.2.1-rc.4",
          "abi": "arm64-v8a",
          "rootfs": "runtime/rootfs.tar.xz",
          "rootfsBytes": 123456,
          "rootfsSha256": "abcdef0123",
          "uncompressedBytes": 999999,
          "tarBytes": 888888,
          "dshVersion": "0.2.1",
          "nodeVersion": "22.14.0",
          "entries": {"dir": 10, "file": 20, "symlink": 3},
          "launch": {
            "argv": ["{NATIVE_LIB_DIR}/libproot.so", "--rootfs={ROOTFS}", "{GUEST_ENV}", "dsh", "web"],
            "env": {"PROOT_TMP_DIR": "{TMP}", "HOME": "/root"}
          }
        }
    """.trimIndent()

    @Test
    fun parsesEveryFieldIncludingLaunchAndEntries() {
        val manifest = RuntimeManifest.parse(fullJson())
        assertEquals("0.2.1-rc.4", manifest.runtimeVersion)
        assertEquals("arm64-v8a", manifest.abi)
        assertEquals("runtime/rootfs.tar.xz", manifest.rootfsAsset)
        assertEquals(123456L, manifest.rootfsBytes)
        assertEquals("abcdef0123", manifest.rootfsSha256)
        assertEquals(999999L, manifest.uncompressedBytes)
        assertEquals(888888L, manifest.tarBytes)
        assertEquals("0.2.1", manifest.dshVersion)
        assertEquals("22.14.0", manifest.nodeVersion)
        assertEquals(33, manifest.entriesTotal)
        assertEquals(
            listOf("{NATIVE_LIB_DIR}/libproot.so", "--rootfs={ROOTFS}", "{GUEST_ENV}", "dsh", "web"),
            manifest.launchArgv,
        )
        assertEquals(mapOf("PROOT_TMP_DIR" to "{TMP}", "HOME" to "/root"), manifest.launchEnv)
    }

    @Test
    fun minimalManifestFallsBackToSafeDefaults() {
        val manifest = RuntimeManifest.parse("""{"runtimeVersion":"1","rootfs":"rootfs.tar.xz"}""")
        assertEquals("1", manifest.runtimeVersion)
        assertEquals("rootfs.tar.xz", manifest.rootfsAsset)
        assertEquals("", manifest.abi)
        assertEquals(-1L, manifest.rootfsBytes)
        assertNull(manifest.rootfsSha256)
        assertEquals(-1L, manifest.uncompressedBytes)
        assertEquals(-1L, manifest.tarBytes)
        assertEquals("", manifest.dshVersion)
        assertEquals("", manifest.nodeVersion)
        assertTrue(manifest.launchArgv.isEmpty())
        assertTrue(manifest.launchEnv.isEmpty())
        assertEquals(0, manifest.entriesTotal)
    }

    @Test
    fun emptyShaAndPartialLaunchDoNotBreakParsing() {
        val manifest = RuntimeManifest.parse(
            """
            {
              "runtimeVersion": "1",
              "rootfs": "r.tar.xz",
              "rootfsSha256": "",
              "entries": {"dir": 2},
              "launch": {"argv": ["dsh", "web"]}
            }
            """.trimIndent(),
        )
        assertNull(manifest.rootfsSha256)
        assertEquals(listOf("dsh", "web"), manifest.launchArgv)
        assertTrue(manifest.launchEnv.isEmpty())
        assertEquals(2, manifest.entriesTotal)
    }

    @Test
    fun missingRequiredFieldsAreRejected() {
        listOf("{}", """{"rootfs":"r.tar.xz"}""", """{"runtimeVersion":"1"}""").forEach { raw ->
            try {
                RuntimeManifest.parse(raw)
                fail("缺少必填字段却解析成功：$raw")
            } catch (expected: JSONException) {
                // getString 会在缺 runtimeVersion / rootfs 时抛出，这是期望行为。
            }
        }
    }

    @Test
    fun malformedJsonIsRejected() {
        try {
            RuntimeManifest.parse("not-json")
            fail("非法 JSON 却解析成功")
        } catch (expected: JSONException) {
            // 回归：早期用 optString 全兜底会把损坏的清单当成空清单装进引擎。
        }
    }
}
