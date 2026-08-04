package com.scpc.deliveryagent.delivery

import com.scpc.deliveryagent.core.FactKind
import com.scpc.deliveryagent.core.FieldStatus
import com.scpc.deliveryagent.core.InMemoryStateStore
import com.scpc.deliveryagent.core.ProductionCore
import com.scpc.deliveryagent.core.ProductionState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat surface: what the app does with a sentence a person types.
 *
 * These tests fix the two properties that matter more than coverage of phrasing:
 * a settled reading is applied, and an unsettled one becomes a question rather
 * than a guess.
 */
class ChatIntakeTest {

    private val catalog = SyntheticCatalog.parse(
        File("src/main/assets/synthetic/catalog.json").readText(Charsets.UTF_8),
    )
    private val intake = RuleBasedIntake(catalog)
    private val daon = catalog.restaurant("restaurant.daon")!!
    private val ongi = catalog.restaurant("restaurant.ongi")!!
    private val marahyang = catalog.restaurant("restaurant.marahyang")!!

    private fun surface(): ProductSurface = ProductSurface(
        ProductionCore(
            store = InMemoryStateStore(),
            processMarker = "chat-process-1",
            namespace = ProductionState.NAMESPACE_FULL,
            asprEnabled = true,
            runBinding = "CHAT",
        ),
        catalog,
    )

    private fun context(restaurant: RestaurantDefinition) = IntakeContext(
        restaurant = restaurant,
        offeredSlots = catalog.slotsOf(restaurant).map { it.scopeToken }.toSet() +
            catalog.slots.filter { it.kind != SlotKind.MENU_OPTION }.map { it.scopeToken },
    )

    private fun ProductSurface.field(scopeToken: String) =
        state().fields[catalog.slot(scopeToken).fieldId]

    // ------------------------------------------------------------- reading

    @Test
    fun `reads a budget, a food character and a spiciness from one sentence`() {
        val read = intake.read("2만원 이하로 따뜻한 국물이 먹고 싶어. 맵지 않게 해줘.", context(daon))

        assertEquals(
            "budget.20000",
            read.values.first { it.scopeToken == Slots.BUDGET }.valueToken,
        )
        assertEquals(
            "warmth.soup",
            read.values.first { it.scopeToken == Slots.WARMTH }.valueToken,
        )
        assertEquals(
            "맵지 않게 is mild, not a mention of heat",
            "spice.mild",
            read.values.first { it.scopeToken == Slots.SPICINESS }.valueToken,
        )
    }

    @Test
    fun `reads amounts written several ways`() {
        fun budgetOf(text: String): String? = intake.read(text, context(daon))
            .values.firstOrNull { it.scopeToken == Slots.BUDGET }?.valueToken

        assertEquals("budget.20000", budgetOf("2만원 이하"))
        assertEquals("budget.18000", budgetOf("1만8천원까지"))
        assertEquals("budget.18000", budgetOf("18000원 이하로"))
        assertEquals("budget.18000", budgetOf("18,000원 이하로"))
        assertEquals(
            "an amount the catalog never listed still resolves",
            "budget.13500",
            budgetOf("13500원 이하로"),
        )
        assertEquals("a bare number is not an amount", null, budgetOf("맵지 않게"))
    }

    @Test
    fun `reads a duration`() {
        val read = intake.read("30분 안에 오는 걸로", context(daon))
        assertEquals("eta.30", read.values.first { it.scopeToken == Slots.ETA }.valueToken)
    }

    @Test
    fun `an amount is still read when another number comes first`() {
        fun budgetOf(text: String): String? = intake.read(text, context(daon))
            .values.firstOrNull { it.scopeToken == Slots.BUDGET }?.valueToken

        // The amount pattern also matches a bare number, so a quantity in front of
        // the budget used to claim the slot and the stated budget vanished. It
        // vanished silently whenever some other slot in the same sentence resolved,
        // which is the dangerous shape: a dropped budget is a dropped constraint.
        assertEquals("budget.15000", budgetOf("2인분 1만5천원 이하로"))
        assertEquals("budget.20000", budgetOf("3개 담고 2만원 이하로"))
    }

    @Test
    fun `a budget and a duration survive a leading quantity together`() {
        val read = intake.read("떡볶이 2개랑 2만원 이하로 30분 안에", context(daon))

        assertEquals(
            "budget.20000",
            read.values.first { it.scopeToken == Slots.BUDGET }.valueToken,
        )
        assertEquals(
            "eta.30",
            read.values.first { it.scopeToken == Slots.ETA }.valueToken,
        )
    }

