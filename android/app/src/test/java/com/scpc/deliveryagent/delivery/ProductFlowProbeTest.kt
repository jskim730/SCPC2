package com.scpc.deliveryagent.delivery

import com.scpc.deliveryagent.core.FieldStatus
import com.scpc.deliveryagent.core.InMemoryStateStore
import com.scpc.deliveryagent.core.ProductionCore
import com.scpc.deliveryagent.core.ProductionState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walks the E1-E4 story through the product surface over the shipped synthetic
 * catalog, on the JVM.
 *
 * This is the product-level counterpart to the probe contract tests: it checks
 * what a person would actually see on the screens, so a device run is a
 * confirmation rather than a first look.
 */
class ProductFlowProbeTest {

    private val catalog = SyntheticCatalog.parse(
        File("src/main/assets/synthetic/catalog.json").readText(Charsets.UTF_8),
    )
    private val pricing = DraftPricing(catalog)
    private val daon = catalog.restaurant("restaurant.daon")!!
    private val ongi = catalog.restaurant("restaurant.ongi")!!
    private val bulkkot = catalog.restaurant("restaurant.bulkkot")!!

    private fun surface(): ProductSurface = ProductSurface(
        ProductionCore(
            store = InMemoryStateStore(),
            processMarker = "flow-process-1",
            namespace = ProductionState.NAMESPACE_FULL,
            asprEnabled = true,
            runBinding = "FLOW",
        ),
        catalog,
    )

    private fun ProductSurface.field(scopeToken: String) =
        state().fields[catalog.slot(scopeToken).fieldId]

    private fun decision(outcome: com.scpc.deliveryagent.core.StepOutcome): String =
        outcome.result.getString("decision_state")

    /** E1: learn the preferences and record a synthetic order. */
    private fun walkEpisodeOne(surface: ProductSurface) {
        surface.startNewOrder(daon)
        surface.remember(daon, catalog.slot(Slots.BUDGET), "budget.20000", stable = false)
        surface.remember(daon, catalog.slot(Slots.WARMTH), "warmth.soup", stable = false)
        surface.chooseLine(daon, catalog.slot(Slots.MAIN), "menu.daon.clear")
        surface.remember(daon, catalog.slot(Slots.SPICINESS), "spice.mild", stable = true)
        surface.remember(daon, catalog.slot(Slots.UTENSIL), "utensil.exclude", stable = true)
        surface.remember(daon, catalog.slot(Slots.RICE), "rice.normal", stable = true)
    }

    @Test
    fun `E1 completes an order and prices it from the catalog`() {
        val surface = surface()
        walkEpisodeOne(surface)
        val outcome = surface.requestDecision(daon)

        assertEquals("ACT", decision(outcome))
        assertEquals(11_500, pricing.total(surface.state()))
        assertEquals(28, pricing.estimateMinutes(surface.state()))
        assertTrue(
            "the order is inside the stated budget",
            pricing.total(surface.state()) <= pricing.budgetLimit(surface.state())!!,
        )
        assertEquals(
            "a synthetic order is recorded once",
            1,
            surface.state().actions.size,
        )
    }

    @Test
    fun `E2 reuses the permitted options at a new restaurant and only asks about the new one`() {
        val surface = surface()
        walkEpisodeOne(surface)
        surface.requestDecision(daon)

        surface.nextOrderSession(ongi)
        surface.chooseLine(ongi, catalog.slot(Slots.MAIN), "menu.ongi.perilla")
        val outcome = surface.requestDecision(ongi)

        assertEquals(
            "the stored spiciness is applied without asking again",
            FieldStatus.AUTO_APPLIED,
            surface.field(Slots.SPICINESS)?.status,
        )
        assertEquals(
            "the stored utensil choice is applied without asking again",
            FieldStatus.AUTO_APPLIED,
            surface.field(Slots.UTENSIL)?.status,
        )
        assertEquals("spice.mild", surface.field(Slots.SPICINESS)?.value)
        assertEquals(
            "the option this restaurant adds is the one still asked about",
            FieldStatus.NEEDS_CONFIRMATION,
            surface.field(Slots.SALTINESS)?.status,
        )
        assertEquals("ASK", decision(outcome))

        surface.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.light", stable = false)
        assertEquals("ACT", decision(surface.requestDecision(ongi)))
    }

    @Test
    fun `E3 applies a one-off over a stored preference without changing it`() {
        val surface = surface()
        walkEpisodeOne(surface)
        surface.requestDecision(daon)

        surface.nextOrderSession(bulkkot)
        surface.remember(bulkkot, catalog.slot(Slots.SPICINESS), "spice.very_hot", stable = false)
        assertEquals("spice.very_hot", surface.field(Slots.SPICINESS)?.value)
        assertTrue(
            "the stored preference itself is untouched",
            surface.state().facts.values.any {
                it.value == "spice.mild" && it.kind == com.scpc.deliveryagent.core.FactKind.STABLE
            },
        )

        // Revoking only the utensil permission keeps the stored value and asks again.
        surface.revokeAutoApply(catalog.slot(Slots.UTENSIL))
        assertEquals(
            FieldStatus.NEEDS_CONFIRMATION,
            surface.field(Slots.UTENSIL)?.status,
        )
        assertTrue(surface.state().facts.values.any { it.value == "utensil.exclude" })
        assertEquals(
            "another scope is unaffected",
            "spice.very_hot",
            surface.field(Slots.SPICINESS)?.value,
        )

        // Correcting the stored rice amount raises its authority.
        surface.correct(catalog.slot(Slots.RICE), "rice.small")
        assertEquals("rice.small", surface.field(Slots.RICE)?.value)

        // A new session expires the one-off and the stored preference returns.
        surface.nextOrderSession(bulkkot)
        assertEquals(
            "the one-off did not survive the session boundary",
            "spice.mild",
            surface.field(Slots.SPICINESS)?.value,
        )
        assertEquals(
            "the correction is the current authority in the new session",
            "rice.small",
            surface.field(Slots.RICE)?.value,
        )
        assertEquals(
            "the revoked permission still makes the app ask",
            FieldStatus.NEEDS_CONFIRMATION,
            surface.field(Slots.UTENSIL)?.status,
        )
    }

