package com.scpc.deliveryagent.delivery

import com.scpc.deliveryagent.core.FieldStatus
import com.scpc.deliveryagent.core.InMemoryStateStore
import com.scpc.deliveryagent.core.ProductionCore
import com.scpc.deliveryagent.core.ProductionState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One order, several lines.
 *
 * The contract under test: every line has its own identity, options, price and
 * dependencies; a preference fills every line it is scoped for; an instruction
 * addressed at one line stays on that line; and removing or losing one line
 * never touches the others or the stored memory.
 */
class MultiLineDraftTest {

    private val catalog = SyntheticCatalog.parse(
        File("src/main/assets/synthetic/catalog.json").readText(Charsets.UTF_8),
    )
    private val pricing = DraftPricing(catalog)
    private val ongi = catalog.restaurant("restaurant.ongi")!!

    private fun surface(
        store: InMemoryStateStore = InMemoryStateStore(),
        process: String = "multi-1",
    ) = ProductSurface(
        ProductionCore(store, process, ProductionState.NAMESPACE_FULL, true, "MULTI"),
        catalog,
    )

    private fun ProductSurface.menuField(lineId: String) =
        state().fields[LineTokens.menuFieldId(lineId)]

    private fun ProductSurface.lineField(lineId: String, base: String) =
        state().fields[LineTokens.optionFieldId(lineId, base)]

    private fun decision(outcome: com.scpc.deliveryagent.core.StepOutcome): String =
        outcome.result.getString("decision_state")

    @Test
    fun `two menus of one restaurant are two lines with one shared preference`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.addLine(ongi, "menu.ongi.perilla")
        surface.addLine(ongi, "menu.ongi.tofu")
        surface.remember(ongi, catalog.slot(Slots.RICE), "rice.normal", stable = false)
        surface.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.light", stable = false)