    @Test
    fun `a reuse scope stated between the value's own words still reads both`() {
        // 항상 and 계속 are reuse-scope phrases the catalog authors, so a person who
        // uses one mid-sentence is speaking the vocabulary the app advertises. The
        // scope word is consumed before phrases are matched, and charging those
        // characters to the gap budget used to lose the value outright.
        val cilantro = intake.read("고수는 항상 빼줘", context(marahyang))
        assertEquals(
            "cilantro.exclude",
            cilantro.values.first { it.scopeToken == Slots.CILANTRO }.valueToken,
        )
        assertEquals(ValueScope.REMEMBER_FOR_REUSE, cilantro.values.first().scope)

        val rice = intake.read("밥은 계속 적게", context(daon))
        assertEquals(
            "rice.small",
            rice.values.first { it.scopeToken == Slots.RICE }.valueToken,
        )
        assertEquals(ValueScope.REMEMBER_FOR_REUSE, rice.values.first().scope)
    }

    @Test
    fun `an unstated reuse scope is read as unstated, not turned into a question`() {
        val read = intake.read("맵지 않게 해줘", context(daon))
        assertEquals(ValueScope.UNSTATED, read.values.first().scope)
        assertTrue(
            "reading the sentence is not the place that decides what to keep",
            read.questions.isEmpty(),
        )
    }

    @Test
    fun `a stated reuse scope is read as stated`() {
        assertEquals(
            ValueScope.REMEMBER_FOR_REUSE,
            intake.read("앞으로도 맵지 않게 해줘", context(daon)).values.first().scope,
        )
        assertEquals(
            ValueScope.THIS_ORDER_ONLY,
            intake.read("이번 주문만 아주 맵게 해줘", context(daon)).values.first().scope,
        )
    }

    @Test
    fun `a vague expression is asked about rather than acted on`() {
        val read = intake.read("가볍게 빨리 저렴하게 해줘", context(daon))
        assertTrue(read.values.isEmpty())
        assertEquals(3, read.questions.size)
        assertTrue(read.questions.all { it.about == "unclear" })
        assertTrue(read.questions.any { it.question.contains("기준이 여러 가지") })
    }

    @Test
    fun `an option this restaurant does not offer becomes a question`() {
        // 온기한상 has no spiciness option.
        val read = intake.read("맵지 않게 해줘", context(ongi))
        assertTrue(read.values.none { it.scopeToken == Slots.SPICINESS })
        assertTrue(read.questions.any { it.about == Slots.SPICINESS })
    }

    @Test
    fun `an action is read as an action, not as a preference`() {
        val read = intake.read("일회용 수저는 앞으로 자동으로 정하지 마", context(daon))
        assertTrue(Intent.REVOKE_AUTO_APPLY in read.intents)
        assertTrue(read.referencedSlots.contains(Slots.UTENSIL))
        assertTrue(
            "the withdrawal must not also store a utensil preference",
            read.values.none { it.scopeToken == Slots.UTENSIL },
        )
    }

    @Test
    fun `words nobody taught the app are reported, not dropped`() {
        val read = intake.read("탕수육 곱빼기로 부탁해", context(daon))
        assertTrue(read.values.isEmpty())
        assertTrue(read.unrecognised.isNotEmpty())
    }

    @Test
    fun `the deterministic reading uses no model and no inference budget`() {
        assertFalse(intake.usesModel)
        assertEquals(0, intake.invocations)
        repeat(3) {
            assertEquals(
                "the same sentence reads the same way every time",
                intake.read("앞으로도 맵지 않게", context(daon)).values,
                intake.read("앞으로도 맵지 않게", context(daon)).values,
            )
        }
    }

    // ---------------------------------------------------------- applying

    @Test
    fun `a chat turn applies what it settled and asks about the rest`() {
        val surface = surface()
        surface.startNewOrder(daon)
        val turn = surface.say(daon, "2만원 이하로 따뜻한 국물, 앞으로도 맵지 않게 해줘")

        assertTrue(turn.questions.isEmpty())
        assertEquals("spice.mild", surface.field(Slots.SPICINESS)?.value)
        assertEquals(
            "a preference the user asked to keep is stored for reuse",
            FactKind.STABLE,
            surface.state().facts.values.first { it.value == "spice.mild" }.kind,
        )
        assertEquals("budget.20000", surface.field(Slots.BUDGET)?.value)
    }

