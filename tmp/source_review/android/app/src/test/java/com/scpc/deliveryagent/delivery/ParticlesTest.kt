package com.scpc.deliveryagent.delivery

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Korean particles chosen from the word, not written into the template.
 *
 * Every sentence the app shows is assembled from catalog labels, so a hardcoded
 * particle is wrong for half of them: "맵기를"과 "밥 양을" come from one line of
 * code. These cases pin the rule that decides.
 */
class ParticlesTest {

    @Test
    fun `the object particle follows the final consonant`() {
        assertEquals("맵기를", Particles.withObj("맵기"))
        assertEquals("간 세기를", Particles.withObj("간 세기"))
        assertEquals("일회용 수저를", Particles.withObj("일회용 수저"))
        assertEquals("밥 양을", Particles.withObj("밥 양"))
        assertEquals("총액을", Particles.withObj("총액"))
        assertEquals("사이드를", Particles.withObj("사이드"))
    }

    @Test
    fun `the destination particle treats a final consonant as long form`() {
        assertEquals("순한맛으로", Particles.withInto("순한맛"))
        assertEquals("보통으로", Particles.withInto("보통"))
        assertEquals("제외로", Particles.withInto("제외"))
        assertEquals("약하게로", Particles.withInto("약하게"))
    }

    @Test
    fun `a final rieul takes the short form, like a vowel`() {
        assertEquals("서울로", Particles.withInto("서울"))
        assertEquals("적게로", Particles.withInto("적게"))
    }

    @Test
    fun `the subject particle follows the same rule`() {
        assertEquals("이", Particles.subject("총액"))
        assertEquals("가", Particles.subject("맵기"))
    }

    @Test
    fun `a label ending in a digit or latin letter reads as vowel final`() {
        // Amounts and counts are shown verbatim, so they must not pick up 을/으로.
        assertEquals("20,000원 이하를", Particles.withObj("20,000원 이하"))
        assertEquals("3개를", Particles.withObj("3개"))
        assertEquals("항목 l1를", Particles.withObj("항목 l1"))
    }

    @Test
    fun `trailing whitespace does not change the choice`() {
        assertEquals("맵기 를", Particles.withObj("맵기 ").let { "맵기 " + Particles.obj("맵기 ") })
        assertEquals("를", Particles.obj("맵기 "))
    }
}
