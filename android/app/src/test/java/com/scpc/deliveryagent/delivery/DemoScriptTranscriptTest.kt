package com.scpc.deliveryagent.delivery

import com.scpc.deliveryagent.core.AsprEngine
import com.scpc.deliveryagent.core.DraftField
import com.scpc.deliveryagent.core.FieldStatus
import com.scpc.deliveryagent.core.InMemoryStateStore
import com.scpc.deliveryagent.core.ProductionCore
import com.scpc.deliveryagent.core.ProductionState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walks the demo script and renders what each screen would actually show.
 *
 * The emulator does not fit on the development laptop, so this is how the
 * product surface is reviewed before the device day. It composes every line with
 * the same functions `MainActivity` uses — `slotLabel`, `valueLabel`,
 * `provenanceLabel`, `DraftPricing`, `Recommender`, `reviewScopeChoices` — so the
 * transcript is what a person would read, not a paraphrase of it.
 *
 * What this covers: labels, the order and content of draft rows, which questions
 * are open, which answers are offered for each, recommendation reasons, review
 * scope choices, and that the script never reaches a state with a question and no
 * way to answer it. What it cannot cover: layout, touch targets, scrolling and
 * Android lifecycle, which stay on the device checklist.
 */
class DemoScriptTranscriptTest {

    private val catalog = SyntheticCatalog.parse(
        File("src/main/assets/synthetic/catalog.json").readText(Charsets.UTF_8),
    )
    private val pricing = DraftPricing(catalog)
    private val marahyang = catalog.restaurant("restaurant.marahyang")!!
    private val geumson = catalog.restaurant("restaurant.geumson")!!
    private val hanbam = catalog.restaurant("restaurant.hanbam")!!
    private val ongi = catalog.restaurant("restaurant.ongi")!!

    private val transcript = StringBuilder()

    private fun surface(): ProductSurface = ProductSurface(
        ProductionCore(
            store = InMemoryStateStore(),
            processMarker = "demo-process-1",
            namespace = ProductionState.NAMESPACE_FULL,
            asprEnabled = true,
            runBinding = "DEMO",
        ),
        catalog,
    )

    // ------------------------------------------------------------- rendering

    /** Mirrors `MainActivity.renderDraft`: line cards, then order-level rows. */
    private fun draftRows(
        surface: ProductSurface,
        restaurant: RestaurantDefinition,
    ): List<String> {
        val state = surface.state()
        val rows = mutableListOf<String>()
        val rendered = mutableSetOf<String>()

        fun row(field: DraftField): String {
            val entry = catalog.value(field.value)
            val amount = if (entry != null && entry.priceDelta > 0) {
                " · ${pricing.formatAmount(entry.priceDelta)}"
            } else {
                ""
            }
            return "%-28s %-22s %s".format(
                catalog.slotLabel(field.slotId),
                field.displayValue(catalog, pricing, state) + amount,
                field.statusLine(state),
            )
        }

        surface.lines().forEach { line ->
            val menuLabel = line.menuValueToken?.let(catalog::valueLabel) ?: "메뉴 미정"
            rows += "· 항목 ${line.lineId.removePrefix("l")} — $menuLabel"
            line.slots.forEach { binding ->
                val field = state.fields[AsprEngine.fieldIdFor(binding.lineSlotId)]
                    ?: return@forEach
                rendered += field.fieldId
                rows += "    " + row(field)
            }
        }
        state.fields.values.sortedBy { it.fieldId }.forEach { field ->
            if (field.fieldId in rendered) return@forEach
            val isDraftRow = field.slotId == AsprEngine.SLOT_TOTAL ||
                catalog.slotOfFieldSlotId(field.slotId) != null ||
                LineTokens.parseSlotId(field.slotId) != null
            if (!isDraftRow) return@forEach
            rows += "  " + row(field)
        }
        return rows
    }

    /** Mirrors `MainActivity.renderQuestions`: each open field and its chips. */
    private fun questions(
        surface: ProductSurface,
        restaurant: RestaurantDefinition,
    ): List<Pair<String, List<String>>> {
        val state = surface.state()
        return AsprEngine.openConfirmationIds(state).mapNotNull { fieldId ->
            val field = state.fields[fieldId] ?: return@mapNotNull null
            val slot = catalog.slotOfFieldSlotId(field.slotId)
                ?: catalog.baseSlotOfLineSlotId(field.slotId)
                ?: return@mapNotNull null
            val chips = catalog.valuesFor(restaurant, slot.scopeToken)
                .filter { it.inStock }
                .map { option ->
                    if (option.priceDelta > 0) {
                        "${option.label} ${pricing.formatAmount(option.priceDelta)}"
                    } else {
                        option.label
                    }
                }
            catalog.slotLabel(field.slotId) to chips
        }
    }

