package com.scpc.deliveryagent.delivery

import com.scpc.deliveryagent.core.FactKind
import com.scpc.deliveryagent.core.FieldStatus
import com.scpc.deliveryagent.core.InMemoryStateStore
import com.scpc.deliveryagent.core.Permission
import com.scpc.deliveryagent.core.ProductionCore
import com.scpc.deliveryagent.core.ProductionState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reviewing an order, and what the app is allowed to learn from it.
 *
 * The property under test is restraint: a remark about one order becomes an offer,
 * the user decides, and an accepted remark only ever proposes later. Nothing a
 * review said is applied on its own, and deleting the review removes what it
 * taught.
 */
class ReviewMemoryTest {

    private val catalog = SyntheticCatalog.parse(
        File("src/main/assets/synthetic/catalog.json").readText(Charsets.UTF_8),
    )
    private val intake = RuleBasedIntake(catalog)
    private val daon = catalog.restaurant("restaurant.daon")!!
    private val ongi = catalog.restaurant("restaurant.ongi")!!

    private fun surface(store: InMemoryStateStore = InMemoryStateStore(), process: String = "review-1") =
        ProductSurface(
            ProductionCore(store, process, ProductionState.NAMESPACE_FULL, true, "REVIEW"),
            catalog,
        )

    private fun ProductSurface.field(scopeToken: String) =
        state().fields[catalog.slot(scopeToken).fieldId]

    /** Completes one order so there is something to review. */
    private fun placeOrder(surface: ProductSurface) {
        surface.startNewOrder(daon)
        surface.say(daon, "2만원 이하로 따뜻한 국물, 앞으로도 맵지 않게 해줘")
        surface.chooseLine(daon, catalog.slot(Slots.MAIN), "menu.daon.clear")
        surface.say(daon, "앞으로도 수저 빼고 밥은 보통으로")
        surface.requestDecision(daon)
    }

    // -------------------------------------------------------------- reading

    @Test
    fun `a review names the option it was about and what would answer it`() {
        val reading = intake.readReview("국물은 괜찮았는데 간이 좀 셌어", "rating.ok")

        assertEquals("rating.ok", reading.ratingToken)
        val candidate = reading.candidates.single()
        assertEquals(Slots.SALTINESS, candidate.slotToken)
        assertEquals("salt.light", candidate.impliesValue)
        assertEquals(Sentiment.NEGATIVE, candidate.sentiment)
        assertTrue(
            "the positive half is understood too, without becoming memory",
            reading.observations.isNotEmpty(),
        )
    }

    @Test
    fun `a review that only praises produces nothing to remember`() {
        val reading = intake.readReview("맛있었어요", "rating.good")
        assertTrue(reading.candidates.isEmpty())
        assertTrue(reading.observations.isNotEmpty())
    }

    @Test
    fun `a remark the app was never taught is reported rather than guessed at`() {
        val reading = intake.readReview("포장이 찌그러져서 왔어요", null)
        assertTrue(reading.candidates.isEmpty())
        assertTrue(reading.unrecognised.isNotEmpty())
    }

    @Test
    fun `an unrated review is still readable`() {
        val reading = intake.readReview("간이 셌어", null)
        assertEquals(null, reading.ratingToken)
        assertEquals(1, reading.candidates.size)
    }

    // ------------------------------------------------------------- offering

    @Test
    fun `a review is recorded but changes nothing until the user agrees`() {
        val surface = surface()
        placeOrder(surface)
        val before = surface.state()

        val turn = surface.submitReview(daon, "rating.ok", "국물은 괜찮았는데 간이 좀 셌어")

        assertEquals(1, surface.reviews().size)
        assertEquals("국물은 괜찮았는데 간이 좀 셌어", surface.reviews().single().text)
        assertEquals(1, turn.offers.size)
        assertTrue(
            "the review is traceable to the order it was about",
            surface.reviews().single().actionId != null,
        )
        assertTrue(
            "nothing was learned yet",
            surface.reviews().single().derivedValueTokens.isEmpty(),
        )
        assertEquals(
            "no memory appeared from the review alone",
            before.facts.keys,
            surface.state().facts.keys,
        )
        assertTrue(surface.state().pendingOutcomes.isEmpty())
    }

    @Test
    fun `declining an offer leaves the review and creates no memory`() {
        val surface = surface()
        placeOrder(surface)
        val turn = surface.submitReview(daon, "rating.poor", "간이 셌어")

        surface.dismissFromReview(turn.offers.single())

        assertEquals(1, surface.reviews().size)
        assertTrue(surface.reviews().single().derivedValueTokens.isEmpty())
        assertTrue(surface.state().pendingOutcomes.isEmpty())
        assertTrue(surface.state().appliedOutcomes.isEmpty())
    }