    @Test
    fun `E4 recovers only the sold-out line and keeps the rest`() {
        val surface = surface()
        walkEpisodeOne(surface)
        surface.requestDecision(daon)

        surface.nextOrderSession(ongi)
        surface.chooseLine(ongi, catalog.slot(Slots.MAIN), "menu.ongi.perilla")
        surface.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.light", stable = false)
        surface.chooseLine(ongi, catalog.slot(Slots.SIDE), "side.dumpling")
        surface.addRequestNote(ongi, "note.sauce_separate")
        assertEquals("ACT", decision(surface.requestDecision(ongi)))

        val totalBefore = pricing.total(surface.state())
        assertEquals(14_300, totalBefore)

        // The side goes out of stock at a higher catalog version.
        surface.applyCatalogEvent("catalog.ongi.side.dumpling.soldout")

        assertEquals(
            "the sold-out line has to be chosen again",
            FieldStatus.NEEDS_CONFIRMATION,
            surface.field(Slots.SIDE)?.status,
        )
        assertEquals("side.dumpling.soldout", surface.field(Slots.SIDE)?.value)
        assertEquals(
            "the main line the user confirmed is preserved",
            "menu.ongi.perilla",
            surface.field(Slots.MAIN)?.value,
        )
        assertEquals(FieldStatus.CONFIRMED, surface.field(Slots.MAIN)?.status)
        assertEquals(
            "an independent option is preserved",
            FieldStatus.AUTO_APPLIED,
            surface.field(Slots.UTENSIL)?.status,
        )
        assertNotEquals(
            "the total no longer counts the unavailable line",
            totalBefore,
            pricing.total(surface.state()),
        )
        assertEquals(
            "the agent will not act on an unavailable line",
            "ASK",
            decision(surface.requestDecision(ongi)),
        )

        // Deleting the one-time note removes the original and leaves a marker.
        surface.deleteRequestNote()
        assertFalse(surface.state().encode().contains("note.sauce_separate"))
        assertTrue(surface.state().tombstones.isNotEmpty())
        assertEquals(
            "the main line survives the deletion",
            "menu.ongi.perilla",
            surface.field(Slots.MAIN)?.value,
        )

        // Choosing a replacement completes the order again.
        surface.chooseLine(ongi, catalog.slot(Slots.SIDE), "side.rice_ball")
        assertEquals("ACT", decision(surface.requestDecision(ongi)))
        assertEquals(13_600, pricing.total(surface.state()))
    }

    @Test
    fun `a draft over the stated budget stops instead of ordering`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.remember(ongi, catalog.slot(Slots.BUDGET), "budget.10000", stable = false)
        surface.chooseLine(ongi, catalog.slot(Slots.MAIN), "menu.ongi.perilla")
        surface.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.light", stable = false)
        surface.remember(ongi, catalog.slot(Slots.RICE), "rice.normal", stable = false)
        surface.remember(ongi, catalog.slot(Slots.UTENSIL), "utensil.exclude", stable = false)
        surface.chooseLine(ongi, catalog.slot(Slots.SIDE), "side.rice_ball")

        val outcome = surface.requestDecision(ongi)
        assertTrue(pricing.total(surface.state()) > pricing.budgetLimit(surface.state())!!)
        assertEquals(
            "no draft satisfies the stated budget, so the agent stops",
            "ABSTAIN",
            decision(outcome),
        )
        assertTrue("nothing is ordered", surface.state().actions.isEmpty())
    }

    @Test
    fun `an option the current menu does not offer is refused`() {
        val surface = surface()
        surface.startNewOrder(daon)
        val refused = try {
            // 간 세기 is not part of this restaurant's option schema.
            surface.remember(daon, catalog.slot(Slots.SALTINESS), "salt.light", stable = false)
            false
        } catch (expected: IllegalArgumentException) {
            true
        }
        assertTrue("a value outside the current menu must be refused", refused)

        val wrongValue = try {
            surface.chooseLine(daon, catalog.slot(Slots.MAIN), "menu.ongi.perilla")
            false
        } catch (expected: IllegalArgumentException) {
            true
        }
        assertTrue("another restaurant's menu item must be refused", wrongValue)
    }

    @Test
    fun `a real restart keeps the order and does not complete what was not committed`() {
        val store = InMemoryStateStore()
        fun surfaceIn(process: String) = ProductSurface(
            ProductionCore(store, process, ProductionState.NAMESPACE_FULL, true, "FLOW"),
            catalog,
        )

        val first = surfaceIn("flow-process-1")
        first.startNewOrder(ongi)
        first.chooseLine(ongi, catalog.slot(Slots.MAIN), "menu.ongi.perilla")
        first.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.light", stable = false)
        val epochBefore = first.state().processEpoch

        // Same store, next process: this is what a real kill and relaunch looks like.
        val second = surfaceIn("flow-process-2")
        val afterRestart = second.requestDecision(ongi)

        assertTrue(second.state().processEpoch > epochBefore)
        assertEquals(
            "the chosen line survived the restart",
            "menu.ongi.perilla",
            second.field(Slots.MAIN)?.value,
        )
        assertTrue(
            "an order that was never confirmed is not reported as placed",
            decision(afterRestart) != "CONFIRMED_COMPLETE",
        )
    }
}
