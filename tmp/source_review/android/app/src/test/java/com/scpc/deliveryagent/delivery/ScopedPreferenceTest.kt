package com.scpc.deliveryagent.delivery

import com.scpc.deliveryagent.core.FieldStatus
import com.scpc.deliveryagent.core.InMemoryStateStore
import com.scpc.deliveryagent.core.ProductionCore
import com.scpc.deliveryagent.core.ProductionState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three scopes an explicit stable preference can be saved at, and the
 * precedence between them.
 *
 * The contract: on one line the order is current instruction, then the exact
 * restaurant-menu override, then the authored menu-type preference, then the
 * global default. A scope only ever applies where its structural key matches,
 * each scope keeps its own permission and tombstone, and a menu type crosses
 * restaurants only because the catalog author said so.
 */
class ScopedPreferenceTest {

    private val catalog = SyntheticCatalog.parse(
        File("src/main/assets/synthetic/catalog.json").readText(Charsets.UTF_8),
    )
    private val daon = catalog.restaurant("restaurant.daon")!!
    private val ongi = catalog.restaurant("restaurant.ongi")!!
    private val bulkkot = catalog.restaurant("restaurant.bulkkot")!!

    private fun surface(
        store: InMemoryStateStore = InMemoryStateStore(),
        process: String = "scope-1",
    ) = ProductSurface(
        ProductionCore(store, process, ProductionState.NAMESPACE_FULL, true, "SCOPE"),
        catalog,
    )

    private fun ProductSurface.lineField(lineId: String, base: String) =
        state().fields[LineTokens.optionFieldId(lineId, base)]

    @Test
    fun `override beats menu type beats global on the line the scopes match`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.normal", stable = true)
        surface.rememberForMenuType(
            ongi, catalog.slot(Slots.SALTINESS), "salt.light", "menutype.soup_meal",
        )
        surface.addLine(ongi, "menu.ongi.perilla")
        assertEquals(
            "the narrower menu-type value wins over the global default",
            "salt.light",
            surface.lineField("l1", Slots.SALTINESS)?.value,
        )

