package com.scpc.deliveryagent.delivery

import com.scpc.deliveryagent.core.AsprEngine
import com.scpc.deliveryagent.core.Ids
import com.scpc.deliveryagent.core.InMemoryStateStore
import com.scpc.deliveryagent.core.ProductionCore
import com.scpc.deliveryagent.core.ProductionState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog-wide completion guard before device testing.
 *
 * Every in-stock main menu is placed in a fresh production run, every required
 * option is answered with a value that restaurant actually offers, and the
 * resulting synthetic draft must commit exactly once. This catches a catalog
 * slot that the UI can show but no valid path can answer before an emulator run
 * turns it into a restaurant-specific dead end.
 */
class AllRestaurantCompletionTest {

    private val catalog = SyntheticCatalog.parse(
        File("src/main/assets/synthetic/catalog.json").readText(Charsets.UTF_8),
    )

    @Test
    fun `every restaurant can complete every in-stock main menu`() {
        var coveredRestaurants = 0
        var coveredMenus = 0

        catalog.restaurants.forEach { restaurant ->
            val menus = catalog.mainMenus(restaurant).filter { it.inStock }
            assertTrue("${restaurant.name} has no orderable main menu", menus.isNotEmpty())
            coveredRestaurants += 1

            menus.forEach { menu ->
                coveredMenus += 1
                val surface = ProductSurface(
                    ProductionCore(
                        store = InMemoryStateStore(),
                        processMarker = "complete-${Ids.segment(menu.token)}",
                        namespace = ProductionState.NAMESPACE_FULL,
                        asprEnabled = true,
                        runBinding = "COMPLETE-${Ids.segment(menu.token)}",
                    ),
                    catalog,
                )
                surface.startNewOrder(restaurant)
                surface.addLine(restaurant, menu.token)
                answerEveryRequiredField(surface, restaurant)

                val outcome = surface.requestDecision(restaurant)

                assertEquals(
                    "${restaurant.name}의 ${menu.label} 초안이 완료되지 않았다",
                    "ACT",
                    outcome.result.getString("decision_state"),
                )
                assertTrue(AsprEngine.openConfirmationIds(surface.state()).isEmpty())
                assertEquals(1, surface.state().actions.size)
            }
        }

        assertEquals("all six synthetic restaurants are covered", 6, coveredRestaurants)
        assertTrue("more than one route per restaurant is exercised", coveredMenus > 6)
    }

    @Test
    fun `claim-off can start an order and add every in-stock main menu`() {
        catalog.restaurants.forEach { restaurant ->
            catalog.mainMenus(restaurant).filter { it.inStock }.forEach { menu ->
                val surface = ProductSurface(
                    ProductionCore(
                        store = InMemoryStateStore(),
                        processMarker = "claim-off-${Ids.segment(menu.token)}",
                        namespace = ProductionState.NAMESPACE_CLAIM_OFF,
                        asprEnabled = false,
                        runBinding = "CLAIM-OFF-${Ids.segment(menu.token)}",
                    ),
                    catalog,
                )

                surface.startNewOrder(restaurant)
                surface.addLine(restaurant, menu.token)

                assertEquals(
                    "claim-off에서 ${restaurant.name}의 ${menu.label} 선택이 주문서에 들어가지 않았다",
                    listOf(menu.token),
                    surface.lines().mapNotNull { it.menuValueToken },
                )
            }
        }
    }

    /**
     * The same guard for a full basket. A judge who keeps adding from one menu
     * puts every line, sides included, into a single draft; each line brings its
     * own required options, and the order still has to commit exactly once.
     */
    @Test
    fun `every restaurant can complete one order holding its entire menu`() {
        catalog.restaurants.forEach { restaurant ->
            val menus = restaurant.menu.filter { it.inStock }
            assertTrue("${restaurant.name} has no orderable menu", menus.isNotEmpty())
            val surface = ProductSurface(
                ProductionCore(
                    store = InMemoryStateStore(),
                    processMarker = "basket-${Ids.segment(restaurant.entityToken)}",
                    namespace = ProductionState.NAMESPACE_FULL,
                    asprEnabled = true,
                    runBinding = "BASKET-${Ids.segment(restaurant.entityToken)}",
                ),
                catalog,
            )
            surface.startNewOrder(restaurant)
            menus.forEach { surface.addLine(restaurant, it.token) }
            answerEveryRequiredField(surface, restaurant)

            val outcome = surface.requestDecision(restaurant)

            assertEquals(
                "${restaurant.name}의 전체 메뉴 초안이 완료되지 않았다",
                "ACT",
                outcome.result.getString("decision_state"),
            )
            assertTrue(AsprEngine.openConfirmationIds(surface.state()).isEmpty())
            assertEquals(1, surface.state().actions.size)
        }
    }

    private fun answerEveryRequiredField(
        surface: ProductSurface,
        restaurant: RestaurantDefinition,
    ) {
        repeat(MAX_FIELDS) {
            val fieldId = AsprEngine.openConfirmationIds(surface.state()).firstOrNull()
                ?: return
            val field = surface.state().fields.getValue(fieldId)
            val line = LineTokens.parseSlotId(field.slotId)
            if (line != null) {
                require(line.baseScopeToken != Slots.MAIN) {
                    "the chosen menu unexpectedly reopened on ${line.lineId}"
                }
                if (line.baseScopeToken == Slots.QUANTITY) {
                    surface.setQuantity(restaurant, line.lineId, "qty.1")
                } else {
                    val slot = catalog.slot(line.baseScopeToken)
                    surface.setLineOption(
                        restaurant,
                        line.lineId,
                        slot,
                        firstUsableValue(restaurant, slot).token,
                    )
                }
            } else {
                val slot = catalog.slotOfFieldSlotId(field.slotId)
                    ?: error("no catalog slot for required field ${field.fieldId}")
                surface.remember(
                    restaurant,
                    slot,
                    firstUsableValue(restaurant, slot).token,
                    stable = false,
                )
            }
        }
        error("required fields did not converge within $MAX_FIELDS answers")
    }

    private fun firstUsableValue(
        restaurant: RestaurantDefinition,
        slot: SlotDefinition,
    ): OptionValue = catalog.valuesFor(restaurant, slot.scopeToken)
        .firstOrNull { it.inStock }
        ?: error("${restaurant.name} offers no usable value for ${slot.label}")

    companion object {
        private const val MAX_FIELDS = 32
    }
}
