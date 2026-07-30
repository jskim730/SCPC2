package com.scpc.deliveryagent.delivery

import com.scpc.deliveryagent.core.InMemoryStateStore
import com.scpc.deliveryagent.core.ProductionCore
import com.scpc.deliveryagent.core.ProductionState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ratings the user left on earlier orders, shown so they can compare.
 *
 * The property under test is that they are shown and not scored: the average
 * appears next to the price and the estimate, and the ranking is exactly what it
 * would be without any ratings at all.
 */
class RatingDisplayTest {

    private val catalog = SyntheticCatalog.parse(
        File("src/main/assets/synthetic/catalog.json").readText(Charsets.UTF_8),
    )
    private val recommender = Recommender(catalog)
    private val daon = catalog.restaurant("restaurant.daon")!!
    private val ongi = catalog.restaurant("restaurant.ongi")!!

    private fun surface(
        store: InMemoryStateStore = InMemoryStateStore(),
        asprEnabled: Boolean = true,
    ) = ProductSurface(
        ProductionCore(
            store,
            "rating-1",
            if (asprEnabled) ProductionState.NAMESPACE_FULL else ProductionState.NAMESPACE_CLAIM_OFF,
            asprEnabled,
            "RATING",
        ),
        catalog,
    )

    /** Orders [line] at [restaurant] and rates it. */
    private fun orderAndRate(
        surface: ProductSurface,
        restaurant: RestaurantDefinition,
        line: String,
        rating: String,
        text: String = "",
    ) {
        surface.nextOrderSession(restaurant)
        surface.chooseLine(restaurant, catalog.slot(Slots.MAIN), line)
        catalog.slotsOf(restaurant)
            .filter { it.required && it.scopeToken != Slots.MAIN }
            .forEach { slot ->
                catalog.valuesFor(restaurant, slot.scopeToken).firstOrNull()?.let { value ->
                    surface.remember(restaurant, slot, value.token, stable = false)
                }
            }
        surface.requestDecision(restaurant)
        surface.submitReview(restaurant, rating, text)
    }

    @Test
    fun `the average is on the declared scale and counts the orders`() {
        val surface = surface()
        surface.startNewOrder(daon)
        orderAndRate(surface, daon, "menu.daon.clear", "rating.good")
        orderAndRate(surface, daon, "menu.daon.clear", "rating.ok")

        val tally = recommender.tallies(surface.state(), daon).getValue("menu.daon.clear")
        assertEquals(2, tally.count)
        assertEquals(5, tally.scaleMax)
        assertEquals(4.0, tally.average!!, 0.001)
        assertEquals("내 평점 4.0/5 (2건)", tally.label())
        assertTrue(tally.breakdown().contains("좋았어요"))
    }

    @Test
    fun `an unrated line reports no rating rather than a zero`() {
        val surface = surface()
        surface.startNewOrder(daon)
        orderAndRate(surface, daon, "menu.daon.clear", "rating.good")

        val tallies = recommender.tallies(surface.state(), daon)
        assertNotNull(tallies["menu.daon.clear"])
        assertNull("an unordered line has no tally", tallies["menu.daon.spicy"])

        val spicy = recommender.candidates(surface.state(), daon)
            .single { it.valueToken == "menu.daon.spicy" }
        assertEquals(0, spicy.rating.count)
        assertNull(spicy.rating.average)
        assertEquals("내 평점 없음", spicy.rating.label())
    }

    @Test
    fun `the restaurant average covers every rated order there`() {
        val surface = surface()
        surface.startNewOrder(daon)
        orderAndRate(surface, daon, "menu.daon.clear", "rating.good")
        orderAndRate(surface, daon, "menu.daon.spicy", "rating.poor")

        val tally = recommender.restaurantTally(surface.state(), daon)
        assertEquals(2, tally.count)
        assertEquals(3.0, tally.average!!, 0.001)
    }

