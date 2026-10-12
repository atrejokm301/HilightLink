package dev.kevtr.hilightlink

import dev.kevtr.hilightlink.engine.Effect
import dev.kevtr.hilightlink.engine.Renderer
import dev.kevtr.hilightlink.engine.Style
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val app = Rule("chat")
    private val group = Rule("chat", channel = "groups", channelName = "Groups", id = "chat/1")
    private val mom = Rule("chat", title = "Mom", effect = Effect(Style.BLINK), id = "chat/2")
    private val catchAll = Rule(RuleStore.DEFAULT_PKG)

    private fun load(vararg rules: Rule) {
        assertTrue(RuleStore.importJson("""{"rules":[]}"""))
        rules.forEach(RuleStore::put)
    }

    @Test
    fun `an app's own rule covers all of its notifications`() {
        load(app, catchAll)
        assertEquals(app, RuleStore.ruleFor("chat", "groups") { "Anyone" })
        assertEquals(catchAll, RuleStore.ruleFor("other"))
    }

    @Test
    fun `an app that is switched off does not fall back to the catch-all`() {
        load(app.copy(enabled = false), catchAll)
        assertNull(RuleStore.ruleFor("chat"))
    }

    @Test
    fun `the narrowest rule that fits wins`() {
        load(app, group, mom)
        assertEquals(group, RuleStore.ruleFor("chat", "groups") { "Book club" })
        assertEquals(app, RuleStore.ruleFor("chat", "direct") { "Dad" })
        assertEquals(mom, RuleStore.ruleFor("chat", "direct") { "mom" })
        // A name beats a channel when both fit.
        assertEquals(mom, RuleStore.ruleFor("chat", "groups") { "Family\nMom" })
    }

    @Test
    fun `a narrow rule that is off keeps its part dark`() {
        load(app, group.copy(enabled = false))
        assertNull(RuleStore.ruleFor("chat", "groups"))
        assertEquals(app, RuleStore.ruleFor("chat", "direct"))
    }

    @Test
    fun `a narrow rule lights its part while the app's own rule is off`() {
        load(app.copy(enabled = false), mom)
        assertEquals(mom, RuleStore.ruleFor("chat", "direct") { "Mom" })
        assertNull(RuleStore.ruleFor("chat", "direct") { "Dad" })
    }

    @Test
    fun `a narrow rule with nothing set matches nothing`() {
        load(app, Rule("chat", enabled = false, id = "chat/3"))
        assertEquals(app, RuleStore.ruleFor("chat", "groups") { "Mom" })
    }

    @Test
    fun `the title is not read unless a rule of that app asks for it`() {
        load(app, group, Rule("mail", title = "Boss", id = "mail/1"))
        RuleStore.ruleFor("chat", "groups") { error("title was read") }
    }

    @Test
    fun `removing an app's own rule takes its narrower ones along`() {
        load(app, group, mom, catchAll)
        RuleStore.remove(mom)
        assertEquals(listOf(app, group, catchAll), RuleStore.config.value.rules)
        RuleStore.remove(app)
        assertEquals(listOf(catchAll), RuleStore.config.value.rules)
    }

    @Test
    fun `a backup keeps everything`() {
        load(app, group, mom)
        RuleStore.setCall(CallConfig(on = true, effect = Effect(Style.STROBE, listOf(-1), periodMs = 300)))
        RuleStore.setQuiet(QuietConfig(on = true, startMinute = 60, endMinute = 120, dim = true, calls = false))
        RuleStore.setRespectDnd(false)
        val before = RuleStore.config.value
        val backup = RuleStore.exportJson()
        load()
        assertTrue(RuleStore.importJson(backup))
        assertEquals(before, RuleStore.config.value)
    }

    @Test
    fun `a backup from before these settings existed still loads`() {
        val old = """{"onlyScreenOff":true,"rules":[{"pkg":"chat","enabled":true,"effect":{"style":"WAVE"}}]}"""
        assertTrue(RuleStore.importJson(old))
        val config = RuleStore.config.value
        assertEquals(Rule("chat", effect = Effect.fromJson(org.json.JSONObject("""{"style":"WAVE"}"""))), config.rules.single())
        assertFalse(config.rules.single().scoped)
        assertEquals(CallConfig(), config.call)
        assertEquals(QuietConfig(), config.quiet)
        assertTrue(config.respectDnd)
    }

    @Test
    fun `quiet hours may run through the night`() {
        val night = QuietConfig(on = true, startMinute = 22 * 60, endMinute = 7 * 60)
        assertTrue(Quiet.active(night, 23 * 60))
        assertTrue(Quiet.active(night, 22 * 60))
        assertTrue(Quiet.active(night, 6 * 60 + 59))
        assertFalse(Quiet.active(night, 7 * 60))
        assertFalse(Quiet.active(night, 12 * 60))
        val nap = QuietConfig(on = true, startMinute = 13 * 60, endMinute = 14 * 60)
        assertTrue(Quiet.active(nap, 13 * 60 + 30))
        assertFalse(Quiet.active(nap, 14 * 60))
        assertFalse(Quiet.active(nap.copy(on = false), 13 * 60 + 30))
        assertFalse(Quiet.active(nap.copy(endMinute = 13 * 60), 13 * 60))
    }

    @Test
    fun `waiting colours share the LEDs`() {
        val out = IntArray(Renderer.LEDS)
        Renderer.segments(listOf(1), out)
        assertArrayEquals(intArrayOf(1, 1, 1, 1, 1, 1, 1, 1), out)
        Renderer.segments(listOf(1, 2), out)
        assertArrayEquals(intArrayOf(1, 1, 1, 1, 2, 2, 2, 2), out)
        Renderer.segments(listOf(1, 2, 3), out)
        assertArrayEquals(intArrayOf(1, 1, 1, 2, 2, 2, 3, 3), out)
        Renderer.segments((1..10).toList(), out)
        assertArrayEquals(intArrayOf(3, 4, 5, 6, 7, 8, 9, 10), out)
        Renderer.segments(emptyList(), out)
        assertArrayEquals(IntArray(Renderer.LEDS), out)
    }
}
