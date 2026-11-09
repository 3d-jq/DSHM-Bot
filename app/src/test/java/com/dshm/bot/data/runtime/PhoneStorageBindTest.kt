package com.dshm.bot.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 访问手机文件开关只影响 proot 的 bind 参数，且位置必须在 env 之前。 */
class PhoneStorageBindTest {

    @Test
    fun insertsBindBeforeEnv() {
        val argv = mutableListOf("proot", "--rootfs=/x", "/usr/bin/env", "-i", "/bin/sh")
        addPhoneStorageBind(argv)
        assertEquals(listOf("proot", "--rootfs=/x", "-b", "/sdcard", "/usr/bin/env", "-i", "/bin/sh"), argv)
    }

    @Test
    fun noEnvLeavesArgvUntouched() {
        val argv = mutableListOf("proot", "--rootfs=/x", "/bin/sh")
        val before = argv.toList()
        addPhoneStorageBind(argv)
        assertEquals(before, argv)
    }

    @Test
    fun bindIsNotDuplicatedInTemplate() {
        val argv = mutableListOf("proot", "-b", "/dev", "/usr/bin/env", "-i", "/bin/sh")
        addPhoneStorageBind(argv)
        assertTrue(argv.count { it == "/sdcard" } == 1)
    }
}