    @Test
    fun `a rating at one restaurant says nothing about another`() {
        val surface = surface()
        surface.startNewOrder(daon)
        orderAndRate(surface, daon, "menu.daon.clear", "rating.good")

        assertEquals(
            "another restaurant's menu carries no rating from here",
            0,
            recommender.restaurantTally(surface.state(), ongi).count,
        )
        assertTrue(recommender.tallies(surface.state(), ongi).isEmpty())
        recommender.candidates(surface.state(), ongi).forEach { candidate ->
            assertEquals(0, candidate.rating.count)
        }
    }

    @Test
    fun `ratings do not move the ranking`() {
        // One run with no ratings, one where the lower-ranked line is rated well and
        // the higher-ranked line is rated badly. The order must be identical.
        fun ranking(withRatings: Boolean): List<String> {
            val surface = surface()
            surface.startNewOrder(daon)
            surface.say(daon, "따뜻한 국물, 앞으로도 맵지 않게 해줘")
            if (withRatings) {
                orderAndRate(surface, daon, "menu.daon.spicy", "rating.good")
                orderAndRate(surface, daon, "menu.daon.clear", "rating.poor")
                surface.nextOrderSession(daon)
                surface.say(daon, "따뜻한 국물")
            }
            return recommender.candidates(surface.state(), daon).map { it.valueToken }
        }

        val plain = ranking(withRatings = false)
        val rated = ranking(withRatings = true)
        assertEquals(
            "a well rated dish must not climb over what the user asked for",
            plain,
            rated,
        )
        assertEquals("menu.daon.clear", rated.first())
    }

    @Test
    fun `a rating is never a reason for the ranking`() {
        val surface = surface()
        surface.startNewOrder(daon)
        orderAndRate(surface, daon, "menu.daon.clear", "rating.good")

        recommender.candidates(surface.state(), daon).forEach { candidate ->
            candidate.reasons.forEach { reason ->
                assertTrue(
                    "'${reason.badge}' must not be a rating badge",
                    reason.badge in setOf("오늘 입력", "직접 저장", "지난 평가", "현재 메뉴정보"),
                )
            }
        }
    }

    @Test
    fun `a poorly rated dish is still offerable`() {
        val surface = surface()
        surface.startNewOrder(daon)
        orderAndRate(surface, daon, "menu.daon.clear", "rating.poor")

        val candidate = recommender.candidates(surface.state(), daon)
            .single { it.valueToken == "menu.daon.clear" }
        assertTrue("a rating never blocks a choice", candidate.offerable)
        assertNull(candidate.blockedBy)
        assertEquals(1, candidate.rating.negative)
    }

    @Test
    fun `both comparison arms see the same ratings`() {
        fun averageIn(asprEnabled: Boolean): Double? {
            val surface = surface(asprEnabled = asprEnabled)
            surface.startNewOrder(daon)
            orderAndRate(surface, daon, "menu.daon.clear", "rating.good")
            orderAndRate(surface, daon, "menu.daon.clear", "rating.ok")
            return recommender.restaurantTally(surface.state(), daon).average
        }
        assertEquals(
            "order history is shared infrastructure, so the gain stays attributable",
            averageIn(asprEnabled = true),
            averageIn(asprEnabled = false),
        )
    }

    @Test
    fun `deleting a review removes it from the average`() {
        val surface = surface()
        surface.startNewOrder(daon)
        orderAndRate(surface, daon, "menu.daon.clear", "rating.good")
        orderAndRate(surface, daon, "menu.daon.clear", "rating.poor")
        assertEquals(3.0, recommender.restaurantTally(surface.state(), daon).average!!, 0.001)

        val poor = surface.reviews().single { it.ratingToken == "rating.poor" }
        surface.deleteReview(poor.reviewId)

        val tally = recommender.restaurantTally(surface.state(), daon)
        assertEquals(1, tally.count)
        assertEquals(5.0, tally.average!!, 0.001)
    }

    @Test
    fun `a review with no rating does not enter the average`() {
        val surface = surface()
        surface.startNewOrder(daon)
        orderAndRate(surface, daon, "menu.daon.clear", "rating.good")
        surface.submitReview(daon, null, "간이 셌어")

        val tally = recommender.restaurantTally(surface.state(), daon)
        assertEquals("only rated orders count", 1, tally.count)
        assertEquals(5.0, tally.average!!, 0.001)
    }
}
