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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reviewing an order, and what the app is allowed to learn from it.
 *
 * The property under test is restraint with named scope: a remark about one
 * order becomes an offer; the user picks the exact scope it may apply at — this
 * menu, this menu type anywhere, or everywhere — and only that scope gets a
 * stable fact and permission. Deleting the review takes back exactly what it
 * created. A review of a many-menu order stays unattributed until the user
 * names the menu it was about.
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

    private fun ProductSurface.lineField(lineId: String, base: String) =
        state().fields[LineTokens.optionFieldId(lineId, base)]

    /** Completes one single-menu order at 다온 so there is something to review. */
    private fun placeOrder(surface: ProductSurface): com.scpc.deliveryagent.core.StepOutcome {
        surface.startNewOrder(daon)
        surface.say(daon, "2만원 이하로 따뜻한 국물, 앞으로도 맵지 않게 해줘")
        surface.addLine(daon, "menu.daon.clear")
        surface.say(daon, "앞으로도 수저 빼고 밥은 보통으로")
        surface.remember(daon, catalog.slot(Slots.SALTINESS), "salt.normal", stable = false)
        surface.answerRemaining(catalog, daon)
        return surface.requestDecision(daon)
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
    fun `a packaging problem is understood as a non reusable observation`() {
        val reading = intake.readReview("포장이 찌그러져서 왔어요", null)
        assertTrue(reading.candidates.isEmpty())
        assertTrue(reading.observations.isNotEmpty())
        assertTrue(reading.unrecognised.isEmpty())
    }

    @Test
    fun `an unrated review is still readable`() {
        val reading = intake.readReview("간이 셌어", null)
        assertEquals(null, reading.ratingToken)
        assertEquals(1, reading.candidates.size)
    }

    @Test
    fun `compositional review rules cover ordinary wording without sentence enumeration`() {
        val cases = listOf(
            "염도가 꽤 강해서 먹기 힘들었어요" to (Slots.SALTINESS to "salt.light"),
            "간이 너무 약해서 싱거운 느낌이었어요" to (Slots.SALTINESS to "salt.normal"),
            "맵기가 세서 거의 못 먹겠더라고요" to (Slots.SPICINESS to "spice.mild"),
            "하나도 안 매워서 다음에는 더 맵게 먹고 싶어요" to
                (Slots.SPICINESS to "spice.very_hot"),
            "공기밥 양이 모자라서 더 필요했어요" to (Slots.RICE to "rice.large"),
            "밥이 너무 많아서 결국 남겼어요" to (Slots.RICE to "rice.small"),
            "고수 향이 강해서 못 먹겠어요" to (Slots.CILANTRO to "cilantro.exclude"),
            "치즈가 거의 없어서 아쉬웠어요" to (Slots.CHEESE to "cheese.add"),
            "달걀이 빠져서 아쉬웠어요" to (Slots.EGG to "egg.keep"),
            "국물이 적어서 더 있었으면 했어요" to ("option.broth" to "broth.more"),
            "튀김 식감이 눅눅했어요" to ("option.crispy" to "crispy.extra"),
        )

        cases.forEach { (review, expected) ->
            val reading = intake.readReview(review, null)
            assertEquals("one safe candidate for '$review': $reading", 1, reading.candidates.size)
            val candidate = reading.candidates.single()
            assertEquals("slot for '$review'", expected.first, candidate.slotToken)
            assertEquals("value for '$review'", expected.second, candidate.impliesValue)
            assertTrue("visible provenance keeps the user's words", candidate.matchedText.isNotBlank())
        }
    }

    @Test
    fun `subject omitted inflection and explicit next order requests remain actionable`() {
        val cases = listOf(
            "제 입에는 너무 짜서 물을 계속 마셨어요" to
                (Slots.SALTINESS to "salt.light"),
            "싱거워서 간이 안 된 줄 알았어요" to
                (Slots.SALTINESS to "salt.normal"),
            "맵찔이가 먹기에는 너무 맵네요" to
                (Slots.SPICINESS to "spice.mild"),
            "하나도 안 매워서 다음에는 더 맵게 해 주세요" to
                (Slots.SPICINESS to "spice.very_hot"),
            "다음엔 치즈 추가해 주세요" to
                (Slots.CHEESE to "cheese.add"),
            "앞으로 수저는 필요 없어요" to
                (Slots.UTENSIL to "utensil.exclude"),
            "수저가 안 와서 불편했어요" to
                (Slots.UTENSIL to "utensil.include"),
            "밀떡이라 쫄깃해서 좋았어요" to
                ("option.ricecake" to "ricecake.wheat"),
            "당면이 제 취향이었어요" to
                ("option.noodle" to "noodle.glass"),
            "단무지가 빠져서 아쉬웠어요" to
                ("option.pickle" to "pickle.include"),
            "다음부턴 단무지는 빼 주세요" to
                ("option.pickle" to "pickle.exclude"),
        )

        cases.forEach { (review, expected) ->
            val reading = intake.readReview(review, null)
            assertTrue(
                "'$review' should offer $expected but was $reading",
                reading.candidates.any {
                    it.slotToken == expected.first && it.impliesValue == expected.second
                },
            )
            assertTrue(
                "recognised Korean grammar should not be reported as unknown for '$review': " +
                    reading.unrecognised,
                reading.unrecognised.isEmpty(),
            )
        }
    }

    @Test
    fun `broad review topics become observations without inventing preferences`() {
        val reviews = listOf(
            "배달 기사님이 정말 친절했어요",
            "용기 뚜껑이 열려서 국물이 다 샜어요",
            "토핑 하나가 누락돼서 아쉬웠어요",
            "재료가 신선하고 포장도 깔끔했어요",
            "가격은 조금 비싸지만 맛은 좋았어요",
            "면이 너무 퍼져서 식감이 별로였어요",
            "양념이 제 입맛에는 너무 달았어요",
            "그냥 무난했어요",
            "또 시켜 먹을 것 같아요",
            "제 취향은 아니었어요",
        )

        reviews.forEach { review ->
            val reading = intake.readReview(review, null)
            assertTrue("'$review' should be an observation: $reading", reading.observations.isNotEmpty())
            assertTrue("'$review' must not invent memory: $reading", reading.candidates.isEmpty())
            assertTrue(
                "recognised review should not leave grammar as unknown for '$review': " +
                    reading.unrecognised,
                reading.unrecognised.isEmpty(),
            )
        }
    }

    @Test
    fun `negated praise does not become the opposite preference`() {
        listOf(
            "짜지 않고 간이 딱 맞아서 좋았어요" to Slots.SALTINESS,
            "고수 향이 강하지 않고 괜찮았어요" to Slots.CILANTRO,
        ).forEach { (review, slot) ->
            val reading = intake.readReview(review, null)
            assertTrue("negated review must not propose $slot: $reading", reading.candidates.none {
                it.slotToken == slot
            })
            assertTrue("the praise itself remains understood", reading.observations.isNotEmpty())
        }
    }

    @Test
    fun `delivery packaging and temperature complaints are understood but never become memory`() {
        listOf(
            "배송이 너무 늦어서 한참 기다렸어요",
            "포장 용기가 찌그러지고 국물이 샜어요",
            "음식이 다 식어서 차가웠어요",
        ).forEach { review ->
            val reading = intake.readReview(review, "rating.poor")
            assertTrue("'$review' is an observation", reading.observations.isNotEmpty())
            assertTrue("'$review' does not invent reusable memory", reading.candidates.isEmpty())
        }
    }

    @Test
    fun `conflicting review directions for one slot create no memory candidate`() {
        val reading = intake.readReview("처음에는 너무 매웠는데 나중에는 하나도 안 매웠어요", null)

        assertTrue(reading.candidates.none { it.slotToken == Slots.SPICINESS })
        assertTrue(reading.observations.isNotEmpty())
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
        assertFalse("a single-menu order needs no target question", turn.needsTarget)
        assertEquals("menu.daon.clear", turn.targetMenuToken)
        assertTrue(
            "the review is traceable to the order it was about",
            surface.reviews().single().actionId != null,
        )
        assertTrue("nothing was learned yet", surface.reviews().single().derivedFactIds.isEmpty())
        assertEquals(
            "no memory appeared from the review alone",
            before.facts.keys,
            surface.state().facts.keys,
        )
        assertTrue(
            "nothing but the app-local rating request is scheduled",
            surface.state().pendingOutcomes.all { it.value.startsWith("review.request.") },
        )
    }

    @Test
    fun `the scope choices offered match what the catalog authored`() {
        val surface = surface()
        placeOrder(surface)
        val turn = surface.submitReview(daon, "rating.ok", "간이 좀 셌어")
        val saltiness = turn.offers.single()

        assertEquals(
            listOf(
                PreferenceScopeLevel.RESTAURANT_MENU_OVERRIDE,
                PreferenceScopeLevel.MENU_TYPE,
                PreferenceScopeLevel.GLOBAL_DEFAULT,
            ),
            surface.reviewScopeChoices(daon, saltiness, turn.targetMenuToken),
        )

        // Spiciness is not an authored stable slot of the soup type, so the
        // cross-restaurant choice is not offered for it.
        val spicy = surface.submitReview(daon, "rating.ok", "너무 매웠어").offers.single()
        assertEquals(
            listOf(
                PreferenceScopeLevel.RESTAURANT_MENU_OVERRIDE,
                PreferenceScopeLevel.GLOBAL_DEFAULT,
            ),
            surface.reviewScopeChoices(daon, spicy, "menu.daon.clear"),
        )
    }

    @Test
    fun `declining an offer leaves the review and creates no memory`() {
        val surface = surface()
        placeOrder(surface)
        val turn = surface.submitReview(daon, "rating.poor", "간이 셌어")

        surface.dismissFromReview(turn.offers.single())

        assertEquals(1, surface.reviews().size)
        assertTrue(surface.reviews().single().derivedFactIds.isEmpty())
        assertTrue(
            surface.state().facts.values.none { it.value == "salt.light" },
        )
    }

    // ------------------------------------------------------------ accepting

    @Test
    fun `accepting for this menu only creates a scoped override, not a general rule`() {
        val surface = surface()
        placeOrder(surface)
        val turn = surface.submitReview(daon, "rating.ok", "국물은 괜찮았는데 간이 좀 셌어")

        surface.acceptFromReview(
            daon, turn.reviewId, turn.offers.single(),
            PreferenceScopeLevel.RESTAURANT_MENU_OVERRIDE,
        )

        val learned = surface.state().facts.values.single {
            it.kind == FactKind.STABLE && it.value == "salt.light"
        }
        assertEquals("menu.daon.clear", learned.scopeMenuId)
        assertEquals(Permission.AUTO_APPLY, learned.permission)
        assertEquals(
            "the review keeps the id of exactly what it created",
            listOf(learned.factId),
            surface.reviews().single().derivedFactIds,
        )
        assertEquals(turn.reviewId, learned.originReviewId)
    }

    @Test
    fun `review approval and ownership link share one evidence boundary`() {
        val surface = surface()
        placeOrder(surface)
        val turn = surface.submitReview(daon, "rating.ok", "간이 좀 셌어")

        val outcome = surface.acceptFromReview(
            daon,
            turn.reviewId,
            turn.offers.single(),
            PreferenceScopeLevel.RESTAURANT_MENU_OVERRIDE,
        )

        assertTrue(surface.reviews().single().derivedFactIds.isNotEmpty())
        assertEquals(
            "the result includes both the fact and its review ownership link",
            surface.state().digest(),
            outcome.result.getString("state_after_sha256"),
        )
    }

    @Test
    fun `an approved override applies to the same menu later and nowhere else`() {
        val surface = surface()
        placeOrder(surface)
        val turn = surface.submitReview(daon, "rating.ok", "간이 좀 셌어")
        surface.acceptFromReview(
            daon, turn.reviewId, turn.offers.single(),
            PreferenceScopeLevel.RESTAURANT_MENU_OVERRIDE,
        )

        // The same restaurant and menu: the override fills the line unasked.
        surface.nextOrderSession(daon)
        surface.addLine(daon, "menu.daon.clear")
        assertEquals("salt.light", surface.lineField("l1", Slots.SALTINESS)?.value)
        assertEquals(FieldStatus.AUTO_APPLIED, surface.lineField("l1", Slots.SALTINESS)?.status)

        // A different restaurant: the exact pair does not match, so it asks.
        surface.nextOrderSession(ongi)
        surface.addLine(ongi, "menu.ongi.perilla")
        assertEquals(
            "one bowl's remark does not follow the user across restaurants",
            FieldStatus.NEEDS_CONFIRMATION,
            surface.lineField("l1", Slots.SALTINESS)?.status,
        )
        assertNull(surface.lineField("l1", Slots.SALTINESS)?.value)
    }

    @Test
    fun `accepting for the menu type crosses restaurants on the authored type only`() {
        val surface = surface()
        placeOrder(surface)
        val turn = surface.submitReview(daon, "rating.ok", "간이 좀 셌어")
        surface.acceptFromReview(
            daon, turn.reviewId, turn.offers.single(), PreferenceScopeLevel.MENU_TYPE,
        )

        surface.nextOrderSession(ongi)
        surface.addLine(ongi, "menu.ongi.perilla")
        assertEquals(
            "the same authored soup type at another restaurant reuses the value",
            "salt.light",
            surface.lineField("l1", Slots.SALTINESS)?.value,
        )
        assertEquals(FieldStatus.AUTO_APPLIED, surface.lineField("l1", Slots.SALTINESS)?.status)
    }

    // ------------------------------------------------------------- deleting

    @Test
    fun `deleting a review takes back exactly what it created`() {
        val surface = surface()
        placeOrder(surface)
        // The same value also exists as the user's own independent global choice.
        surface.remember(daon, catalog.slot(Slots.SALTINESS), "salt.light", stable = true)
        val turn = surface.submitReview(daon, "rating.ok", "국물은 괜찮았는데 간이 좀 셌어")
        surface.acceptFromReview(
            daon, turn.reviewId, turn.offers.single(),
            PreferenceScopeLevel.RESTAURANT_MENU_OVERRIDE,
        )

        surface.deleteReview(turn.reviewId)
        val after = surface.state()

        assertTrue("the review is gone", after.reviews.isEmpty())
        assertFalse("the text is gone from state", after.encode().contains("국물은 괜찮았는데"))
        assertTrue(
            "the override the review created is gone",
            after.facts.values.none { it.scopeMenuId == "menu.daon.clear" && it.value == "salt.light" },
        )
        assertTrue("a marker remains", after.tombstones.isNotEmpty())
        assertTrue(
            "the user's own independent preference with the same value survives",
            after.facts.values.any {
                it.value == "salt.light" && it.scopeMenuId == null && it.menuTypeId == null
            },
        )
        assertTrue(
            "the preference stored while ordering is untouched",
            after.facts.values.any { it.value == "spice.mild" },
        )
    }

    @Test
    fun `deleting an old review preserves a newer direct instruction at the same scope`() {
        val surface = surface()
        placeOrder(surface)
        val turn = surface.submitReview(daon, "rating.ok", "간이 좀 셌어")
        surface.acceptFromReview(
            daon,
            turn.reviewId,
            turn.offers.single(),
            PreferenceScopeLevel.RESTAURANT_MENU_OVERRIDE,
        )

        // The same deterministic fact address now holds a newer instruction the
        // user stated independently of the review.
        surface.rememberForMenu(
            daon,
            catalog.slot(Slots.SALTINESS),
            "salt.normal",
            "menu.daon.clear",
        )
        val current = surface.state().facts.values.single {
            it.scopeMenuId == "menu.daon.clear" && it.kind == FactKind.STABLE
        }
        assertEquals("salt.normal", current.value)
        assertNull(current.originReviewId)

        surface.deleteReview(turn.reviewId)

        assertTrue(surface.reviews().isEmpty())
        assertTrue(
            "the current direct instruction is not owned by the deleted review",
            surface.state().facts.values.any {
                it.factId == current.factId && it.value == "salt.normal" &&
                    it.originReviewId == null
            },
        )
    }

    @Test
    fun `deleting a review preserves a newer correction of its scoped preference`() {
        val surface = surface()
        placeOrder(surface)
        val turn = surface.submitReview(daon, "rating.ok", "간이 좀 셌어")
        surface.acceptFromReview(
            daon,
            turn.reviewId,
            turn.offers.single(),
            PreferenceScopeLevel.RESTAURANT_MENU_OVERRIDE,
        )
        val scope = PreferenceScopes.menuOverride(
            Slots.SALTINESS,
            daon.entityToken,
            "menu.daon.clear",
        )

        surface.correctPreference(scope, "salt.normal")
        surface.deleteReview(turn.reviewId)

        assertTrue(
            "the corrected current-authority value survives its source review",
            surface.state().facts.values.any {
                it.scopeMenuId == "menu.daon.clear" && it.value == "salt.normal" &&
                    it.originReviewId == null
            },
        )
    }

    @Test
    fun `deleting a review does not delete an independent outcome with the same value`() {
        val surface = surface()
        placeOrder(surface)
        surface.scheduleOutcome(catalog.slot(Slots.SALTINESS), "salt.light")
        surface.advanceTime()
        val independentOutcome = surface.state().facts.values.single {
            it.kind == FactKind.OUTCOME && it.value == "salt.light"
        }
        val turn = surface.submitReview(daon, "rating.ok", "간이 좀 셌어")
        surface.acceptFromReview(
            daon,
            turn.reviewId,
            turn.offers.single(),
            PreferenceScopeLevel.RESTAURANT_MENU_OVERRIDE,
        )

        surface.deleteReview(turn.reviewId)

        assertTrue(
            "equal value tokens do not imply shared ownership",
            surface.state().facts[independentOutcome.factId]?.value == "salt.light",
        )
        assertTrue(surface.state().appliedOutcomes.containsKey("outcome.salt.light"))
    }

    @Test
    fun `a deleted review's memory is not restored by time or a restart`() {
        val store = InMemoryStateStore()
        val surface = surface(store)
        placeOrder(surface)
        val turn = surface.submitReview(daon, "rating.ok", "간이 셌어")
        surface.acceptFromReview(
            daon, turn.reviewId, turn.offers.single(),
            PreferenceScopeLevel.RESTAURANT_MENU_OVERRIDE,
        )
        surface.deleteReview(turn.reviewId)

        surface.nextOrderSession(daon)
        surface.advanceTime()
        val relaunched = surface(store, process = "review-2")
        relaunched.advanceTime()
        relaunched.nextOrderSession(daon)
        relaunched.addLine(daon, "menu.daon.clear")

        assertTrue(
            "the deleted override did not come back",
            relaunched.state().facts.values.none {
                it.scopeMenuId == "menu.daon.clear" && it.value == "salt.light"
            },
        )
        assertEquals(
            FieldStatus.NEEDS_CONFIRMATION,
            relaunched.lineField("l1", Slots.SALTINESS)?.status,
        )
        assertFalse(relaunched.state().encode().contains("간이 셌어"))
    }

    // ------------------------------------------------- the rating request

    @Test
    fun `the rating request arrives exactly once and expires on its own`() {
        val store = InMemoryStateStore()
        val surface = surface(store)
        val commit = placeOrder(surface)
        assertFalse("nothing arrived before time moves", surface.pendingEvaluationRequest())
        assertEquals(
            "commit and rating-request scheduling are one persisted transition",
            surface.state().digest(),
            commit.result.getString("state_after_sha256"),
        )

        surface.advanceTime()
        assertTrue("the request arrived with virtual time", surface.pendingEvaluationRequest())
        val arrivals = surface.state().appliedOutcomes.keys.filter { it.contains("review.request") }
        assertEquals(1, arrivals.size)

        // A restart and more time never deliver the same request again.
        val relaunched = surface(store, process = "review-3")
        relaunched.advanceTime()
        assertEquals(
            1,
            relaunched.state().appliedOutcomes.keys.count { it.contains("review.request") },
        )

        // It is a passing notice: shortly later it no longer stands anywhere.
        relaunched.advanceTime()
        assertFalse(relaunched.pendingEvaluationRequest())
    }

    @Test
    fun `answering with a review takes the arrived request down`() {
        val surface = surface()
        placeOrder(surface)
        surface.advanceTime()
        assertTrue(surface.pendingEvaluationRequest())

        surface.submitReview(daon, "rating.ok", "간이 셌어")

        assertFalse("the request was answered", surface.pendingEvaluationRequest())
        assertEquals(
            "the arrival stays recorded exactly once",
            1,
            surface.state().appliedOutcomes.keys.count { it.contains("review.request") },
        )
    }

    // ------------------------------------------------- ambiguous target

    @Test
    fun `a review of a many-menu order stays unattributed until the menu is named`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.addLine(ongi, "menu.ongi.perilla")
        surface.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.normal", stable = false)
        surface.remember(ongi, catalog.slot(Slots.RICE), "rice.normal", stable = false)
        surface.remember(ongi, catalog.slot(Slots.UTENSIL), "utensil.exclude", stable = false)
        surface.addLine(ongi, "menu.ongi.dumpling")
        surface.requestDecision(ongi)

        val turn = surface.submitReview(ongi, "rating.good", "간이 셌어")

        assertTrue("two menus were ordered, so the target has to be asked", turn.needsTarget)
        assertTrue("no offer is made before the target is settled", turn.offers.isEmpty())
        assertNull(surface.reviews().single().lineValueToken)
        assertTrue(
            "an unattributed rating is no menu's evidence",
            Recommender(catalog).tallies(surface.state(), ongi).isEmpty(),
        )

        val offers = surface.setReviewTarget(ongi, turn.reviewId, "menu.ongi.perilla")

        assertEquals(1, offers.size)
        assertEquals("menu.ongi.perilla", surface.reviews().single().lineValueToken)
        assertTrue(
            Recommender(catalog).tallies(surface.state(), ongi)
                .containsKey("menu.ongi.perilla"),
        )
    }

    @Test
    fun `the review flow needs no model and no inference budget`() {
        val surface = surface()
        placeOrder(surface)
        val turn = surface.submitReview(daon, "rating.ok", "국물은 괜찮았는데 간이 좀 셌어")
        surface.acceptFromReview(
            daon, turn.reviewId, turn.offers.single(),
            PreferenceScopeLevel.RESTAURANT_MENU_OVERRIDE,
        )

        assertFalse(intake.usesModel)
        assertEquals(0, intake.invocations)
        assertEquals(0, surface.state().modelInvocations)
    }
}
