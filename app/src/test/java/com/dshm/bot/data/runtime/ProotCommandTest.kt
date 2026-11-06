package com.dshm.bot.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotCommandTest {

    private val placeholders = mapOf(
        "{NATIVE_LIB_DIR}" to "/data/app/lib",
        "{ROOTFS}" to "/data/user/0/app/files/runtime/rootfs",
        "{TMP}" to "/data/user/0/app/cache/proot-tmp",
        "{PORT}" to "3080",
    )

    @Test
    fun expandReplacesEveryPlaceholder() {
        val out = ProotCommand.expand("{NATIVE_LIB_DIR}/libproot.so --rootfs={ROOTFS} --port {PORT}", placeholders)
        assertEquals("/data/app/lib/libproot.so --rootfs=/data/user/0/app/files/runtime/rootfs --port 3080", out)
    }

    @Test
    fun guestEnvPlaceholderExpandsToZeroOrMoreArgs() {
        val template = listOf("proot", "{GUEST_ENV}", "dsh", "web")
        assertEquals(listOf("proot", "dsh", "web"), ProotCommand.buildArgv(template, placeholders, emptyList()))
        assertEquals(
            listOf("proot", "TZ=Asia/Shanghai", "DEEPSEEK_API_KEY=k", "dsh", "web"),
            ProotCommand.buildArgv(template, placeholders, listOf("TZ=Asia/Shanghai", "DEEPSEEK_API_KEY=k")),
        )
    }

    @Test
    fun envValuesAreExpanded() {
        val env = ProotCommand.buildEnv(
            mapOf("PROOT_TMP_DIR" to "{TMP}", "PROOT_L2S_DIR" to "{ROOTFS}/root/.l2s"),
            placeholders,
        )
        assertEquals("/data/user/0/app/cache/proot-tmp", env["PROOT_TMP_DIR"])
        assertEquals("/data/user/0/app/files/runtime/rootfs/root/.l2s", env["PROOT_L2S_DIR"])
    }

    @Test
    fun parseTokenFromReadyLine() {
        val line = "dsh web: http://127.0.0.1:3080/?token=AbC-123_xyz"
        val ready = ProotCommand.parseToken(line)
        assertEquals("http://127.0.0.1:3080/", ready?.baseUrl)
        assertEquals("AbC-123_xyz", ready?.token)
    }

    @Test
    fun ignoresUnrelatedLines() {
        assertNull(ProotCommand.parseToken("starting proot…"))
        assertTrue(ProotCommand.parseToken("dsh web: http://example.com/") == null)
    }

    @Test
    fun unknownPlaceholdersStayVerbatim() {
        assertEquals(
            "/data/user/0/app/files/runtime/rootfs/{UNKNOWN}",
            ProotCommand.expand("{ROOTFS}/{UNKNOWN}", placeholders),
        )
    }

    @Test
    fun buildArgvExpandsPlaceholdersInsideEachArgument() {
        val argv = ProotCommand.buildArgv(
            listOf("proot", "--rootfs={ROOTFS}", "--port={PORT}", "{GUEST_ENV}", "dsh"),
            placeholders,
            listOf("A=1"),
        )
        assertEquals(
            listOf(
                "proot",
                "--rootfs=/data/user/0/app/files/runtime/rootfs",
                "--port=3080",
                "A=1",
                "dsh",
            ),
            argv,
        )
    }

    @Test
    fun parseTokenTrimsWhitespaceButNotLogPrefix() {
        val ready = ProotCommand.parseToken("  dsh web: http://127.0.0.1:3080/?token=AbC_1  ")
        assertEquals("http://127.0.0.1:3080/", ready?.baseUrl)
        assertEquals("AbC_1", ready?.token)
        assertNull(ProotCommand.parseToken("INFO dsh web: http://127.0.0.1:3080/?token=AbC_1"))
    }

    @Test
    fun parseTokenRejectsMalformedReadyLines() {
        assertNull(ProotCommand.parseToken(""))
        assertNull(ProotCommand.parseToken("dsh web: http://127.0.0.1:3080/"))
        assertNull(ProotCommand.parseToken("dsh web: http://127.0.0.1:3080/?token="))
        assertNull(ProotCommand.parseToken("dsh web: https://example.com/?token=abc"))
    }
}