        surface.rememberForMenu(
            ongi, catalog.slot(Slots.SALTINESS), "salt.normal", "menu.ongi.perilla",
        )
        assertEquals(
            "the exact restaurant-menu override wins over the type value",
            "salt.normal",
            surface.lineField("l1", Slots.SALTINESS)?.value,
        )
    }

    @Test
    fun `an override applies to its exact menu and to nothing else`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.light", stable = true)
        surface.rememberForMenu(
            ongi, catalog.slot(Slots.SALTINESS), "salt.normal", "menu.ongi.perilla",
        )
        surface.addLine(ongi, "menu.ongi.perilla")
        surface.addLine(ongi, "menu.ongi.tofu")

        assertEquals("salt.normal", surface.lineField("l1", Slots.SALTINESS)?.value)
        assertEquals(
            "the sibling menu keeps the global default",
            "salt.light",
            surface.lineField("l2", Slots.SALTINESS)?.value,
        )
    }

    @Test
    fun `a menu type preference crosses restaurants only on the same authored type`() {
        val store = InMemoryStateStore()
        val surface = surface(store)
        surface.startNewOrder(daon)
        // Saved at daon, for soup meals anywhere.
        surface.rememberForMenuType(
            daon, catalog.slot(Slots.RICE), "rice.small", "menutype.soup_meal",
        )

        surface.nextOrderSession(ongi)
        surface.addLine(ongi, "menu.ongi.perilla")
        assertEquals(
            "the same authored type at another restaurant reuses the value",
            "rice.small",
            surface.lineField("l1", Slots.RICE)?.value,
        )
        assertEquals(
            FieldStatus.AUTO_APPLIED,
            surface.lineField("l1", Slots.RICE)?.status,
        )

        surface.nextOrderSession(bulkkot)
        surface.addLine(bulkkot, "menu.bulkkot.mild")
        assertNull(
            "a different authored type never inherits the value",
            surface.lineField("l1", Slots.RICE),
        )
    }

    @Test
    fun `a slot the author did not declare stable cannot be saved per menu type`() {
        val surface = surface()
        surface.startNewOrder(daon)
        val refused = try {
            surface.rememberForMenuType(
                daon, catalog.slot(Slots.SPICINESS), "spice.mild", "menutype.soup_meal",
            )
            false
        } catch (expected: IllegalArgumentException) {
            true
        }
        assertTrue("spiciness is not an authored stable slot of the soup type", refused)
    }

    @Test
    fun `a current instruction on the line beats every stored scope`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.rememberForMenu(
            ongi, catalog.slot(Slots.SALTINESS), "salt.normal", "menu.ongi.perilla",
        )
        surface.addLine(ongi, "menu.ongi.perilla")
        assertEquals("salt.normal", surface.lineField("l1", Slots.SALTINESS)?.value)

        surface.setLineOption(ongi, "l1", catalog.slot(Slots.SALTINESS), "salt.light")
        assertEquals(
            "this order's own instruction wins",
            "salt.light",
            surface.lineField("l1", Slots.SALTINESS)?.value,
        )

        // The next session expires the instruction and the override returns. Line
        // ids start over with the new draft.
        surface.nextOrderSession(ongi)
        surface.addLine(ongi, "menu.ongi.perilla")
        assertEquals("salt.normal", surface.lineField("l1", Slots.SALTINESS)?.value)
        assertTrue(
            "the override itself was never changed",
            surface.state().facts.values.any {
                it.value == "salt.normal" && it.scopeMenuId == "menu.ongi.perilla"
            },
        )
    }

    @Test
    fun `withdrawing one scope's permission leaves the other scopes applying`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.light", stable = true)
        surface.rememberForMenu(
            ongi, catalog.slot(Slots.SALTINESS), "salt.normal", "menu.ongi.perilla",
        )
        surface.addLine(ongi, "menu.ongi.perilla")
        surface.addLine(ongi, "menu.ongi.tofu")

        surface.revokeAutoApplyScope(
            PreferenceScopes.menuOverride(Slots.SALTINESS, ongi.entityToken, "menu.ongi.perilla"),
        )

        assertEquals(
            "the revoked override still proposes but has to ask",
            FieldStatus.NEEDS_CONFIRMATION,
            surface.lineField("l1", Slots.SALTINESS)?.status,
        )
        assertEquals("salt.normal", surface.lineField("l1", Slots.SALTINESS)?.value)
        assertEquals(
            "the global scope's permission is untouched",
            FieldStatus.AUTO_APPLIED,
            surface.lineField("l2", Slots.SALTINESS)?.status,
        )
    }

    @Test
    fun `deleting an override falls back to the wider scope and leaves a marker`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.remember(ongi, catalog.slot(Slots.SALTINESS), "salt.light", stable = true)
        surface.rememberForMenu(
            ongi, catalog.slot(Slots.SALTINESS), "salt.normal", "menu.ongi.perilla",
        )
        surface.addLine(ongi, "menu.ongi.perilla")
        assertEquals("salt.normal", surface.lineField("l1", Slots.SALTINESS)?.value)

        surface.deletePreference(
            PreferenceScopes.menuOverride(Slots.SALTINESS, ongi.entityToken, "menu.ongi.perilla"),
        )

        assertEquals(
            "the line falls back to the global default",
            "salt.light",
            surface.lineField("l1", Slots.SALTINESS)?.value,
        )
        assertTrue(surface.state().tombstones.isNotEmpty())
        assertTrue(
            "the global preference survives the deletion",
            surface.state().facts.values.any { it.value == "salt.light" },
        )
    }

    @Test
    fun `correcting one scope raises its authority without touching the others`() {
        val surface = surface()
        surface.startNewOrder(daon)
        surface.remember(daon, catalog.slot(Slots.RICE), "rice.normal", stable = true)
        surface.rememberForMenuType(
            daon, catalog.slot(Slots.RICE), "rice.small", "menutype.soup_meal",
        )

        surface.correctPreference(
            PreferenceScopes.menuType(Slots.RICE, "menutype.soup_meal"),
            "rice.large",
        )

        surface.addLine(daon, "menu.daon.clear")
        assertEquals(
            "the corrected type value applies at its own scope",
            "rice.large",
            surface.lineField("l1", Slots.RICE)?.value,
        )
        assertTrue(
            "the global value is untouched",
            surface.state().facts.values.any {
                it.value == "rice.normal" && it.menuTypeId == null && it.scopeMenuId == null
            },
        )
    }

    @Test
    fun `stored preferences report their scope level`() {
        val surface = surface()
        surface.startNewOrder(ongi)
        surface.remember(ongi, catalog.slot(Slots.UTENSIL), "utensil.exclude", stable = true)
        surface.rememberForMenuType(
            ongi, catalog.slot(Slots.RICE), "rice.normal", "menutype.soup_meal",
        )
        surface.rememberForMenu(
            ongi, catalog.slot(Slots.SALTINESS), "salt.light", "menu.ongi.perilla",
        )

        val levels = surface.storedPreferences().associate { it.value to it.level }
        assertEquals(PreferenceScopeLevel.GLOBAL_DEFAULT, levels["utensil.exclude"])
        assertEquals(PreferenceScopeLevel.MENU_TYPE, levels["rice.normal"])
        assertEquals(PreferenceScopeLevel.RESTAURANT_MENU_OVERRIDE, levels["salt.light"])
    }
}
