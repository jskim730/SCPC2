package com.scpc.deliveryagent.delivery

import com.scpc.deliveryagent.core.InMemoryStateStore
import com.scpc.deliveryagent.core.ProductionCore
import com.scpc.deliveryagent.core.ProductionState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ranking a typed sentence across every restaurant.
 *
 * The frozen declaration's long-horizon goal is that stating food and conditions
 * in conversation produces 식당·메뉴 candidates, and only then does the user pick.
 * These tests pin the ranking half of that: that one sentence reaches several
 * restaurants, that the order is the same every run, and — the property the whole
 * submission rests on — that both comparison arms rank identically, so no measured
 * ASPR gain can come from the recommendation itself.
 */
class CrossRestaurantDiscoveryTest {

    private val catalog = SyntheticCatalog.parse(
        File("src/main/assets/synthetic/catalog.json").readText(Charsets.UTF_8),
    )
    private val intake = RuleBasedIntake(catalog)
    private val recommender = Recommender(catalog)

    private fun core(asprEnabled: Boolean) = ProductionCore(
        store = InMemoryStateStore(),
        processMarker = "discovery-process-1",
        namespace = ProductionState.NAMESPACE_FULL,
        asprEnabled = asprEnabled,
        runBinding = "DISCOVERY",
    )

    /** What the app will read before any restaurant exists. */
    private fun conditionsOf(sentence: String): Recommender.Conditions =
        Recommender.Conditions.ofUtterance(
            catalog,
            intake.read(
                sentence,
                IntakeContext(
                    restaurant = null,
                    offeredSlots = catalog.slots.map { it.scopeToken }.toSet(),
                ),
            ),
        )

    @Test
    fun `one sentence reaches several restaurants`() {
        val state = core(asprEnabled = true).state()

        val found = recommender.discoveries(state, conditionsOf("1만5천원 이하로 따뜻한 국물"))

        assertEquals("세 곳을 제안한다", 3, found.size)
        assertEquals(
            "한 식당이 목록을 독차지하지 않는다",
            3,
            found.map { it.restaurant.entityToken }.distinct().size,
        )
        assertTrue(
            "제안된 것은 전부 주문 가능하다 (${found.map { it.candidate.blockedBy }})",
            found.all { it.candidate.offerable },
        )
    }

    @Test
    fun `a dish named before any restaurant is read and offered first`() {
        // The opening screen invites "먹고 싶은 것을 바로 말씀하셔도 됩니다", and a dish
        // name is the most direct way anyone takes that up. Menu phrases used to be
        // indexed only for the restaurant already chosen, so on that screen — where
        // there is none — naming a dish matched nothing whatsoever.
        val read = intake.read(
            "마라탕",
            IntakeContext(
                restaurant = null,
                offeredSlots = catalog.slots.map { it.scopeToken }.toSet(),
            ),
        )
        assertEquals(
            "menu.marahyang.malatang",
            read.values.single { it.scopeToken == Slots.MAIN }.valueToken,
        )

        val found = recommender.discoveries(core(asprEnabled = true).state(), conditionsOf("마라탕"))
        assertEquals(
            "the dish the user named leads the offer",
            "menu.marahyang.malatang",
            found.first().candidate.valueToken,
        )
        assertTrue(
            "and the row says why it is there",
            found.first().candidate.reasons.any { it.badge == "말한 메뉴" },
        )
    }

    @Test
    fun `naming a dish also surfaces its own kind at another restaurant`() {
        val found = recommender.discoveries(core(asprEnabled = true).state(), conditionsOf("마라탕"))

        // 마라 떡볶이 is 마라 요리 at a different restaurant. Reaching it from the word
        // 마라탕 is the cross-restaurant discovery the declaration promises.
        assertTrue(
            "same-kind dishes follow the named one (${found.map { it.candidate.valueToken }})",
            found.any { it.candidate.valueToken == "menu.geumson.mala_tteok" },
        )
    }

    @Test
    fun `a stated budget keeps over-budget restaurants out of the offer`() {
        val state = core(asprEnabled = true).state()

        val found = recommender.discoveries(state, conditionsOf("1만원 이하로 따뜻한 국물"))

        found.forEach { discovery ->
            assertTrue(
                "${discovery.restaurant.name} ${discovery.candidate.label} " +
                    "(${discovery.candidate.amount}원)이 1만원 예산 안에 있다",
                discovery.candidate.amount <= 10000,
            )
        }
    }