    @Test
    fun `a budget the catalog never listed is applied, not refused`() {
        // The parser resolves any well-formed amount, so the draft has to accept
        // one too; presets are examples, not the set of allowed budgets.
        val surface = surface()
        surface.startNewOrder(daon)
        surface.say(daon, "13500원 이하로 해줘")

        assertEquals("budget.13500", surface.field(Slots.BUDGET)?.value)
        assertEquals(13_500, DraftPricing(catalog).budgetLimit(surface.state()))
    }

    @Test
    fun `a first value with no stated scope is remembered and reported as remembered`() {
        val surface = surface()
        surface.startNewOrder(daon)
        val turn = surface.say(daon, "맵지 않게 해줘")

        assertTrue(turn.applied.isNotEmpty())
        assertEquals("spice.mild", surface.field(Slots.SPICINESS)?.value)
        assertEquals(
            "the screen is told so it can say what was kept and offer to narrow it",
            listOf("spice.mild"),
            turn.firstStored.map { it.valueToken },
        )
        assertTrue(
            "a first answer is kept for later orders, not just this one",
            surface.storedPreferencesFor(Slots.SPICINESS).any { it.value == "spice.mild" },
        )
    }

    @Test
    fun `a value contradicting a stored one is not written until its reach is stated`() {
        val surface = surface()
        surface.startNewOrder(daon)
        surface.say(daon, "앞으로도 맵지 않게 해줘")

        val turn = surface.say(daon, "아주 맵게 해줘")

        assertTrue("nothing is applied while the reach is open", turn.applied.isEmpty())
        assertEquals(
            listOf("spice.very_hot"),
            turn.scopeChanges.map { it.valueToken },
        )
        assertEquals(
            "the stored preference is untouched until the user says otherwise",
            "spice.mild",
            surface.storedPreferencesFor(Slots.SPICINESS).single().value,
        )

        surface.rememberAtLevel(daon, catalog.slot(Slots.SPICINESS), "spice.very_hot", level = null)
        assertEquals("spice.very_hot", surface.field(Slots.SPICINESS)?.value)
        assertEquals(
            "이번 주문만 leaves what is remembered exactly as it was",
            "spice.mild",
            surface.storedPreferencesFor(Slots.SPICINESS).single().value,
        )
    }

    @Test
    fun `a value given without 앞으로도 still carries into the next order`() {
        // The point of storing a first answer: the second order is shorter without
        // the user having had to know the phrase "앞으로도". If this stops holding,
        // the default has stopped earning what it costs.
        val surface = surface()
        surface.startNewOrder(daon)
        surface.say(daon, "맵지 않게 해줘")

        surface.nextOrderSession(marahyang)

        assertEquals(
            "the remembered value fills the same option at another restaurant",
            "spice.mild",
            surface.field(Slots.SPICINESS)?.value,
        )
        assertEquals(
            FieldStatus.AUTO_APPLIED,
            surface.field(Slots.SPICINESS)?.status,
        )
    }

    @Test
    fun `a stated one-off never becomes a standing preference`() {
        val surface = surface()
        surface.startNewOrder(daon)
        surface.say(daon, "이번 주문만 아주 맵게 해줘")

        assertEquals("spice.very_hot", surface.field(Slots.SPICINESS)?.value)
        assertTrue(
            "saying 이번 주문만 is a decision, not a slot waiting to be filled",
            surface.storedPreferencesFor(Slots.SPICINESS).isEmpty(),
        )
    }

    @Test
    fun `a chat withdrawal keeps the stored value and asks again next order`() {
        val surface = surface()
        surface.startNewOrder(daon)
        surface.say(daon, "앞으로도 수저 빼고 맵지 않게 해줘")
        assertEquals(
            "answering the utensil question makes it the user's own choice",
            FieldStatus.CONFIRMED,
            surface.field(Slots.UTENSIL)?.status,
        )

        surface.say(daon, "일회용 수저는 앞으로 자동으로 정하지 마")
        assertTrue(
            "the stored preference itself survives the withdrawal",
            surface.state().facts.values.any { it.value == "utensil.exclude" },
        )
        assertEquals(
            "a value the user chose for this order is not taken away from them",
            FieldStatus.CONFIRMED,
            surface.field(Slots.UTENSIL)?.status,
        )

        // The withdrawal is about future orders, and that is where it shows.
        surface.nextOrderSession(daon)
        assertEquals(
            "the next order asks about the utensil again",
            FieldStatus.NEEDS_CONFIRMATION,
            surface.field(Slots.UTENSIL)?.status,
        )
        assertEquals(
            "an option whose permission was not withdrawn is still applied",
            FieldStatus.AUTO_APPLIED,
            surface.field(Slots.SPICINESS)?.status,
        )
        assertTrue(surface.state().facts.values.any { it.value == "utensil.exclude" })
    }

