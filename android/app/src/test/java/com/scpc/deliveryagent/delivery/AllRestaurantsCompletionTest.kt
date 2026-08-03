package com.scpc.deliveryagent.delivery

import com.scpc.deliveryagent.core.AsprEngine
import com.scpc.deliveryagent.core.InMemoryStateStore
import com.scpc.deliveryagent.core.ProductionCore
import com.scpc.deliveryagent.core.ProductionState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every restaurant, every menu, all the way to a committed order.
 *
 * A judge does not follow the script: they open any restaurant and pick any
 * menu. This walk drives the exact answering loop the screen offers — read the
 * open questions, tap the first in-stock value the restaurant offers — and
 * asserts every start reaches ACT. A menu whose required option cannot be
 * answered from the catalog would strand the judge on the spot; this test makes
 * that impossible to ship, and it keeps covering new data as the catalog grows.
 */
class AllRestaurantsCompletionTest {

    private val catalog = SyntheticCatalog.parse(
        File("src/main/assets/synthetic/catalog.json").readText(Charsets.UTF_8),
    )

    private fun surface(process: String) = ProductSurface(
        ProductionCore(InMemoryStateStore(), process, ProductionState.NAMESPACE_FULL, true, "ALL"),
        catalog,
    )

    /**
     * Answers open questions the way the screen does: first in-stock value the
     * restaurant offers for the asked slot. Fails, naming the walk and the slot,
     * when a question has no offerable answer or answering does not converge.
     */
    private fun answerUntilQuiet(surface: ProductSurface, restaurant: RestaurantDefinition, walk: String) {
        var rounds = 0
        while (true) {
            val state = surface.state()
            val open = AsprEngine.openConfirmationIds(state)
            if (open.isEmpty()) return
            if (rounds++ > open.size + 50) {
                fail("$walk: answering never converged; still open: ${open.map(catalog::slotLabel)}")
            }
            val fieldId = open.first()
            val field = state.fields.getValue(fieldId)
            val slot = catalog.slotOfFieldSlotId(field.slotId)
                ?: catalog.baseSlotOfLineSlotId(field.slotId)
                ?: fail("$walk: open question '${field.slotId}' maps to no catalog slot").let { return }
            val choice = catalog.valuesFor(restaurant, slot.scopeToken).firstOrNull { it.inStock }
                ?: fail("$walk: '${slot.label}' is asked but offers no in-stock value").let { return }
            val parsed = LineTokens.parseSlotId(field.slotId)
            when {
                parsed != null && parsed.baseScopeToken == Slots.MAIN ->
                    surface.chooseLineMenu(restaurant, parsed.lineId, choice.token)

                parsed != null && parsed.baseScopeToken == Slots.QUANTITY ->
                    surface.setQuantity(restaurant, parsed.lineId, choice.token)

                parsed != null ->
                    surface.setLineOption(restaurant, parsed.lineId, catalog.slot(parsed.baseScopeToken), choice.token)

                else -> surface.remember(restaurant, slot, choice.token, stable = false)
            }
        }
    }

    private fun completeOrder(restaurant: RestaurantDefinition, menus: List<OptionValue>, walk: String) {
        val surface = surface(walk.replace(Regex("[^a-z0-9.]"), "-"))
        try {
            surface.startNewOrder(restaurant)
            menus.forEach { surface.addLine(restaurant, it.token) }
            answerUntilQuiet(surface, restaurant, walk)
        } catch (error: AssertionError) {
            throw error
        } catch (error: Exception) {
            fail("$walk: the walk itself failed — ${error.message}")
        }
        val outcome = surface.requestDecision(restaurant)
        assertEquals(
            "$walk should reach a committed order",
            "ACT",
            outcome.result.getString("decision_state"),
        )
        assertEquals("$walk commits exactly once", 1, surface.state().actions.size)
    }

    @Test
    fun `every main menu of every restaurant completes an order on its own`() {
        val walks = catalog.restaurants.flatMap { restaurant ->
            restaurant.menu
                .filter { catalog.menuTypeOf(it.token)?.course == MenuCourse.MAIN }
                .map { menu -> restaurant to menu }
        }
        assertEquals(
            "every restaurant must offer at least one main menu",
            catalog.restaurants.map { it.entityToken }.toSet(),
            walks.map { (restaurant, _) -> restaurant.entityToken }.toSet(),
        )
        walks.forEach { (restaurant, menu) ->
            completeOrder(restaurant, listOf(menu), "${restaurant.name} × ${menu.label}")
        }
    }

    @Test
    fun `every restaurant completes one order holding its entire menu`() {
        catalog.restaurants.forEach { restaurant ->
            assertTrue("${restaurant.name} has an empty menu", restaurant.menu.isNotEmpty())
            completeOrder(restaurant, restaurant.menu, "${restaurant.name} × 전체 메뉴")
        }
    }
}
