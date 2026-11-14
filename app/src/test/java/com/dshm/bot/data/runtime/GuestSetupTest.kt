package com.dshm.bot.data.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `AGENTS.md` 的写入规则（幂等 upsert）。
 *
 * 这里锁四件事：
 * 1. 交付规则真的进了文件（图片/视频也要 `present`）—— 产物卡根治靠它；
 * 2. 我们管理的文件在规则更新时整体重写；
 * 3. 用户自己的文件**追加**规则块（此前「有标记才覆盖」的保护让老设备的规则
 *    一直缺席，模型不调 `present`，产物卡全灭）；已带我们块 / 已有交付说明则不动；
 * 4. 每次引擎启动都跑，重复 apply 不重复追加。
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
    fun userOwnedFileGetsDeliveryRuleAppended() {
        val rootfs = temp.newFolder("rootfs")
        val file = agentsFile(rootfs)
        file.parentFile!!.mkdirs()
        val mine = "# 我自己写的规则\n- 不要动我\n"
        file.writeText(mine)

        GuestSetup.apply(rootfs)

        val text = file.readText()
        assertTrue("用户原有内容丢了", text.startsWith(mine.trimEnd('\n')))
        assertTrue("交付规则没追加到用户文件", text.contains("# 交付文件"))
        assertTrue("追加的块没带托管标记", text.contains("<!-- dshm-bot:managed"))
    }

    @Test
    fun appendedBlockIsIdempotent() {
        val rootfs = temp.newFolder("rootfs")
        val file = agentsFile(rootfs)
        file.parentFile!!.mkdirs()
        file.writeText("# 用户内容\n")

        GuestSetup.apply(rootfs)
        val once = file.readText()
        GuestSetup.apply(rootfs)

        assertEquals("重复 apply 不该再追加", once, file.readText())
    }

    @Test
    fun userOwnedFileWithSameDeliverySectionUntouched() {
        val rootfs = temp.newFolder("rootfs")
        val file = agentsFile(rootfs)
        file.parentFile!!.mkdirs()
        // 用户把我们的规则抄进了自己的文件（同名小节）：不再追加，避免模型看两份。
        val mine = "# 我自己写的规则\n\n# 交付文件（DSHM Bot）\n- 我自己说了算\n"
        file.writeText(mine)

        GuestSetup.apply(rootfs)

        assertEquals("已有同名交付小节时不该动", mine, file.readText())
    }

    @Test
    fun createsParentDirectoryWhenMissing() {
        val rootfs = temp.newFolder("rootfs")
        assertFalse(File(rootfs, "root/.dsh").exists())

        GuestSetup.apply(rootfs)

        assertTrue(agentsFile(rootfs).isFile)
    }
}