    @Test
    fun `accepting an offer stores it as a result that arrives later`() {
        val surface = surface()
        placeOrder(surface)
        val turn = surface.submitReview(daon, "rating.ok", "국물은 괜찮았는데 간이 좀 셌어")

        surface.rememberFromReview(turn.reviewId, turn.offers.single())

        assertEquals(
            listOf("salt.light"),
            surface.reviews().single().derivedValueTokens,
        )
        assertEquals(
            "it is scheduled, not folded in yet",
            1,
            surface.state().pendingOutcomes.size,
        )
        assertTrue(surface.state().appliedOutcomes.isEmpty())
    }

    // ------------------------------------------------------- later effect

    @Test
    fun `what a review taught only proposes in a later order`() {
        val surface = surface()
        placeOrder(surface)
        val turn = surface.submitReview(daon, "rating.ok", "국물은 괜찮았는데 간이 좀 셌어")
        surface.rememberFromReview(turn.reviewId, turn.offers.single())

        // Next order, at a different restaurant, after time has passed.
        surface.nextOrderSession(ongi)
        surface.advanceTime()

        val learned = surface.state().facts.values.single { it.kind == FactKind.OUTCOME }
        assertEquals("salt.light", learned.value)
        assertEquals(
            "a result never gets permission to apply itself",
            Permission.ASK_BEFORE_APPLY,
            learned.permission,
        )
        assertEquals(
            "the stored preference from the order is still applied automatically",
            FieldStatus.AUTO_APPLIED,
            surface.field(Slots.SPICINESS)?.status,
        )

        val candidates = Recommender(catalog).candidates(surface.state(), ongi)
        assertTrue(
            "the review shows up as a reason a candidate ranks where it does",
            candidates.any { candidate ->
                candidate.reasons.any { it.badge == "지난 평가" }
            },
        )
    }

    @Test
    fun `a result learned from a review is folded in exactly once`() {
        val store = InMemoryStateStore()
        val surface = surface(store)
        placeOrder(surface)
        val turn = surface.submitReview(daon, "rating.ok", "간이 셌어")
        surface.rememberFromReview(turn.reviewId, turn.offers.single())

        surface.nextOrderSession(ongi)
        surface.advanceTime()
        assertEquals(1, surface.state().appliedOutcomes.size)

        // Same store, next process, and time moves again.
        val relaunched = surface(store, process = "review-2")
        relaunched.advanceTime()
        assertEquals(
            "a restart does not apply the same result a second time",
            1,
            relaunched.state().appliedOutcomes.size,
        )
    }

    @Test
    fun `deleting a review removes what it taught and leaves only a marker`() {
        val surface = surface()
        placeOrder(surface)
        val turn = surface.submitReview(daon, "rating.ok", "국물은 괜찮았는데 간이 좀 셌어")
        surface.rememberFromReview(turn.reviewId, turn.offers.single())
        surface.nextOrderSession(ongi)
        surface.advanceTime()
        assertNotNull(surface.state().facts.values.firstOrNull { it.kind == FactKind.OUTCOME })

        surface.deleteReview(turn.reviewId)
        val after = surface.state()

        assertTrue("the review is gone", after.reviews.isEmpty())
        assertFalse(
            "the text is gone from state",
            after.encode().contains("국물은 괜찮았는데"),
        )
        assertTrue(
            "the memory it produced is gone",
            after.facts.values.none { it.kind == FactKind.OUTCOME },
        )
        assertTrue(after.appliedOutcomes.isEmpty())
        assertTrue("a marker remains", after.tombstones.isNotEmpty())
        assertTrue(
            "the preference stored while ordering is untouched",
            after.facts.values.any { it.value == "spice.mild" },
        )
    }

    @Test
    fun `a deleted review is not restored by time passing again`() {
        val store = InMemoryStateStore()
        val surface = surface(store)
        placeOrder(surface)
        val turn = surface.submitReview(daon, "rating.ok", "간이 셌어")
        surface.rememberFromReview(turn.reviewId, turn.offers.single())
        surface.deleteReview(turn.reviewId)

        surface.nextOrderSession(ongi)
        surface.advanceTime()
        val relaunched = surface(store, process = "review-3")
        relaunched.advanceTime()

        assertTrue(
            "neither the scheduled nor the applied result came back",
            relaunched.state().facts.values.none { it.kind == FactKind.OUTCOME },
        )
        assertTrue(relaunched.state().pendingOutcomes.isEmpty())
        assertFalse(relaunched.state().encode().contains("간이 셌어"))
    }

    @Test
    fun `the review flow needs no model and no inference budget`() {
        val surface = surface()
        placeOrder(surface)
        val turn = surface.submitReview(daon, "rating.ok", "국물은 괜찮았는데 간이 좀 셌어")
        surface.rememberFromReview(turn.reviewId, turn.offers.single())

        assertFalse(intake.usesModel)
        assertEquals(0, intake.invocations)
        assertEquals(0, surface.state().modelInvocations)
    }
}