    private fun screen(
        surface: ProductSurface,
        restaurant: RestaurantDefinition,
        title: String,
    ) {
        val state = surface.state()
        transcript.appendLine()
        transcript.appendLine("=".repeat(78))
        transcript.appendLine("  $title")
        transcript.appendLine("=".repeat(78))
        transcript.appendLine(
            "현재 상황: session ${state.sessionLabel} · 대상 ${restaurant.name} · " +
                "network ${state.network.name}",
        )

        transcript.appendLine()
        transcript.appendLine("[주문 초안]")
        val rows = draftRows(surface, restaurant)
        if (rows.isEmpty()) {
            transcript.appendLine("  아직 초안이 없습니다.")
        } else {
            rows.forEach { transcript.appendLine("  $it") }
        }
        val total = pricing.total(state)
        val budget = pricing.budgetLimit(state)
        val estimate = pricing.estimateMinutes(state)
        transcript.appendLine(
            buildString {
                append("  합성 총액 ${pricing.formatAmount(total)}")
                if (budget != null) append(if (total <= budget) " · 예산 안" else " · 예산 초과")
                if (estimate != null) append(" · 합성 예상시간 ${estimate}분")
            },
        )
        val confirmed = state.fields.values.count { it.status == FieldStatus.CONFIRMED }
        val open = AsprEngine.openConfirmationIds(state)
        transcript.appendLine(
            "  확인 완료 $confirmed · 확인 필요 ${open.size} · " +
                "지금까지 필요한 확인·입력 ${state.resolutions.size}회",
        )

        val asked = questions(surface, restaurant)
        if (asked.isNotEmpty()) {
            transcript.appendLine()
            transcript.appendLine("[확인이 필요한 항목]")
            asked.forEach { (label, chips) ->
                transcript.appendLine("  ${Particles.withObj(label)} 정해 주세요.")
                transcript.appendLine("    ( ${chips.joinToString(" | ")} )")
            }
        }
        if (open.isEmpty() && state.fields.isNotEmpty()) {
            transcript.appendLine()
            transcript.appendLine("  [버튼] 이대로 확인하고 가상 주문 기록")
        }
    }

    private fun say(
        surface: ProductSurface,
        restaurant: RestaurantDefinition,
        message: String,
    ): ProductSurface.ChatTurn {
        val turn = surface.say(restaurant, message)
        transcript.appendLine()
        transcript.appendLine("나: $message")
        turn.applied.forEach { value ->
            transcript.appendLine(
                "에이전트: ${Particles.withObj(catalog.slot(value.scopeToken).label)} " +
                    "${Particles.withInto(catalog.valueLabel(value.valueToken))} 반영했습니다.",
            )
        }
        turn.questions.forEach { transcript.appendLine("에이전트: $it") }
        if (turn.recommendations.isNotEmpty()) {
            transcript.appendLine("에이전트: [추천 후보]")
            turn.recommendations.forEach { candidate ->
                transcript.appendLine(
                    "  - ${candidate.label} · ${pricing.formatAmount(candidate.amount)} · " +
                        "${candidate.estimateMinutes}분" +
                        (if (candidate.rating.count > 0) " · ${candidate.rating.label()}" else " · 내 평점 없음") +
                        (candidate.blockedBy?.let { " · 제외: $it" } ?: ""),
                )
                transcript.appendLine(
                    "      근거: " +
                        candidate.reasons.joinToString(" · ") { "${it.badge} ${it.detail}" },
                )
            }
        }
        return turn
    }

    // ------------------------------------------------------------ invariants

    /** A question the user cannot answer is a dead end, so every one offers values. */
    private fun assertNoDeadEnd(surface: ProductSurface, restaurant: RestaurantDefinition, at: String) {
        questions(surface, restaurant).forEach { (label, chips) ->
            assertTrue("$at: '$label' 질문에 고를 수 있는 값이 없다", chips.isNotEmpty())
        }
    }