    @Test
    fun `the same sentence ranks the same way every time`() {
        val conditions = conditionsOf("1만5천원 이하로 따뜻한 국물")

        val first = recommender.discoveries(core(asprEnabled = true).state(), conditions)
        val second = recommender.discoveries(core(asprEnabled = true).state(), conditions)

        assertEquals(
            first.map { "${it.restaurant.entityToken}/${it.candidate.valueToken}" },
            second.map { "${it.restaurant.entityToken}/${it.candidate.valueToken}" },
        )
    }

    @Test
    fun `both comparison arms rank a sentence identically`() {
        // If the two arms could order candidates differently, a VIL difference
        // would no longer be attributable to ASPR. Discovery runs before anything
        // is stored, so there is nothing for ASPR to contribute here — and that
        // has to stay true as the ranking grows.
        val conditions = conditionsOf("2만원 이하로 30분 안에")

        val full = recommender.discoveries(core(asprEnabled = true).state(), conditions)
        val claimOff = recommender.discoveries(core(asprEnabled = false).state(), conditions)

        assertEquals(
            full.map { "${it.restaurant.entityToken}/${it.candidate.valueToken}/${it.candidate.score}" },
            claimOff.map { "${it.restaurant.entityToken}/${it.candidate.valueToken}/${it.candidate.score}" },
        )
    }

    @Test
    fun `a sentence nobody understood still proposes something`() {
        val state = core(asprEnabled = true).state()

        val found = recommender.discoveries(state, conditionsOf("가성비 좋은 걸로 부탁해요"))

        assertEquals(
            "이해하지 못해도 고를 것은 남는다",
            3,
            found.size,
        )
    }

    @Test
    fun `exploring commits nothing`() {
        val surface = ProductSurface(core(asprEnabled = true), catalog)
        val before = surface.state()
        val beforeSteps = before.runStepCount
        val beforeRun = before.runId

        val exploration = surface.explore("1만5천원 이하로 따뜻한 국물")

        assertTrue("제안은 나온다", exploration.discoveries.isNotEmpty())
        val after = surface.state()
        assertEquals("step이 실행되지 않았다", beforeSteps, after.runStepCount)
        assertEquals("run이 시작되지 않았다", beforeRun, after.runId)
        assertTrue("저장된 것이 없다", after.facts.isEmpty())
        assertTrue("주문 항목이 생기지 않았다", after.draftLines.isEmpty())
    }

    @Test
    fun `picking a proposal starts that order and keeps what was said`() {
        val surface = ProductSurface(core(asprEnabled = true), catalog)
        val sentence = "1만5천원 이하로 따뜻한 국물"

        val chosen = surface.explore(sentence).discoveries.first()
        // What the screen does on tap: start there, replay the sentence, add the line.
        surface.startNewOrder(chosen.restaurant)
        surface.say(chosen.restaurant, sentence)
        surface.addLine(chosen.restaurant, chosen.candidate.valueToken)

        val state = surface.state()
        assertEquals(
            "고른 식당에서 주문이 열렸다",
            chosen.restaurant.entityToken,
            state.targetEntityId,
        )
        assertEquals(
            "고른 메뉴가 초안에 들어갔다",
            chosen.candidate.valueToken,
            state.fields[LineTokens.menuFieldId("l1")]?.value,
        )
        assertTrue(
            "말한 조건이 다시 묻지 않고 반영됐다",
            state.fields.values.any { it.value == "budget.15000" } &&
                state.fields.values.any { it.value == "warmth.soup" },
        )
    }

    @Test
    fun `conditions read from a sentence combine`() {
        val budgetOnly = conditionsOf("2만원 이하로")
        val timeOnly = conditionsOf("30분 안에")

        val both = budgetOnly + timeOnly

        assertEquals(20000, both.budget)
        assertEquals(30, both.etaLimitMinutes)
        assertEquals(
            "더 좁은 예산이 이긴다",
            15000,
            (both + conditionsOf("1만5천원 이하로")).budget,
        )
    }
}
