package com.scpc.deliveryagent.delivery

import com.scpc.deliveryagent.core.AsprEngine

/**
 * Answers whatever the draft still has open, the way the screen's chips do:
 * the first value this restaurant actually offers for the asked slot.
 *
 * A test says what it is about — the preference it stores, the correction it
 * makes, the line it loses to stock — and then calls this before asking for a
 * decision. Authoring one more option onto a menu therefore does not turn every
 * order-completing test red while leaving each test's own assertions in charge
 * of what it actually checks.
 *
 * The chosen menu is never answered here. When a line's menu is open, because
 * it went out of stock or was never picked, this stops and leaves that choice
 * to the test, which is also what the product does: the app never picks a menu
 * for the user.
 */
fun ProductSurface.answerRemaining(
    catalog: SyntheticCatalog,
    restaurant: RestaurantDefinition,
    limit: Int = 64,
) {
    repeat(limit) {
        val fieldId = AsprEngine.openConfirmationIds(state()).firstOrNull() ?: return
        val field = state().fields.getValue(fieldId)
        val line = LineTokens.parseSlotId(field.slotId)
        val scopeToken = line?.baseScopeToken
            ?: catalog.slotOfFieldSlotId(field.slotId)?.scopeToken
            ?: return
        if (scopeToken == Slots.MAIN) return
        val slot = catalog.slot(scopeToken)
        val value = catalog.valuesFor(restaurant, scopeToken).firstOrNull { it.inStock }
            ?: error("${restaurant.name} offers no usable value for ${slot.label}")
        when {
            line == null -> remember(restaurant, slot, value.token, stable = false)
            scopeToken == Slots.QUANTITY -> setQuantity(restaurant, line.lineId, value.token)
            else -> setLineOption(restaurant, line.lineId, slot, value.token)
        }
    }
    error("the draft did not settle within $limit answers")
}
