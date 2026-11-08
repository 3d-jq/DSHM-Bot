package com.dshm.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 蓝图公式（DS Bot `src/shared/formula.js`）：语法、函数、`let` 名字。 */
class ChatFormulaTest {

    private fun eval(source: String, values: Map<String, Any?> = emptyMap()): Any? {
        val formula = ChatFormula.compile(source) ?: throw AssertionError("did not compile: $source")
        val scope = ChatViewScope(values, emptyMap())
        return when (val outcome = formula.evaluate(scope::lookup)) {
            is FormulaOutcome.Value -> outcome.value
            is FormulaOutcome.Failure -> throw AssertionError(outcome.message)
        }
    }

    private fun fail(source: String): String {
        val formula = ChatFormula.compile(source) ?: return "did not compile"
        val scope = ChatViewScope(emptyMap(), emptyMap())
        return when (val outcome = formula.evaluate(scope::lookup)) {
            is FormulaOutcome.Value -> throw AssertionError("expected a failure, got ${outcome.value}")
            is FormulaOutcome.Failure -> outcome.message
        }
    }

    @Test
    fun doesArithmeticWithPrecedence() {
        assertEquals(7.0, eval("1 + 2 * 3"))
        assertEquals(-5.0, eval("-(2 + 3)"))
        assertEquals(2.0, eval("7 % 5"))
        assertEquals(3.0, eval("(1 + 2) * 3 - 6"))
    }

    @Test
    fun comparesAndBranches() {
        assertEquals(true, eval("qty > 3", mapOf("qty" to 5.0)))
        assertEquals("big", eval("qty > 3 ? 'big' : 'small'", mapOf("qty" to 5.0)))
        assertEquals("small", eval("qty > 3 ? 'big' : 'small'", mapOf("qty" to 1.0)))
        assertEquals(true, eval("on && qty == 2", mapOf("on" to true, "qty" to 2.0)))
    }

    @Test
    fun callsTheFunctionsDsBotAllows() {
        assertEquals(2.0, eval("min(4, 2, 9)"))
        assertEquals(9.0, eval("max(4, 2, 9)"))
        assertEquals(3.0, eval("floor(3.7)"))
        assertEquals(4.0, eval("ceil(3.2)"))
        assertEquals(8.0, eval("pow(2, 3)"))
        assertEquals(3.0, eval("round(2.5, 0)"))
        assertEquals(5.0, eval("clamp(9, 0, 5)"))
        assertEquals("2.50", eval("fixed(2.5, 2)"))
        assertEquals("1,234.50", eval("format(1234.5, 2)"))
        assertEquals("12%", eval("percent(0.12, 0)"))
    }

    @Test
    fun joinsTextOnlyWhenOneSideIsText() {
        assertEquals("6: 6", eval("'6: ' + max(2, 6)"))
        assertEquals(6.0, eval("2 + 4"))
    }

    @Test
    fun showsNumbersWithoutFloatNoise() {
        assertEquals("0.3", show(eval("0.1 + 0.2")))
    }

    @Test
    fun readsNamesFromTheViewScope() {
        val lets = mapOf("total" to ChatFormula.compile("price * qty")!!)
        val scope = ChatViewScope(mapOf("price" to 3.0, "qty" to 4.0), lets)
        assertEquals(12.0, scope.lookup("total"))
        assertEquals(setOf("price", "qty"), ChatFormula.namesIn(lets.getValue("total")))
    }

    @Test
    fun failsOnUnknownNamesAndBadSource() {
        assertEquals(true, fail("nope + 1").contains("unknown name"))
        assertNull(ChatFormula.compile("1 +"))
        assertNull(ChatFormula.compile("nope(1)"))
        assertNull(ChatFormula.compile(42))
    }
}