    /** A row that still shows a raw token means a label is missing on screen. */
    private fun assertNoRawTokens(surface: ProductSurface, restaurant: RestaurantDefinition, at: String) {
        draftRows(surface, restaurant).forEach { row ->
            listOf("slot.", "option.", "menu.", "field.", "line.", "total.", "budget.", "eta.")
                .forEach { marker ->
                    assertFalse("$at: 화면에 원시 token이 그대로 보인다 -> $row", row.contains(marker))
                }
            // An engine constant such as CATALOG_CANNOT_FULFIL_ASK_AGAIN is not a
            // sentence, and the token markers above do not catch it.
            assertFalse(
                "$at: 화면에 내부 상수가 그대로 보인다 -> $row",
                SCREAMING_SNAKE.containsMatchIn(row),
            )
        }
    }

    /**
     * One option must not occupy two rows of the same draft. A slot addressed at
     * a line and at the whole order at once reads as the app asking twice for the
     * same thing.
     */
    private fun assertNoDuplicateRows(
        surface: ProductSurface,
        restaurant: RestaurantDefinition,
        at: String,
    ) {
        val labels = draftRows(surface, restaurant)
            .filter { it.startsWith("  ") }
            .map { it.trim().substringBefore("  ").trim() }
        val duplicates = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("$at: 같은 항목이 초안에 두 번 보인다 -> $duplicates", duplicates.isEmpty())
    }

    private fun check(surface: ProductSurface, restaurant: RestaurantDefinition, at: String) {
        assertNoDeadEnd(surface, restaurant, at)
        assertNoRawTokens(surface, restaurant, at)
        assertNoDuplicateRows(surface, restaurant, at)
    }

    // ---------------------------------------------------------------- script

    @Test
    fun `the demo script renders a complete screen at every step`() {
        // The transcript is the diagnostic, so it is printed even when an
        // assertion stops the walk part way.
        try {
            walkDemoScript()
        } finally {
            println(transcript)
        }
    }

