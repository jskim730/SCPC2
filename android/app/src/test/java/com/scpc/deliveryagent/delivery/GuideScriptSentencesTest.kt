package com.scpc.deliveryagent.delivery

import com.scpc.deliveryagent.core.InMemoryStateStore
import com.scpc.deliveryagent.core.ProductionCore
import com.scpc.deliveryagent.core.ProductionState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `INSTALL_AND_USE_GUIDE` §3 hands a judge sentences to type verbatim. Every one
 * of them has to survive the parser, because the guide promises the app acts on
 * them rather than asking again.
 *
 * `DemoScriptTranscriptTest` walks the same E1–E4 story, but it reaches for
 * `addLine(menuToken)` wherever the guide types a menu name, so the sentences
 * that name a menu were never actually parsed. These tests close that gap: they
 * only ever type.
 */
class GuideScriptSentencesTest {

    private val catalog = SyntheticCatalog.parse(
        File("src/main/assets/synthetic/catalog.json").readText(Charsets.UTF_8),
    )
    private val marahyang = catalog.restaurant("restaurant.marahyang")!!

    private fun surface(): ProductSurface = ProductSurface(
        ProductionCore(
            store = InMemoryStateStore(),
            processMarker = "guide-process-1",
            namespace = ProductionState.NAMESPACE_FULL,
            asprEnabled = true,
            runBinding = "GUIDE",
        ),
        catalog,
    )

    /** Guide E4, step 21. */
    @Test
    fun `typing a menu name orders that menu`() {
        val surface = surface()
        surface.startNewOrder(marahyang)

        surface.say(marahyang, "마라탕으로 할게")

        assertEquals(
            "'마라탕으로 할게'가 마라탕을 주문 항목으로 만든다",
            "menu.marahyang.malatang",
            surface.state().fields[LineTokens.menuFieldId("l1")]?.value,
        )
    }

    /** Guide E4, step 22 — one sentence carrying a second item and a request. */
    @Test
    fun `typing an added menu and a request in one sentence does both`() {
        val surface = surface()
        surface.startNewOrder(marahyang)
        surface.say(marahyang, "마라탕으로 할게")

        val turn = surface.say(marahyang, "꿔바로우 추가하고 소스는 따로 포장해줘")

        assertEquals(
            "본 메뉴는 그대로 남는다",
            "menu.marahyang.malatang",
            surface.state().fields[LineTokens.menuFieldId("l1")]?.value,
        )
        assertEquals(
            "꿔바로우가 두 번째 항목으로 담긴다",
            "menu.marahyang.guobaorou",
            surface.state().fields[LineTokens.menuFieldId("l2")]?.value,
        )
        assertTrue(
            "같은 문장의 '소스는 따로'가 요청 메모로 함께 적용된다 (applied=${turn.applied.map { it.valueToken }}, questions=${turn.questions})",
            turn.applied.any { it.valueToken == "note.sauce_separate" },
        )
    }
}