        assertEquals("menu.ongi.perilla", surface.menuField("l1")?.value)
        assertEquals("menu.ongi.tofu", surface.menuField("l2")?.value)
        assertEquals(
            "one stated option fills every line that carries the slot",
            "rice.normal",
            surface.lineField("l1", Slots.RICE)?.value,
        )
        assertEquals("rice.normal", surface.lineField("l2", Slots.RICE)?.value)
        assertEquals(10_800 + 10_200, pricing.total(surface.state()))
    }

    @Test
    fun `the same menu twice keeps two identities and two option sets`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.addLine(ongi, "menu.ongi.perilla")
        surface.addLine(ongi, "menu.ongi.perilla")
        surface.setLineOption(ongi, "l1", catalog.slot(Slots.SALTINESS), "salt.light")
        surface.setLineOption(ongi, "l2", catalog.slot(Slots.SALTINESS), "salt.normal")

        assertEquals("salt.light", surface.lineField("l1", Slots.SALTINESS)?.value)
        assertEquals(
            "the second bowl keeps its own saltiness",
            "salt.normal",
            surface.lineField("l2", Slots.SALTINESS)?.value,
        )
        assertEquals(2 * 10_800, pricing.total(surface.state()))
    }

    @Test
    fun `an instruction addressed at one line beats the shared value on that line only`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.addLine(ongi, "menu.ongi.perilla")
        surface.addLine(ongi, "menu.ongi.tofu")
        surface.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.light", stable = false)
        surface.setLineOption(ongi, "l2", catalog.slot(Slots.SALTINESS), "salt.normal")

        assertEquals("salt.light", surface.lineField("l1", Slots.SALTINESS)?.value)
        assertEquals("salt.normal", surface.lineField("l2", Slots.SALTINESS)?.value)
    }

    @Test
    fun `quantity multiplies one line and the total`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.addLine(ongi, "menu.ongi.perilla")
        surface.addLine(ongi, "menu.ongi.dumpling")
        surface.setQuantity(ongi, "l2", "qty.2")

        assertEquals(2, pricing.quantityOf(surface.state(), "l2"))
        assertEquals(1, pricing.quantityOf(surface.state(), "l1"))
        assertEquals(10_800 + 2 * 3_500, pricing.total(surface.state()))
        assertTrue(
            "the priced line says how many it counts",
            pricing.lines(surface.state()).single { it.lineId == "l2" }.valueLabel.contains("×2"),
        )
    }

    @Test
    fun `a product confirmation is included in the step digest and evidence boundary`() {
        val surface = surface()
        val start = surface.startNewOrder(ongi)
        val add = surface.addLine(ongi, "menu.ongi.perilla")

        assertEquals(
            "the line schema and menu choice form one recorded transition",
            start.result.getString("state_after_sha256"),
            add.result.getString("state_before_sha256"),
        )
        assertEquals(surface.state().digest(), add.result.getString("state_after_sha256"))

        val quantity = surface.setQuantity(ongi, "l1", "qty.2")

        assertEquals(
            "the persisted field is already confirmed when the step returns",
            FieldStatus.CONFIRMED,
            surface.state().fields[
                com.scpc.deliveryagent.core.AsprEngine.fieldIdFor(
                    LineTokens.quantitySlotId("l1"),
                )
            ]?.status,
        )
        assertEquals(
            "state_after describes the state the product actually persisted",
            surface.state().digest(),
            quantity.result.getString("state_after_sha256"),
        )

        val next = surface.requestDecision(ongi)
        assertEquals(
            "consecutive product operations chain without an unrecorded write",
            quantity.result.getString("state_after_sha256"),
            next.result.getString("state_before_sha256"),
        )
        assertEquals(
            "the post-commit rating request is also inside the decision boundary",
            surface.state().digest(),
            next.result.getString("state_after_sha256"),
        )
    }

    @Test
    fun `removing one line keeps the other line and the stored preferences`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.addLine(ongi, "menu.ongi.perilla")
        surface.addLine(ongi, "menu.ongi.dumpling")
        surface.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.light", stable = true)
        assertEquals(10_800 + 3_500, pricing.total(surface.state()))

        val removal = surface.removeLine(ongi, "l2")!!

        assertNull("the removed line's menu is gone", surface.menuField("l2"))
        assertEquals("menu.ongi.perilla", surface.menuField("l1")?.value)
        assertEquals("salt.light", surface.lineField("l1", Slots.SALTINESS)?.value)
        assertEquals(10_800, pricing.total(surface.state()))
        assertTrue(
            "the stored preference is untouched by removing a line",
            surface.state().facts.values.any { it.value == "salt.light" },
        )
        assertTrue("removal leaves a marker", surface.state().tombstones.isNotEmpty())
        assertEquals(
            "the returned deletion result includes the final line structure",
            surface.state().digest(),
            removal.result.getString("state_after_sha256"),
        )
    }

    @Test
    fun `a removed line does not come back as a stray field after a restart`() {
        val store = InMemoryStateStore()
        val first = surface(store)
        first.startNewOrder(ongi)
        first.addLine(ongi, "menu.ongi.perilla")
        first.addLine(ongi, "menu.ongi.dumpling")
        first.removeLine(ongi, "l2")

        val second = surface(store, process = "multi-2")
        second.requestDecision(ongi)

        assertNull(second.menuField("l2"))
        assertFalse(
            "no field of the removed line survives the restart",
            second.state().fields.keys.any { it.contains("line.l2") },
        )
        assertEquals("menu.ongi.perilla", second.menuField("l1")?.value)
    }

    @Test
    fun `a line added after a removal gets a fresh identity`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.addLine(ongi, "menu.ongi.perilla")
        surface.addLine(ongi, "menu.ongi.dumpling")
        surface.removeLine(ongi, "l2")
        surface.addLine(ongi, "menu.ongi.riceball")

        assertEquals(
            "the removed line id is never reused inside one draft",
            listOf("l1", "l3"),
            surface.lines().map { it.lineId },
        )
        assertEquals("menu.ongi.riceball", surface.menuField("l3")?.value)
        assertEquals(10_800 + 2_800, pricing.total(surface.state()))
    }

    @Test
    fun `one commit covers the whole line set and a restart does not duplicate it`() {
        val store = InMemoryStateStore()
        val surface = surface(store)
        surface.startNewOrder(ongi)
        surface.addLine(ongi, "menu.ongi.perilla")
        surface.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.light", stable = false)
        surface.remember(ongi, catalog.slot(Slots.RICE), "rice.normal", stable = false)
        surface.remember(ongi, catalog.slot(Slots.UTENSIL), "utensil.exclude", stable = false)
        surface.addLine(ongi, "menu.ongi.dumpling")
        assertEquals("ACT", decision(surface.requestDecision(ongi)))
        assertEquals(1, surface.state().actions.size)

        // Asking again over the same draft, and asking again after a real restart,
        // both reuse the same committed action.
        assertEquals("ACT", decision(surface.requestDecision(ongi)))
        val relaunched = surface(store, process = "multi-3")
        assertEquals("ACT", decision(relaunched.requestDecision(ongi)))
        assertEquals(
            "the same draft identity never commits twice",
            1,
            relaunched.state().actions.size,
        )
    }

    @Test
    fun `losing one line to stock re-opens only that line and the total`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.addLine(ongi, "menu.ongi.perilla")
        surface.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.light", stable = false)
        surface.remember(ongi, catalog.slot(Slots.RICE), "rice.normal", stable = false)
        surface.remember(ongi, catalog.slot(Slots.UTENSIL), "utensil.exclude", stable = false)
        surface.addLine(ongi, "menu.ongi.dumpling")
        assertEquals("ACT", decision(surface.requestDecision(ongi)))

        surface.applyCatalogEvent("catalog.ongi.menu.dumpling.soldout")

        assertEquals(FieldStatus.NEEDS_CONFIRMATION, surface.menuField("l2")?.status)
        assertEquals(FieldStatus.CONFIRMED, surface.menuField("l1")?.status)
        assertEquals(
            "the other line's option is preserved",
            "salt.light",
            surface.lineField("l1", Slots.SALTINESS)?.value,
        )
        assertEquals(10_800, pricing.total(surface.state()))
    }
}