    private fun walkDemoScript() {
        val surface = surface()

        // ---------------------------------------------------------- E1 Learn
        transcript.appendLine("### E1 — 첫 주문과 기억 허용범위 (마라향)")
        surface.startNewOrder(marahyang)
        say(surface, marahyang, "1만5천원 이하로 따뜻한 국물, 앞으로도 맵지 않게 해줘")
        say(surface, marahyang, "추천해줘")
        check(surface, marahyang, "E1 추천 직후")

        surface.addLine(marahyang, "menu.marahyang.malatang")
        say(surface, marahyang, "앞으로도 수저 빼고")
        // 파 is offered by every menu type, so this one sentence is what the
        // later episodes carry across restaurants without asking again.
        say(surface, marahyang, "앞으로도 파는 빼줘")
        screen(surface, marahyang, "E1 — 초안 구성 중")
        check(surface, marahyang, "E1 초안")

        // 고수 is what this dish still needs, and it is the customisation the
        // later episodes carry.
        val stillOpen = questions(surface, marahyang).map { it.first }
        assertTrue("E1에서 고수를 물어야 한다: $stillOpen", stillOpen.any { it.contains("고수") })
        // How much the freshly built draft still wants from the user. Comparing
        // this across episodes is the honest measure: the running resolution
        // total also counts the lines an episode chose to add, and adding a dish
        // is not a burden the mechanism was supposed to remove.
        val e1Open = stillOpen.size
        surface.setLineOption(marahyang, "l1", catalog.slot(Slots.CILANTRO), "cilantro.keep")
        // The noodle choice is stated for reuse, so the next restaurant's mala
        // dish arrives with it already filled while 떡 종류, which no earlier
        // order ever named, is still asked about there.
        surface.remember(marahyang, catalog.slot("option.noodle"), "noodle.glass", stable = true)
        surface.answerRemaining(catalog, marahyang)

        val e1 = surface.requestDecision(marahyang)
        screen(surface, marahyang, "E1 — 확정 직후")
        assertEquals("ACT", e1.result.getString("decision_state"))

        // Rating request arrives, then the review whose scope the user names.
        surface.advanceTime()
        assertTrue("E1 뒤 평가 요청이 도착해야 한다", surface.pendingEvaluationRequest())
        val review1 = surface.submitReview(marahyang, "rating.ok", "고수 향이 세서 힘들었어")
        transcript.appendLine()
        transcript.appendLine("나: [★괜찮았어요] 고수 향이 세서 힘들었어")
        review1.notes.forEach { transcript.appendLine("에이전트: $it") }
        transcript.appendLine("에이전트: [이 내용을 다음부터 적용할까요?]")
        review1.offers.forEach { offer ->
            val choices = surface.reviewScopeChoices(marahyang, offer, review1.targetMenuToken)
            transcript.appendLine(
                "  '${offer.matchedText}' → ${Particles.withObj(catalog.slot(offer.slotToken).label)} " +
                    Particles.withInto(catalog.valueLabel(offer.impliesValue)),
            )
            transcript.appendLine(
                "    ( " + choices.joinToString(" | ") { scopeLabel(it) } + " | 저장하지 않음 )",
            )
            assertTrue("리뷰 승인 범위 선택지가 비어 있다", choices.isNotEmpty())
        }
        val offer1 = review1.offers.single { it.slotToken == Slots.CILANTRO }
        assertTrue(
            "마라 유형은 고수를 식당 너머로 이어붙일 수 있게 authored되어 있다",
            PreferenceScopeLevel.MENU_TYPE in
                surface.reviewScopeChoices(marahyang, offer1, review1.targetMenuToken),
        )
        surface.acceptFromReview(
            marahyang, review1.reviewId, offer1, PreferenceScopeLevel.MENU_TYPE,
        )
        transcript.appendLine("나: 같은 메뉴 유형이면 어디서든")

        // ---------------------------------------------------------- E2 Reuse
        transcript.appendLine()
        transcript.appendLine("### E2 — 다른 식당, 같은 마라 유형 (금손분식)")
        surface.nextOrderSession(geumson)
        say(surface, geumson, "2만5천원 이하로 30분 안에, 추천해줘")
        check(surface, geumson, "E2 추천")

        // Two dishes of the same kitchen with different option sets: only the
        // mala one carries 고수, only the tteokbokki one carries 치즈.
        surface.addLine(geumson, "menu.geumson.mala_tteok")
        surface.addLine(geumson, "menu.geumson.soup_tteok")
        screen(surface, geumson, "E2 — 새 식당 초안 (마라 유형 취향이 건너옴)")
        check(surface, geumson, "E2 초안")
        val e2Open = questions(surface, geumson).size

        val e2Fields = surface.state().fields
        assertEquals(
            "E1에서 메뉴 유형 범위로 승인한 고수 취향이 다른 식당에서 자동 적용된다",
            "cilantro.exclude",
            e2Fields[LineTokens.optionFieldId("l1", Slots.CILANTRO)]?.value,
        )
        assertEquals(
            FieldStatus.AUTO_APPLIED,
            e2Fields[LineTokens.optionFieldId("l1", Slots.CILANTRO)]?.status,
        )
        assertEquals(
            "E1의 전역 수저 취향도 새 식당에서 그대로 적용된다",
            FieldStatus.AUTO_APPLIED,
            e2Fields[catalog.slot(Slots.UTENSIL).fieldId]?.status,
        )
        assertEquals(
            "전역 맵기 취향도 이 항목에 적용된다",
            FieldStatus.AUTO_APPLIED,
            e2Fields[LineTokens.optionFieldId("l1", Slots.SPICINESS)]?.status,
        )

        // A paid extra the user asks for today, charged to the line it joins.
        val beforeCheese = pricing.total(surface.state())
        say(surface, geumson, "이번 주문만 치즈 추가해줘")
        assertEquals(
            "치즈 값 1,000원이 총액에 잡힌다",
            beforeCheese + 1_000,
            pricing.total(surface.state()),
        )
        screen(surface, geumson, "E2 — 유료 추가 반영")

        surface.answerRemaining(catalog, geumson)
        val e2 = surface.requestDecision(geumson)
        assertEquals("ACT", e2.result.getString("decision_state"))
        transcript.appendLine()
        transcript.appendLine(
            "  [부담 비교] 초안이 사용자에게 물은 항목 — " +
                "E1(메뉴 1개) ${e1Open}건 → E2(메뉴 2개) ${e2Open}건",
        )
        assertTrue(
            "메뉴를 하나 더 담고도 물어본 항목이 늘지 않아야 한다 (E1=$e1Open E2=$e2Open)",
            e2Open < e1Open,
        )

        // ------------------------------------------------------ E3 Exception
        transcript.appendLine()
        transcript.appendLine("### E3 — 오늘의 예외·권한 철회·취향 정정 (금손분식)")
        surface.nextOrderSession(geumson)
        surface.addLine(geumson, "menu.geumson.soup_tteok")
        say(surface, geumson, "이번 주문만 아주 맵게 해줘")
        say(surface, geumson, "일회용 수저는 앞으로 자동으로 정하지 마")
        // The stored heat is corrected to a middle step this kitchen does have.
        surface.correctPreference(PreferenceScopes.global(Slots.SPICINESS), "spice.medium")
        screen(surface, geumson, "E3 — 일회성 예외·철회·정정")
        check(surface, geumson, "E3")

        assertEquals(
            "권한을 철회했으므로 수저를 다시 물어야 한다",
            FieldStatus.NEEDS_CONFIRMATION,
            surface.state().fields[catalog.slot(Slots.UTENSIL).fieldId]?.status,
        )
        assertEquals(
            "이번 주문 지시가 저장된 취향을 이긴다",
            "spice.very_hot",
            surface.state().fields[LineTokens.optionFieldId("l1", Slots.SPICINESS)]?.value,
        )

        // -------------------------------------------------------- E4 Recover
        transcript.appendLine()
        transcript.appendLine("### E4 — 미제공 옵션·품절·부분복구 (마라향)")
        surface.nextOrderSession(marahyang)
        surface.addLine(marahyang, "menu.marahyang.malatang")
        screen(surface, marahyang, "E4 — 같은 유형 재주문, 이 집엔 없는 단계")
        check(surface, marahyang, "E4 초안")

        val e4Fields = surface.state().fields
        assertEquals(
            "E1의 메뉴 유형 승인이 여전히 이 집 마라탕에도 적용된다",
            "cilantro.exclude",
            e4Fields[LineTokens.optionFieldId("l1", Slots.CILANTRO)]?.value,
        )
        assertEquals(
            "E3에서 정정한 중간맛을 이 식당은 내지 않으므로 다시 묻는다",
            FieldStatus.NEEDS_CONFIRMATION,
            e4Fields[LineTokens.optionFieldId("l1", Slots.SPICINESS)]?.status,
        )
        assertEquals(
            "CATALOG_CANNOT_FULFIL_ASK_AGAIN",
            e4Fields[LineTokens.optionFieldId("l1", Slots.SPICINESS)]?.provenance,
        )
        assertEquals(
            "E3의 일회성 아주 매운맛은 session이 바뀌며 만료된다",
            "spice.very_hot",
            surface.state().facts.values
                .single { it.kind == com.scpc.deliveryagent.core.FactKind.ONE_OFF && it.value == "spice.very_hot" }
                .value,
        )
        surface.setLineOption(marahyang, "l1", catalog.slot(Slots.SPICINESS), "spice.mild")
        surface.remember(marahyang, catalog.slot(Slots.UTENSIL), "utensil.exclude", stable = false)

        surface.addLine(marahyang, "menu.marahyang.guobaorou")
        say(surface, marahyang, "소스는 따로 포장해줘")
        val beforeStock = pricing.total(surface.state())
        screen(surface, marahyang, "E4 — 두 항목 주문 초안")
        check(surface, marahyang, "E4 두 항목")

        surface.applyCatalogEvent("catalog.marahyang.menu.guobaorou.soldout")
        screen(surface, marahyang, "E4 — 사이드 품절 event 도착 (부분복구)")
        check(surface, marahyang, "E4 품절 후")

        val after = surface.state()
        assertEquals(
            "품절된 항목만 다시 확인 대상이 된다",
            FieldStatus.NEEDS_CONFIRMATION,
            after.fields[LineTokens.menuFieldId("l2")]?.status,
        )
        assertEquals(
            "본 메뉴는 그대로 보존된다",
            "menu.marahyang.malatang",
            after.fields[LineTokens.menuFieldId("l1")]?.value,
        )
        assertEquals(
            "그 항목의 고수 선택도 보존된다",
            "cilantro.exclude",
            after.fields[LineTokens.optionFieldId("l1", Slots.CILANTRO)]?.value,
        )
        assertTrue("품절 뒤 총액이 줄어야 한다", pricing.total(after) < beforeStock)

        surface.removeLine(marahyang, "l2")
        surface.answerRemaining(catalog, marahyang)
        val e4 = surface.requestDecision(marahyang)
        screen(surface, marahyang, "E4 — 품절 항목 제거 후 확정")
        assertEquals("ACT", e4.result.getString("decision_state"))
    }

    private companion object {
        /** `CATALOG_CANNOT_FULFIL_ASK_AGAIN` and friends, leaked onto a screen. */
        val SCREAMING_SNAKE = Regex("[A-Z]{3,}(_[A-Z]+)+")
    }

    private fun scopeLabel(level: PreferenceScopeLevel): String = when (level) {
        PreferenceScopeLevel.RESTAURANT_MENU_OVERRIDE -> "이 식당·이 메뉴만"
        PreferenceScopeLevel.MENU_TYPE -> "같은 메뉴 유형이면 어디서든"
        PreferenceScopeLevel.GLOBAL_DEFAULT -> "모든 메뉴"
    }
}