    // ------------------------------------------------------ recommending

    @Test
    fun `recommendations are ranked from conditions, preferences and past results`() {
        val surface = surface()
        surface.startNewOrder(daon)
        surface.say(daon, "2만원 이하로 따뜻한 국물, 앞으로도 맵지 않게 해줘")
        val turn = surface.say(daon, "추천해줘")

        assertTrue(turn.recommendations.isNotEmpty())
        val top = turn.recommendations.first()
        assertEquals(
            "the mild soup matches both the stated condition and the stored preference",
            "menu.daon.clear",
            top.valueToken,
        )
        assertTrue(top.offerable)
        assertTrue(
            "the user can see why it is first",
            top.reasons.map { it.badge }.containsAll(listOf("오늘 입력", "직접 저장")),
        )
        assertTrue(
            "the spicy alternative is still offered, ranked lower",
            turn.recommendations.any { it.valueToken == "menu.daon.spicy" },
        )
        assertTrue(
            "recommending never confirms a menu on the user's behalf",
            surface.field(Slots.MAIN)?.status != FieldStatus.CONFIRMED,
        )
    }

    @Test
    fun `stating conditions is itself a request for candidates`() {
        val surface = surface()
        surface.startNewOrder(daon)

        // No menu is chosen yet, so saying what tonight should be like is
        // answered with candidates. The declared product recommends from the
        // stated conditions; it does not wait to be asked by the word 추천.
        val opening = surface.say(daon, "2만원 이하로 따뜻한 국물, 앞으로도 맵지 않게 해줘")
        assertTrue(
            "conditions alone bring candidates",
            opening.recommendations.isNotEmpty(),
        )
        assertEquals("menu.daon.clear", opening.recommendations.first().valueToken)

        // Once a menu is in the draft the conversation is about that order, so
        // an option instruction does not push a fresh menu list at the user.
        surface.addLine(daon, "menu.daon.clear")
        val afterMenu = surface.say(daon, "밥은 적게 주세요")
        assertTrue(
            "an instruction about the chosen dish is not a menu search",
            afterMenu.recommendations.isEmpty(),
        )
        assertTrue(
            "asking by name still works at any point",
            surface.say(daon, "추천해줘").recommendations.isNotEmpty(),
        )
    }

    @Test
    fun `a candidate the current conditions rule out says why`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.say(ongi, "10000원 이하로 해줘")
        val candidates = Recommender(catalog).candidates(surface.state(), ongi)

        assertTrue(candidates.all { !it.offerable })
        assertTrue(candidates.all { it.blockedBy!!.contains("예산") })
    }

    @Test
    fun `a past satisfaction result changes the ranking reasons`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.say(ongi, "앞으로도 맵지 않게")
        val before = Recommender(catalog).candidates(surface.state(), ongi)
        assertTrue(before.none { it.reasons.any { reason -> reason.badge == "지난 평가" } })

        surface.scheduleOutcome(catalog.slot(Slots.SALTINESS), "salt.light")
        surface.advanceTime()
        val after = Recommender(catalog).candidates(surface.state(), ongi)

        assertTrue(
            "the late result is visible in why a candidate ranks where it does",
            after.any { it.reasons.any { reason -> reason.badge == "지난 평가" } },
        )
        assertEquals(
            "the item the result points at comes first",
            "menu.ongi.perilla",
            after.first().valueToken,
        )
    }

    @Test
    fun `ranking is a pure function of state and catalog`() {
        fun rank(): List<String> {
            val surface = surface()
            surface.startNewOrder(daon)
            surface.say(daon, "2만원 이하로 따뜻한 국물, 앞으로도 맵지 않게 해줘")
            return Recommender(catalog).candidates(surface.state(), daon).map { it.valueToken }
        }
        assertEquals(rank(), rank())
    }
}
