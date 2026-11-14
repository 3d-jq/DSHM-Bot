package com.dshm.bot.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 建群规则：有管理员 + 至少勾一个成员，且管理员不能同时是成员。 */
class NewGroupRulesTest {

    @Test
    fun needsAnAdminAndAtLeastOneMember() {
        assertFalse("没选管理员不能建", canCreateGroup(null, setOf("b2")))
        assertFalse("没勾成员不能建", canCreateGroup("b1", emptySet()))
        assertTrue(canCreateGroup("b1", setOf("b2")))
        assertTrue(canCreateGroup("b1", setOf("b2", "b3")))
    }

    @Test
    fun theAdminCannotAlsoBeAMember() {
        assertFalse("管理员同时当成员不合法", canCreateGroup("b1", setOf("b1")))
        assertFalse(canCreateGroup("b1", setOf("b1", "b2")))
    }
}
