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
 * What happens when two kitchens do not divide an option the same way, and what
 * a paid extra does to the bill.
 *
 * These are the cases the earlier three-restaurant catalog could not express:
 * every restaurant offered the identical option vocabulary, so a stored value was
 * always applicable, and no option carried a price. Both are declared product
 * behaviour, so both are pinned here.
 */
class OptionAvailabilityTest {

    private val catalog = SyntheticCatalog.parse(
        File("src/main/assets/synthetic/catalog.json").readText(Charsets.UTF_8),
    )
    private val pricing = DraftPricing(catalog)
    private val marahyang = catalog.restaurant("restaurant.marahyang")!!
    private val geumson = catalog.restaurant("restaurant.geumson")!!
    private val hanbam = catalog.restaurant("restaurant.hanbam")!!
    private val daon = catalog.restaurant("restaurant.daon")!!

    private fun surface(
        store: InMemoryStateStore = InMemoryStateStore(),
        process: String = "avail-1",
    ) = ProductSurface(
        ProductionCore(store, process, ProductionState.NAMESPACE_FULL, true, "AVAIL"),
        catalog,
    )

    private fun ProductSurface.lineField(lineId: String, base: String) =
        state().fields[LineTokens.optionFieldId(lineId, base)]

    // ------------------------------------------------- narrower option lists

    @Test
    fun `a restaurant offers only the option values it actually does`() {
        assertEquals(
            "금손분식은 맵기를 세 단계로 나눈다",
            listOf("spice.mild", "spice.medium", "spice.very_hot"),
            catalog.valuesFor(geumson, Slots.SPICINESS).map { it.token },
        )
        assertEquals(
            "마라향은 두 단계만 낸다",
            listOf("spice.mild", "spice.very_hot"),
            catalog.valuesFor(marahyang, Slots.SPICINESS).map { it.token },
        )
        assertFalse(catalog.accepts(marahyang, Slots.SPICINESS, "spice.medium"))
        assertTrue(catalog.accepts(geumson, Slots.SPICINESS, "spice.medium"))
    }

    @Test
    fun `a stored value this kitchen does not offer is asked again, not rounded off`() {
        val surface = surface()
        surface.startNewOrder(geumson)
        surface.remember(geumson, catalog.slot(Slots.SPICINESS), "spice.medium", stable = true)
        surface.addLine(geumson, "menu.geumson.soup_tteok")
        assertEquals(
            "저장한 식당에서는 그대로 적용된다",
            FieldStatus.AUTO_APPLIED,
            surface.lineField("l1", Slots.SPICINESS)?.status,
        )
        assertEquals("spice.medium", surface.lineField("l1", Slots.SPICINESS)?.value)

        // 마라향 does not have a middle step at all.
        surface.nextOrderSession(marahyang)
        surface.addLine(marahyang, "menu.marahyang.malatang")
        val field = surface.lineField("l1", Slots.SPICINESS)

        assertEquals(
            "제공하지 않는 값은 자동 적용되지 않는다",
            FieldStatus.NEEDS_CONFIRMATION,
            field?.status,
        )
        assertEquals("CATALOG_CANNOT_FULFIL_ASK_AGAIN", field?.provenance)
        assertNotEquals(
            "가까운 단계로 임의 대체하지 않는다",
            "spice.mild",
            field?.value,
        )
        assertTrue(
            "고를 수 있는 값은 이 식당이 내는 두 단계뿐이다",
            catalog.valuesFor(marahyang, Slots.SPICINESS).map { it.token }
                .none { it == "spice.medium" },
        )
    }

    @Test
    fun `the stored preference itself survives being unusable here`() {
        val surface = surface()
        surface.startNewOrder(geumson)
        surface.remember(geumson, catalog.slot(Slots.SPICINESS), "spice.medium", stable = true)
        surface.nextOrderSession(marahyang)
        surface.addLine(marahyang, "menu.marahyang.malatang")
        assertTrue(
            "이 식당에서 못 쓴다고 취향이 지워지지는 않는다",
            surface.state().facts.values.any { it.value == "spice.medium" },
        )

        // Back at a restaurant that offers it, it applies again.
        surface.nextOrderSession(geumson)
        surface.addLine(geumson, "menu.geumson.soup_tteok")
        assertEquals(
            FieldStatus.AUTO_APPLIED,
            surface.lineField("l1", Slots.SPICINESS)?.status,
        )
    }

    // --------------------------------------------------------- paid extras

    @Test
    fun `a paid extra is added to the line and to the order total`() {
        val surface = surface()
        surface.startNewOrder(marahyang)
        surface.addLine(marahyang, "menu.marahyang.malatang")
        assertEquals(12_500, pricing.total(surface.state()))

        surface.setLineOption(marahyang, "l1", catalog.slot(Slots.TOFU), "tofu.add")
        assertEquals(
            "두부 추가 1,500원이 총액에 잡힌다",
            12_500 + 1_500,
            pricing.total(surface.state()),
        )
        val line = pricing.lines(surface.state()).single { it.lineId == "l1" }
        assertEquals(1_500, line.extrasAmount)
    }

