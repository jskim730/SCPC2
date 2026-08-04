package com.scpc.deliveryagent.delivery

import com.scpc.deliveryagent.core.FieldStatus
import com.scpc.deliveryagent.core.InMemoryStateStore
import com.scpc.deliveryagent.core.ProductionCore
import com.scpc.deliveryagent.core.ProductionState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    private val marahyang = catalog.restaurant("restaurant.marahyang")!!
    private val hanbam = catalog.restaurant("restaurant.hanbam")!!
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

    private fun ProductSurface.lineField(lineId: String, baseScopeToken: String) =
        state().fields[LineTokens.optionFieldId(lineId, baseScopeToken)]

    private fun ProductSurface.menuField(lineId: String) =
        state().fields[LineTokens.menuFieldId(lineId)]

    private fun decision(outcome: com.scpc.deliveryagent.core.StepOutcome): String =
        outcome.result.getString("decision_state")

    /** E1: learn the preferences and record a synthetic order. */
    private fun walkEpisodeOne(surface: ProductSurface) {
        surface.startNewOrder(daon)
        surface.remember(daon, catalog.slot(Slots.BUDGET), "budget.20000", stable = false)
        surface.remember(daon, catalog.slot(Slots.WARMTH), "warmth.soup", stable = false)
        surface.addLine(daon, "menu.daon.clear")
        surface.remember(daon, catalog.slot(Slots.SPICINESS), "spice.mild", stable = true)
        surface.remember(daon, catalog.slot(Slots.SALTINESS), "salt.normal", stable = false)
        surface.remember(daon, catalog.slot(Slots.UTENSIL), "utensil.exclude", stable = true)
        surface.remember(daon, catalog.slot(Slots.RICE), "rice.normal", stable = true)
    }

    @Test
    fun `the first restaurant choice immediately becomes the active recommendation target`() {
        val surface = surface()

        surface.startNewOrder(daon)

        assertEquals(daon.entityToken, surface.state().targetEntityId)
        assertTrue(
            "choosing a restaurant leaves menu candidates available before any chat input",
            Recommender(catalog).candidates(surface.state(), daon).isNotEmpty(),
        )
    }

    @Test
    fun `E1 completes an order and prices it from the catalog`() {
        val surface = surface()
        walkEpisodeOne(surface)
        surface.answerRemaining(catalog, daon)
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
        surface.addLine(ongi, "menu.ongi.perilla")
        val outcome = surface.requestDecision(ongi)

        // 온기한상 does not offer a spiciness option on anything it sells, so the
        // stored 순한맛 has nothing to attach to here. It used to become an
        // order-level row all the same — a value the user could open and never
        // answer, on a draft whose kitchen has no such choice. Reuse is shown by
        // the options this restaurant actually has.
        assertNull(
            "an option this restaurant does not offer raises no row at all",
            surface.field(Slots.SPICINESS),
        )
        assertTrue(
            "and the preference itself is untouched, waiting for a menu that has it",
            surface.storedPreferencesFor(Slots.SPICINESS).any { it.value == "spice.mild" },
        )
        assertEquals(
            "the stored utensil choice is applied without asking again",
            FieldStatus.AUTO_APPLIED,
            surface.field(Slots.UTENSIL)?.status,
        )
        assertEquals(
            "the stored rice amount fills this line without asking again",
            FieldStatus.AUTO_APPLIED,
            surface.lineField("l1", Slots.RICE)?.status,
        )
        assertEquals(
            "the option this restaurant adds is the one still asked about",
            FieldStatus.NEEDS_CONFIRMATION,
            surface.lineField("l1", Slots.SALTINESS)?.status,
        )
        assertEquals("ASK", decision(outcome))

        surface.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.light", stable = false)
        surface.answerRemaining(catalog, ongi)
        assertEquals("ACT", decision(surface.requestDecision(ongi)))
    }

    @Test
    fun `a draft carries only the options its own dishes have`() {
        // 한밤국수's 잔치국수 has 간·밥양·계란·국물양·파 and no 고수. A 고수 preference
        // learned over 마라탕 showed up on it anyway, as a row whose 변경 button
        // could be opened and then refused every value it was offered.
        val surface = surface()
        surface.startNewOrder(marahyang)
        surface.addLine(marahyang, "menu.marahyang.malatang")
        surface.remember(marahyang, catalog.slot(Slots.CILANTRO), "cilantro.exclude", stable = true)
        surface.remember(marahyang, catalog.slot(Slots.UTENSIL), "utensil.exclude", stable = true)
        assertNotNull(
            "it is a row where the dish has the option",
            surface.lineField("l1", Slots.CILANTRO),
        )

        surface.nextOrderSession(hanbam)
        surface.addLine(hanbam, "menu.hanbam.janchi")

        val cilantroRows = surface.state().fields.values.filter { field ->
            field.slotId.contains(Slots.CILANTRO.removePrefix("option."))
        }
        assertTrue(
            "no 고수 row anywhere on a draft whose kitchen has none ($cilantroRows)",
            cilantroRows.isEmpty(),
        )
        assertEquals(
            "while the utensil, which the restaurant declares order-level, is reused",
            FieldStatus.AUTO_APPLIED,
            surface.field(Slots.UTENSIL)?.status,
        )
        assertTrue(
            "and the 고수 preference is kept for a menu that has it",
            surface.storedPreferencesFor(Slots.CILANTRO).any { it.value == "cilantro.exclude" },
        )
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
        surface.addLine(ongi, "menu.ongi.perilla")
        surface.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.light", stable = false)
        surface.addLine(ongi, "menu.ongi.dumpling")
        surface.addRequestNote(ongi, "note.sauce_separate")
        surface.answerRemaining(catalog, ongi)
        assertEquals("ACT", decision(surface.requestDecision(ongi)))

        val totalBefore = pricing.total(surface.state())
        assertEquals(14_300, totalBefore)

        // The side line goes out of stock at a higher catalog version.
        surface.applyCatalogEvent("catalog.ongi.menu.dumpling.soldout")

        assertEquals(
            "the sold-out line has to be chosen again",
            FieldStatus.NEEDS_CONFIRMATION,
            surface.menuField("l2")?.status,
        )
        assertEquals("menu.ongi.dumpling.soldout", surface.menuField("l2")?.value)
        assertEquals(
            "the main line the user confirmed is preserved",
            "menu.ongi.perilla",
            surface.menuField("l1")?.value,
        )
        assertEquals(FieldStatus.CONFIRMED, surface.menuField("l1")?.status)
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
            surface.menuField("l1")?.value,
        )

        // Choosing a replacement for that line completes the order again.
        surface.chooseLineMenu(ongi, "l2", "menu.ongi.riceball")
        surface.answerRemaining(catalog, ongi)
        assertEquals("ACT", decision(surface.requestDecision(ongi)))
        assertEquals(13_600, pricing.total(surface.state()))
    }

    @Test
    fun `a draft over the stated budget stops instead of ordering`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.remember(ongi, catalog.slot(Slots.BUDGET), "budget.10000", stable = false)
        surface.addLine(ongi, "menu.ongi.perilla")
        surface.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.light", stable = false)
        surface.remember(ongi, catalog.slot(Slots.RICE), "rice.normal", stable = false)
        surface.remember(ongi, catalog.slot(Slots.UTENSIL), "utensil.exclude", stable = false)
        surface.addLine(ongi, "menu.ongi.riceball")
        surface.answerRemaining(catalog, ongi)

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
        surface.startNewOrder(ongi)
        val refused = try {
            // 맵기 is not part of this restaurant's option schema.
            surface.remember(ongi, catalog.slot(Slots.SPICINESS), "spice.mild", stable = false)
            false
        } catch (expected: IllegalArgumentException) {
            true
        }
        assertTrue("a value outside the current menu must be refused", refused)

        val wrongValue = try {
            surface.addLine(ongi, "menu.daon.clear")
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
        first.addLine(ongi, "menu.ongi.perilla")
        first.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.light", stable = false)
        val epochBefore = first.state().processEpoch

        // Same store, next process: this is what a real kill and relaunch looks like.
        val second = surfaceIn("flow-process-2")
        val afterRestart = second.requestDecision(ongi)

        assertTrue(second.state().processEpoch > epochBefore)
        assertEquals(
            "the chosen line survived the restart",
            "menu.ongi.perilla",
            second.menuField("l1")?.value,
        )
        assertTrue(
            "an order that was never confirmed is not reported as placed",
            decision(afterRestart) != "CONFIRMED_COMPLETE",
        )
    }
}
