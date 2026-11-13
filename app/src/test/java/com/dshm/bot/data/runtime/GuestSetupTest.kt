package com.dshm.bot.data.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `AGENTS.md` 的写入规则。
 *
 * 这里锁两件事：
 * 1. 交付规则真的进了文件（图片/视频也要 `present`）—— 产物卡根治靠它；
 * 2. 用户自己改过的 AGENTS.md 不被覆盖（只覆盖带我们标记的）。
 */
class GuestSetupTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun agentsFile(rootfs: File) = File(rootfs, "root/.dsh/AGENTS.md")

    @Test
    fun writesDeliveryRuleOnFreshInstall() {
        val rootfs = temp.newFolder("rootfs")

        GuestSetup.apply(rootfs)

        val text = agentsFile(rootfs).readText()
        assertTrue("缺少交付规则", text.contains("# 交付文件"))
        assertTrue("没要求调用 present", text.contains("`present`"))
        assertTrue("没点名图片/视频", text.contains("图片") && text.contains("视频"))
        // 原有环境说明不能丢。
        assertTrue("环境说明丢了", text.contains("danger-full-access"))
        assertTrue("缺少托管标记", text.startsWith("<!-- dshm-bot:managed"))
    }

    @Test
    fun overwritesPreviousManagedVersion() {
        val rootfs = temp.newFolder("rootfs")
        val file = agentsFile(rootfs)
        file.parentFile!!.mkdirs()
        file.writeText("<!-- dshm-bot:managed — 删除这一行后 DSHM Bot 不再覆盖本文件 -->\n# 旧版本\n")

        GuestSetup.apply(rootfs)

        val text = file.readText()
        assertTrue("旧的自有版本没有被升级", text.contains("# 交付文件"))
        assertFalse("旧内容还在", text.contains("# 旧版本"))
    }

    @Test
    fun keepsUserOwnedFileUntouched() {
        val rootfs = temp.newFolder("rootfs")
        val file = agentsFile(rootfs)
        file.parentFile!!.mkdirs()
        val mine = "# 我自己写的规则\n- 不要动我\n"
        file.writeText(mine)

        GuestSetup.apply(rootfs)

        assertEquals("用户自己写的 AGENTS.md 被覆盖了", mine, file.readText())
    }

    @Test
    fun createsParentDirectoryWhenMissing() {
        val rootfs = temp.newFolder("rootfs")
        assertFalse(File(rootfs, "root/.dsh").exists())

        GuestSetup.apply(rootfs)

        assertTrue(agentsFile(rootfs).isFile)
    }
}