    @Test
    fun `a paid extra is charged once per portion of the dish it was added to`() {
        val surface = surface()
        surface.startNewOrder(marahyang)
        surface.addLine(marahyang, "menu.marahyang.malatang")
        surface.setLineOption(marahyang, "l1", catalog.slot(Slots.TOFU), "tofu.add")
        surface.setQuantity(marahyang, "l1", "qty.2")

        assertEquals((12_500 + 1_500) * 2, pricing.total(surface.state()))
        assertEquals(1_500 * 2, pricing.lines(surface.state()).single().extrasAmount)
    }

    @Test
    fun `an extra only bills the line it was added to`() {
        val surface = surface()
        surface.startNewOrder(marahyang)
        surface.addLine(marahyang, "menu.marahyang.malatang")
        surface.addLine(marahyang, "menu.marahyang.malaxiangguo")
        surface.setLineOption(marahyang, "l2", catalog.slot(Slots.TOFU), "tofu.add")

        val lines = pricing.lines(surface.state())
        assertEquals(0, lines.single { it.lineId == "l1" }.extrasAmount)
        assertEquals(1_500, lines.single { it.lineId == "l2" }.extrasAmount)
        assertEquals(12_500 + 14_000 + 1_500, pricing.total(surface.state()))
    }

    @Test
    fun `a paid extra counts against the stated budget`() {
        val surface = surface()
        surface.startNewOrder(marahyang)
        surface.remember(marahyang, catalog.slot(Slots.BUDGET), "budget.13000", stable = false)
        surface.addLine(marahyang, "menu.marahyang.malatang")
        assertTrue(pricing.total(surface.state()) <= pricing.budgetLimit(surface.state())!!)

        surface.setLineOption(marahyang, "l1", catalog.slot(Slots.TOFU), "tofu.add")
        assertTrue(
            "추가 요금 때문에 예산을 넘는 것이 보여야 한다",
            pricing.total(surface.state()) > pricing.budgetLimit(surface.state())!!,
        )
    }

    // ------------------------------------------- menu type across kitchens

    @Test
    fun `a customisation preference follows its menu type to another kitchen`() {
        val surface = surface()
        surface.startNewOrder(marahyang)
        // "어느 집이든 마라 요리는 고수 빼고."
        surface.rememberForMenuType(
            marahyang, catalog.slot(Slots.CILANTRO), "cilantro.exclude", "menutype.mala",
        )
        surface.addLine(marahyang, "menu.marahyang.malatang")
        assertEquals("cilantro.exclude", surface.lineField("l1", Slots.CILANTRO)?.value)

        // 금손분식's 마라 떡볶이 is the same authored type at a different kitchen.
        surface.nextOrderSession(geumson)
        surface.addLine(geumson, "menu.geumson.mala_tteok")
        assertEquals(
            "같은 마라 유형이면 식당이 달라도 이어진다",
            "cilantro.exclude",
            surface.lineField("l1", Slots.CILANTRO)?.value,
        )
        assertEquals(
            FieldStatus.AUTO_APPLIED,
            surface.lineField("l1", Slots.CILANTRO)?.status,
        )

        // The tteokbokki at the same kitchen is a different type, so it is untouched.
        surface.addLine(geumson, "menu.geumson.soup_tteok")
        assertEquals(
            "같은 식당이어도 다른 유형에는 번지지 않는다",
            null,
            surface.lineField("l2", Slots.CILANTRO),
        )
    }

    @Test
    fun `the soup preference now has three kitchens to cross`() {
        val surface = surface()
        surface.startNewOrder(daon)
        surface.rememberForMenuType(
            daon, catalog.slot(Slots.RICE), "rice.small", "menutype.soup_meal",
        )

        // 한밤국수 is a third restaurant carrying the same authored type.
        surface.nextOrderSession(hanbam)
        surface.addLine(hanbam, "menu.hanbam.janchi")
        assertEquals("rice.small", surface.lineField("l1", Slots.RICE)?.value)
        assertEquals(FieldStatus.AUTO_APPLIED, surface.lineField("l1", Slots.RICE)?.status)
    }

    @Test
    fun `an add-on preference crosses kitchens of the tteokbokki type`() {
        val surface = surface()
        surface.startNewOrder(geumson)
        surface.rememberForMenuType(
            geumson, catalog.slot(Slots.CHEESE), "cheese.add", "menutype.tteokbokki",
        )
        surface.addLine(geumson, "menu.geumson.soup_tteok")
        assertEquals("cheese.add", surface.lineField("l1", Slots.CHEESE)?.value)
        assertEquals(
            "치즈 값까지 함께 따라온다",
            9_800 + 1_000,
            pricing.total(surface.state()),
        )
    }
}
