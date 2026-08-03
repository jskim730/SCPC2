package com.scpc.deliveryagent.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.scpc.deliveryagent.core.AsprEngine
import com.scpc.deliveryagent.core.DraftField
import com.scpc.deliveryagent.core.FieldStatus
import com.scpc.deliveryagent.core.ProductionState
import com.scpc.deliveryagent.core.StepOutcome
import com.scpc.deliveryagent.delivery.DraftPricing
import com.scpc.deliveryagent.delivery.LineTokens
import com.scpc.deliveryagent.delivery.Particles
import com.scpc.deliveryagent.delivery.PreferenceScopeLevel
import com.scpc.deliveryagent.delivery.PreferenceScopes
import com.scpc.deliveryagent.delivery.displayValue
import com.scpc.deliveryagent.delivery.statusLine
import com.scpc.deliveryagent.delivery.ProductSurface
import com.scpc.deliveryagent.delivery.RatingValue
import com.scpc.deliveryagent.delivery.Recommender
import com.scpc.deliveryagent.delivery.ReviewCandidate
import com.scpc.deliveryagent.delivery.RestaurantDefinition
import com.scpc.deliveryagent.delivery.SlotDefinition
import com.scpc.deliveryagent.delivery.Slots
import com.scpc.deliveryagent.delivery.SyntheticCatalog
import com.scpc.deliveryagent.delivery.ValueScope
import com.scpc.deliveryagent.delivery.provenanceLabel
import com.scpc.deliveryagent.delivery.userLabel

import com.scpc.deliveryagent.platform.Production

/**
 * Today's order.
 *
 * The user types what they want. What the app understood becomes a structured
 * draft, and what it did not becomes a question with the answers offered as taps.
 * The draft is the single source of truth: every row shows its value, its
 * synthetic amount, where the value came from and whether it still needs the
 * user. Every action goes through the same production core the Probe path uses.
 *
 * All restaurants, menus, prices, stock and orders are synthetic and local. No
 * real order, payment, account or contact is involved.
 */
class MainActivity : Activity() {

    private data class ChatLine(val speaker: String, val text: String)

    private lateinit var content: LinearLayout

    private val chat = mutableListOf<ChatLine>()
    private var openQuestions = listOf<String>()
    private var pendingScope = listOf<com.scpc.deliveryagent.delivery.ReadValue>()
    private var candidates = listOf<Recommender.Candidate>()
    private var reviewRating: RatingValue? = null
    private var reviewOffers = listOf<ReviewCandidate>()
    private var reviewId: String? = null
    private var reviewNeedsTarget = false
    private var reviewTargetToken: String? = null
    private var lastDecision = "-"
    private var showScript = false
    private var showDiagnostics = false

    /** Set by the dialog-style actions so the reply scrolls into view. */
    private var scrollChatIntoView = false
    private var conversationAnchor: View? = null
    private lateinit var scroller: ScrollView

    private val catalog: SyntheticCatalog get() = Production.catalog(this)
    private val pricing: DraftPricing get() = DraftPricing(catalog)
    private val surface: ProductSurface get() = Production.surface(this)

    private fun restaurant(token: String): RestaurantDefinition =
        catalog.restaurant(token) ?: error("synthetic catalog is missing $token")

    private val marahyang get() = restaurant("restaurant.marahyang")
    private val geumson get() = restaurant("restaurant.geumson")
    private val hanbam get() = restaurant("restaurant.hanbam")
    private val ongi get() = restaurant("restaurant.ongi")

    /** The restaurant the order is currently against. */
    private fun currentRestaurant(): RestaurantDefinition? =
        catalog.restaurant(surface.state().targetEntityId)

    /**
     * Screen-kept working state belongs to one order conversation. A new
     * session must not inherit candidates, open scope questions or a half
     * answered review from the previous one; the stored memory itself lives
     * in the core and is untouched here.
     */
    private fun clearTransientScreenState() {
        candidates = emptyList()
        pendingScope = emptyList()
        reviewRating = null
        reviewOffers = emptyList()
        reviewId = null
        reviewNeedsTarget = false
        reviewTargetToken = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = Ui.column(this)
        scroller = Ui.scroller(this, content)
        setContentView(scroller)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ----------------------------------------------------------------- chat

    private fun onSend(message: String) {
        scrollChatIntoView = true
        chat += ChatLine("나:", message)
        val restaurant = currentRestaurant()
        if (restaurant == null) {
            // Nothing to order against yet. The app never silently picks a
            // restaurant for the user; the choice chips are right below.
            chat += ChatLine("에이전트:", "주문할 식당을 먼저 선택해 주세요. 아래에서 고를 수 있습니다.")
            render()
            return
        }
        val turn = try {
            surface.say(restaurant, message)
        } catch (error: Exception) {
            chat += ChatLine("에이전트:", "처리하지 못했습니다: ${error.message}")
            render()
            return
        }

        turn.applied.forEach { value ->
            val slot = catalog.slot(value.scopeToken)
            val scope = when (value.scope) {
                ValueScope.REMEMBER_FOR_REUSE -> "앞으로도 기억"
                ValueScope.THIS_ORDER_ONLY -> "이번 주문만"
                ValueScope.UNSTATED -> "이번 주문"
            }
            chat += ChatLine(
                "에이전트:",
                "${Particles.withObj(slot.label)} " +
                    "${Particles.withInto(catalog.valueLabel(value.valueToken))} 반영했습니다 ($scope).",
            )
        }
        turn.questions.forEach { chat += ChatLine("에이전트:", it) }
        turn.decision?.let { outcome ->
            lastDecision = describe(outcome)
            chat += ChatLine("에이전트:", decisionSentence(outcome))
        }
        if (turn.applied.isEmpty() && turn.questions.isEmpty() && turn.decision == null) {
            chat += ChatLine("에이전트:", "무엇을 바꿔야 할지 알아듣지 못했습니다.")
        }

        pendingScope = turn.utterance.values.filter { value ->
            value.scope == ValueScope.UNSTATED &&
                turn.utterance.questions.any { it.about == value.scopeToken }
        }
        candidates = turn.recommendations
        render()
    }

    /** Answers the reuse-scope question for everything the last message left open. */
    private fun answerScope(remember: Boolean) {
        val restaurant = currentRestaurant() ?: return
        scrollChatIntoView = true
        val pending = pendingScope
        pendingScope = emptyList()
        chat += ChatLine("나:", if (remember) "앞으로도 기억해줘" else "이번 주문만")
        // Only what actually stored is spoken about; a value gone stale since
        // the question was asked becomes a message, not a crash or a claim.
        pending.forEach { value ->
            try {
                surface.remember(
                    restaurant = restaurant,
                    slot = catalog.slot(value.scopeToken),
                    value = value.valueToken,
                    stable = remember,
                )
            } catch (error: Exception) {
                chat += ChatLine("에이전트:", "처리하지 못했습니다: ${error.message}")
                return@forEach
            }
            chat += ChatLine(
                "에이전트:",
                "${Particles.withObj(catalog.slot(value.scopeToken).label)} " +
                    "${Particles.withInto(catalog.valueLabel(value.valueToken))} " +
                    (if (remember) "저장하고 다음 주문에도 씁니다." else "이번 주문에만 적용합니다."),
            )
        }
        render()
    }

    /** Answers one open question by tapping a value the current menu offers. */
    private fun answerField(field: DraftField, valueToken: String) {
        val restaurant = currentRestaurant() ?: return
        val parsed = LineTokens.parseSlotId(field.slotId)
        val baseSlot = if (parsed == null) catalog.slotOfFieldSlotId(field.slotId) else null
        if (parsed == null && baseSlot == null) return
        scrollChatIntoView = true
        // The tap goes into the transcript before the redraw so the screen the
        // user sees next already carries what they just did.
        chat += ChatLine(
            "나:",
            "${catalog.slotLabel(field.slotId)}: ${catalog.valueLabel(valueToken)}",
        )
        act {
            when {
                parsed != null && parsed.baseScopeToken == Slots.MAIN ->
                    it.chooseLineMenu(restaurant, parsed.lineId, valueToken)

                parsed != null && parsed.baseScopeToken == Slots.QUANTITY ->
                    it.setQuantity(restaurant, parsed.lineId, valueToken)

                parsed != null ->
                    it.setLineOption(
                        restaurant,
                        parsed.lineId,
                        catalog.slot(parsed.baseScopeToken),
                        valueToken,
                    )

                else -> it.remember(restaurant, baseSlot!!, valueToken, stable = false)
            }
        }
    }

    // --------------------------------------------------------------- render

    private fun render() {
        val state = surface.state()
        openQuestions = AsprEngine.openConfirmationIds(state)
        content.removeAllViews()

        content.addView(Ui.title(this, "오늘의 주문"))
        content.addView(
            Ui.body(
                this,
                "식당·메뉴·가격·재고·예상시간·주문은 모두 이 앱 안의 합성 데이터입니다. " +
                    "실제 주문·결제·외부 계정은 사용하지 않습니다.",
            ),
        )

        renderStage(state)

        content.addView(
            Ui.inputRow(
                context = this,
                hint = "예: 2만원 이하로 따뜻한 국물, 앞으로도 맵지 않게 해줘",
                sendLabel = "보내기",
                onSend = ::onSend,
            ),
        )

        renderRestaurantChoice()
        renderConversation()
        renderQuestions(state)
        renderCandidates()
        renderReview(state)
        renderSituation(state)
        renderDraft(state)
        renderScript()
        renderInterruptions()
        renderDiagnostics(state)
        renderNavigation()

        if (scrollChatIntoView) {
            scrollChatIntoView = false
            val anchor = conversationAnchor
            if (anchor != null) scroller.post { scroller.smoothScrollTo(0, anchor.top) }
        }
    }

    /**
     * Where the order stands and the one thing to do next. The five stages are
     * derived from the same state the sections below render, so this banner can
     * never disagree with them.
     */
    private fun renderStage(state: ProductionState) {
        val restaurant = currentRestaurant()
        val stage = when {
            restaurant == null -> 0
            surface.pendingEvaluationRequest() -> 4
            surface.lines().isEmpty() -> 1
            openQuestions.isNotEmpty() -> 2
            else -> 3
        }
        val path = listOf("식당", "메뉴", "옵션 확인", "확정", "평가")
            .mapIndexed { index, name -> if (index == stage) "●$name" else name }
            .joinToString(" → ")
        content.addView(Ui.mono(this, path))
        val next = when (stage) {
            0 -> "아래에서 주문할 식당을 골라 주세요."
            1 -> "먹고 싶은 것과 조건을 말해 주세요. 추천 후보에서 메뉴를 담습니다."
            2 -> "확인이 필요한 항목 ${openQuestions.size}개에 답해 주세요."
            3 -> "확인이 끝났습니다. 아래 버튼으로 이번 주문을 확정할 수 있습니다."
            else -> "지난 주문이 어땠는지 평가를 남길 수 있습니다."
        }
        content.addView(Ui.body(this, "다음 할 일: $next"))
        if (stage == 3 && restaurant != null && state.fields.isNotEmpty()) {
            content.addView(
                Ui.button(
                    this,
                    "이대로 확인하고 가상 주문 기록 · 합성 총액 ${pricing.formatAmount(pricing.total(state))}",
                    primary = true,
                ) {
                    act { it.requestDecision(restaurant) }
                },
            )
        }
    }

    /** Every synthetic restaurant, offered whenever no order is open yet. */
    private fun renderRestaurantChoice() {
        if (currentRestaurant() != null) return
        content.addView(Ui.section(this, "식당 선택"))
        content.addView(
            Ui.chipRow(
                this,
                catalog.restaurants.map { restaurant ->
                    restaurant.name to {
                        clearTransientScreenState()
                        chat += ChatLine("에이전트:", "${restaurant.name}으로 새 주문을 시작했습니다.")
                        act { it.startNewOrder(restaurant) }
                    }
                },
            ),
        )
    }

    private fun renderConversation() {
        if (chat.isEmpty()) {
            content.addView(
                Ui.body(this, "무엇을 먹고 싶은지, 예산과 조건을 그대로 말해 주세요."),
            )
            return
        }
        val header = Ui.section(this, "대화")
        conversationAnchor = header
        content.addView(header)
        chat.takeLast(MAX_CHAT_LINES).forEach { line ->
            content.addView(Ui.chatLine(this, line.speaker, line.text))
        }
    }

    private fun renderQuestions(state: ProductionState) {
        val restaurant = currentRestaurant() ?: return
        if (pendingScope.isNotEmpty()) {
            content.addView(Ui.section(this, "이 값을 다음에도 쓸까요?"))
            content.addView(
                Ui.chipRow(
                    this,
                    listOf(
                        "이번 주문만" to { answerScope(remember = false) },
                        "앞으로도 기억" to { answerScope(remember = true) },
                    ),
                ),
            )
        }
        if (openQuestions.isEmpty()) return
        content.addView(Ui.section(this, "확인이 필요한 항목"))
        openQuestions.forEach { fieldId ->
            val field = state.fields[fieldId] ?: return@forEach
            val slot = catalog.slotOfFieldSlotId(field.slotId)
                ?: catalog.baseSlotOfLineSlotId(field.slotId)
                ?: return@forEach
            content.addView(
                Ui.body(this, "${Particles.withObj(catalog.slotLabel(field.slotId))} 정해 주세요."),
            )
            val choices = catalog.valuesFor(restaurant, slot.scopeToken)
                .filter { it.inStock }
                .map { option ->
                    val label = if (option.priceDelta > 0) {
                        "${option.label} ${pricing.formatAmount(option.priceDelta)}"
                    } else {
                        option.label
                    }
                    label to { answerField(field, option.token) }
                }
            if (choices.isNotEmpty()) content.addView(Ui.chipRow(this, choices))
        }
    }

    private fun renderCandidates() {
        if (candidates.isEmpty()) return
        val restaurant = currentRestaurant() ?: return
        content.addView(Ui.section(this, "추천 후보"))
        candidates.forEach { candidate ->
            content.addView(
                Ui.candidateCard(
                    context = this,
                    title = candidate.label,
                    detail = buildString {
                        append(pricing.formatAmount(candidate.amount))
                        append(" · ${candidate.estimateMinutes}분")
                        // The user's own past ratings of this exact menu. They order
                        // what conditions and preferences leave tied, never more.
                        if (candidate.rating.count > 0) {
                            append(" · ${candidate.rating.label()}")
                            candidate.rating.breakdown().takeIf { it.isNotEmpty() }
                                ?.let { append(" — $it") }
                        } else {
                            append(" · 내 평점 없음")
                        }
                        candidate.blockedBy?.let { append(" · 제외: $it") }
                    },
                    reasons = candidate.reasons.joinToString(" · ") { "${it.badge} ${it.detail}" },
                    action = if (candidate.offerable) {
                        "이걸로 하기" to {
                            chat += ChatLine("나:", "메뉴: ${candidate.label}")
                            candidates = emptyList()
                            act { it.addLine(restaurant, candidate.valueToken) }
                        }
                    } else {
                        null
                    },
                ),
            )
        }
    }


    /**
     * Leaving a review, and deciding what of it is worth remembering.
     *
     * The section only appears once an order has been recorded, because there is
     * nothing to review before that.
     */
    private fun renderReview(state: ProductionState) {
        val restaurant = currentRestaurant() ?: return
        if (state.actions.isEmpty()) return

        content.addView(Ui.divider(this))
        content.addView(Ui.section(this, "지난 주문 평가"))

        if (surface.pendingEvaluationRequest()) {
            content.addView(
                Ui.body(this, "지난 주문 평가 요청이 도착했습니다. 별점과 한 줄 평가를 남겨 주세요."),
            )
        }

        content.addView(
            Ui.chipRow(
                this,
                surface.ratingOptions().map { rating ->
                    val chosen = if (reviewRating?.token == rating.token) " ✓" else ""
                    (rating.label + chosen) to {
                        reviewRating = rating
                        render()
                    }
                },
            ),
        )
        content.addView(
            Ui.inputRow(
                context = this,
                hint = "예: 국물은 괜찮았는데 간이 좀 셌어",
                sendLabel = "평가 남기기",
                onSend = { text -> onReview(restaurant, text) },
            ),
        )

        if (reviewNeedsTarget) {
            content.addView(Ui.body(this, "어느 메뉴에 대한 평가인가요?"))
            val id = reviewId
            if (id != null) {
                content.addView(
                    Ui.chipRow(
                        this,
                        surface.lines().mapNotNull { line ->
                            val menuToken = line.menuValueToken ?: return@mapNotNull null
                            catalog.valueLabel(menuToken) to {
                                try {
                                    reviewOffers = surface.setReviewTarget(restaurant, id, menuToken)
                                    reviewTargetToken = menuToken
                                    reviewNeedsTarget = false
                                } catch (error: Exception) {
                                    Toast.makeText(
                                        this,
                                        "처리하지 못했습니다: ${error.message}",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                                render()
                            }
                        },
                    ),
                )
            }
        }

        if (reviewOffers.isNotEmpty() && !reviewNeedsTarget) {
            content.addView(
                Ui.body(
                    this,
                    "이 내용을 다음부터 초안에 적용할까요? 적용 범위를 정해 주세요. " +
                        "동의한 범위에만 저장됩니다.",
                ),
            )
            reviewOffers.forEach { offer ->
                val slot = catalog.slot(offer.slotToken)
                content.addView(
                    Ui.body(
                        this,
                        "'${offer.matchedText}' → 다음에는 ${Particles.withObj(slot.label)} " +
                            "${Particles.withInto(catalog.valueLabel(offer.impliesValue))} 적용",
                    ),
                )
                val choices = surface
                    .reviewScopeChoices(restaurant, offer, reviewTargetToken)
                    .map { level -> scopeLevelLabel(level) to { acceptReviewOffer(offer, level) } }
                content.addView(
                    Ui.chipRow(
                        this,
                        choices + ("저장하지 않음" to { declineReviewOffer(offer) }),
                    ),
                )
            }
        }

        if (state.reviews.isNotEmpty()) {
            content.addView(Ui.body(this, "남긴 평가"))
            state.reviews.forEach { record ->
                val rating = catalog.rating(record.ratingToken)?.label ?: "평점 없음"
                val learned = if (record.derivedValueTokens.isEmpty()) {
                    "참고 항목 없음"
                } else {
                    "참고: " + record.derivedValueTokens.joinToString { catalog.valueLabel(it) }
                }
                content.addView(Ui.row(this, rating, record.text, learned))
                content.addView(
                    Ui.button(this, "이 평가와 참고 내용 삭제") {
                        try {
                            surface.deleteReview(record.reviewId)
                            if (reviewId == record.reviewId) {
                                reviewId = null
                                reviewOffers = emptyList()
                            }
                            chat += ChatLine("에이전트:", "평가와 그 평가로 배운 내용을 지웠습니다.")
                        } catch (error: Exception) {
                            Toast.makeText(
                                this,
                                "처리하지 못했습니다: ${error.message}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        render()
                    },
                )
            }
        }
    }

    private fun onReview(restaurant: RestaurantDefinition, text: String) {
        val turn = try {
            surface.submitReview(restaurant, reviewRating?.token, text)
        } catch (error: Exception) {
            Toast.makeText(this, "평가를 남기지 못했습니다: ${error.message}", Toast.LENGTH_LONG).show()
            return
        }
        reviewId = turn.reviewId
        reviewOffers = turn.offers
        reviewNeedsTarget = turn.needsTarget
        reviewTargetToken = turn.targetMenuToken
        chat += ChatLine("나:", text)
        turn.notes.forEach { chat += ChatLine("에이전트:", it) }
        chat += ChatLine(
            "에이전트:",
            when {
                turn.needsTarget -> "평가를 기록했습니다. 어느 메뉴에 대한 평가인지 정해 주세요."
                turn.offers.isEmpty() -> "평가를 기록했습니다. 다음 주문에 적용할 항목은 없습니다."
                else -> "평가를 기록했습니다. 다음부터 적용할지, 어느 범위에 적용할지 정해 주세요."
            },
        )
        render()
    }

    private fun scopeLevelLabel(level: PreferenceScopeLevel): String = when (level) {
        PreferenceScopeLevel.RESTAURANT_MENU_OVERRIDE -> "이 식당·이 메뉴만"
        PreferenceScopeLevel.MENU_TYPE -> "같은 메뉴 유형이면 어디서든"
        PreferenceScopeLevel.GLOBAL_DEFAULT -> "모든 메뉴"
    }

    private fun acceptReviewOffer(offer: ReviewCandidate, level: PreferenceScopeLevel) {
        val id = reviewId ?: return
        val restaurant = currentRestaurant() ?: return
        reviewOffers = reviewOffers - offer
        chat += ChatLine("나:", scopeLevelLabel(level))
        chat += ChatLine(
            "에이전트:",
            "${Particles.withObj(catalog.slot(offer.slotToken).label)} " +
                "${Particles.withInto(catalog.valueLabel(offer.impliesValue))} 저장했습니다 — " +
                "${scopeLevelLabel(level)} 범위에서 다음 초안부터 적용합니다.",
        )
        act { it.acceptFromReview(restaurant, id, offer, level) }
    }

    private fun declineReviewOffer(offer: ReviewCandidate) {
        try {
            surface.dismissFromReview(offer)
        } catch (error: Exception) {
            Toast.makeText(this, "처리하지 못했습니다: ${error.message}", Toast.LENGTH_LONG).show()
        }
        // A stale offer leaves the screen either way; only the stored state
        // speaks through the toast above.
        reviewOffers = reviewOffers - offer
        chat += ChatLine("나:", "저장하지 않음")
        chat += ChatLine("에이전트:", "기억하지 않겠습니다. 평가 기록만 남습니다.")
        render()
    }

    private fun renderSituation(state: ProductionState) {
        content.addView(Ui.section(this, "현재 상황"))
        content.addView(
            Ui.body(
                this,
                buildString {
                    val restaurant = currentRestaurant()
                    append("주문 식당: ${restaurant?.name ?: "미선택"}")
                    if (restaurant != null) {
                        // The user's own past ratings here, for comparison only.
                        val tally = Recommender(catalog).restaurantTally(state, restaurant)
                        if (tally.count > 0) append(" · ${tally.label()}")
                    }
                    append("\n")
                    append("배달지: ${catalog.deliveryAlias}\n")
                    append("network: ${networkLabel(state)}")
                },
            ),
        )
    }

    /**
     * The run's verification identifiers, folded away. A judge or the probe
     * narrative needs them; a person ordering dinner does not.
     */
    private fun renderDiagnostics(state: ProductionState) {
        content.addView(Ui.divider(this))
        content.addView(
            Ui.button(this, if (showDiagnostics) "검증 정보 접기" else "검증 정보 펼치기 (심사용)") {
                showDiagnostics = !showDiagnostics
                render()
            },
        )
        if (!showDiagnostics) return
        content.addView(
            Ui.mono(
                this,
                buildString {
                    append("주문 session: ${state.sessionLabel.ifEmpty { "없음" }}\n")
                    append("실행 세대(process epoch): ${state.processEpoch}\n")
                    append("비교 arm: ${armLabel()}\n")
                    append("마지막 판단: $lastDecision\n")
                    append("합성 catalog snapshot: ${catalog.snapshotDigest.take(16)}")
                },
            ),
        )
    }

    private fun renderDraft(state: ProductionState) {
        content.addView(Ui.section(this, "주문 초안"))
        val restaurant = currentRestaurant()
        val lines = surface.lines()
        if (state.fields.isEmpty() && lines.isEmpty()) {
            content.addView(Ui.body(this, "아직 초안이 없습니다."))
            return
        }

        fun renderFieldRow(field: com.scpc.deliveryagent.core.DraftField) {
            val entry = catalog.value(field.value)
            val amount = if (entry != null && entry.priceDelta > 0) {
                " · ${pricing.formatAmount(entry.priceDelta)}"
            } else {
                ""
            }
            content.addView(
                Ui.row(
                    this,
                    catalog.slotLabel(field.slotId),
                    field.displayValue(catalog, pricing, state) + amount,
                    field.statusLine(state),
                ),
            )
        }

        // One card per order line: its menu, its own options, its own controls.
        val renderedFieldIds = mutableSetOf<String>()
        lines.forEach { line ->
            val menuLabel = line.menuValueToken?.let(catalog::valueLabel) ?: "메뉴 미정"
            content.addView(Ui.body(this, "· 항목 ${line.lineId.removePrefix("l")} — $menuLabel"))
            line.slots.forEach slot@{ binding ->
                val field = state.fields[AsprEngine.fieldIdFor(binding.lineSlotId)] ?: return@slot
                renderedFieldIds += field.fieldId
                renderFieldRow(field)
            }
            if (restaurant != null) {
                content.addView(
                    Ui.chipRow(
                        this,
                        (1..3).map { count ->
                            "수량 ${count}개" to {
                                act { it.setQuantity(restaurant, line.lineId, "qty.$count") }
                            }
                        } + (
                            "이 항목 빼기" to {
                                chat += ChatLine("나:", "$menuLabel 빼기")
                                actQuiet { it.removeLine(restaurant, line.lineId) }
                            }
                            ),
                    ),
                )
            }
        }

        // Order-level rows: conditions, the utensil, the note, the total.
        state.fields.values.sortedBy { it.fieldId }.forEach { field ->
            if (field.fieldId in renderedFieldIds) return@forEach
            // A passing notice such as the rating request is not a draft row.
            val isDraftRow = field.slotId == AsprEngine.SLOT_TOTAL ||
                catalog.slotOfFieldSlotId(field.slotId) != null ||
                LineTokens.parseSlotId(field.slotId) != null
            if (!isDraftRow) return@forEach
            renderFieldRow(field)
        }

        // Side menus join the order as their own lines.
        if (restaurant != null && lines.isNotEmpty()) {
            val sides = catalog.sideMenus(restaurant).filter { it.inStock }
            if (sides.isNotEmpty()) {
                content.addView(Ui.body(this, "사이드 추가"))
                content.addView(
                    Ui.chipRow(
                        this,
                        sides.map { side ->
                            "${side.label} ${pricing.formatAmount(side.priceDelta)}" to {
                                chat += ChatLine("나:", "사이드: ${side.label}")
                                act { it.addLine(restaurant, side.token) }
                            }
                        },
                    ),
                )
            }
        }

        val total = pricing.total(state)
        val budget = pricing.budgetLimit(state)
        val estimate = pricing.estimateMinutes(state)
        content.addView(
            Ui.body(
                this,
                buildString {
                    append("합성 총액 ${pricing.formatAmount(total)}")
                    if (budget != null) {
                        append(if (total <= budget) " · 예산 안" else " · 예산 초과")
                    }
                    if (estimate != null) append(" · 합성 예상시간 ${estimate}분")
                },
            ),
        )
        pricing.lines(state).filter { !it.inStock }.takeIf { it.isNotEmpty() }?.let { soldOut ->
            content.addView(
                Ui.body(this, "품절로 다시 골라야 하는 항목: " + soldOut.joinToString { it.label }),
            )
        }
        val confirmed = state.fields.values.count { it.status == FieldStatus.CONFIRMED }
        content.addView(
            Ui.body(
                this,
                "확인 완료 $confirmed · 확인 필요 ${openQuestions.size} · " +
                    "지금까지 필요한 확인·입력 ${state.resolutions.size}회",
            ),
        )
        // The confirm button lives in the stage banner at the top, where the
        // "next thing to do" line points at it.
    }

    /** A fixed walk of the four episodes, kept for the demo recording. */
    private fun renderScript() {
        content.addView(Ui.divider(this))
        content.addView(
            Ui.button(this, if (showScript) "대본 접기" else "E1–E4 대본 펼치기") {
                showScript = !showScript
                render()
            },
        )
        if (!showScript) return

        content.addView(Ui.section(this, "E1 — 첫 주문과 기억 허용범위 (${marahyang.name})"))
        content.addView(
            Ui.button(this, "${marahyang.name}에서 새 주문 시작") {
                clearTransientScreenState()
                act { it.startNewOrder(marahyang) }
            },
        )
        content.addView(
            Ui.button(this, "\"1만5천원 이하로 따뜻한 국물, 앞으로도 맵지 않게 해줘\"") {
                onSend("1만5천원 이하로 따뜻한 국물, 앞으로도 맵지 않게 해줘")
            },
        )
        content.addView(Ui.button(this, "\"추천해줘\"") { onSend("추천해줘") })
        content.addView(
            Ui.button(this, "\"앞으로도 수저 빼고\"") { onSend("앞으로도 수저 빼고") },
        )
        content.addView(
            Ui.button(this, "시간 경과 — 평가 요청 도착") { act { it.advanceTime() } },
        )
        content.addView(
            Ui.button(this, "\"고수 향이 세서 힘들었어\" 평가 남기기") {
                currentRestaurant()?.let { restaurant ->
                    reviewRating = surface.ratingOptions().firstOrNull { it.token == "rating.ok" }
                    onReview(restaurant, "고수 향이 세서 힘들었어")
                }
            },
        )
        content.addView(
            Ui.body(this, "→ 승인 범위에서 \"같은 메뉴 유형이면 어디서든\"을 고른다."),
        )

        content.addView(Ui.section(this, "E2 — 다른 식당, 같은 마라 유형 (${geumson.name})"))
        content.addView(
            Ui.button(this, "다음 주문 session · ${geumson.name}") {
                clearTransientScreenState()
                act { it.nextOrderSession(geumson) }
            },
        )
        content.addView(
            Ui.button(this, "\"2만5천원 이하로 30분 안에, 추천해줘\"") {
                onSend("2만5천원 이하로 30분 안에, 추천해줘")
            },
        )
        content.addView(
            Ui.body(this, "→ 마라 떡볶이와 국물 떡볶이를 담으면 고수가 자동으로 빠져 있다."),
        )
        content.addView(
            Ui.button(this, "\"이번 주문만 치즈 추가해줘\"") { onSend("이번 주문만 치즈 추가해줘") },
        )

        content.addView(Ui.section(this, "E3 — 예외·권한 철회·취향 정정"))
        content.addView(
            Ui.button(this, "다음 주문 session · ${geumson.name}") {
                clearTransientScreenState()
                act { it.nextOrderSession(geumson) }
            },
        )
        content.addView(
            Ui.button(this, "\"이번 주문만 아주 맵게 해줘\"") { onSend("이번 주문만 아주 맵게 해줘") },
        )
        content.addView(
            Ui.button(this, "\"일회용 수저는 앞으로 자동으로 정하지 마\"") {
                onSend("일회용 수저는 앞으로 자동으로 정하지 마")
            },
        )
        content.addView(
            Ui.button(this, "저장된 맵기를 중간맛으로 정정") {
                act { it.correctPreference(PreferenceScopes.global(Slots.SPICINESS), "spice.medium") }
            },
        )

        content.addView(Ui.section(this, "E4 — 미제공 옵션·품절·부분복구 (${marahyang.name})"))
        content.addView(
            Ui.button(this, "다음 주문 session · ${marahyang.name}") {
                clearTransientScreenState()
                act { it.nextOrderSession(marahyang) }
            },
        )
        content.addView(
            Ui.button(this, "\"마라탕으로 할게\"") { onSend("마라탕으로 할게") },
        )
        content.addView(
            Ui.body(
                this,
                "→ 고수는 E1 승인대로 자동 적용되지만, 이 집은 중간맛을 내지 않아 맵기만 다시 묻는다.",
            ),
        )
        content.addView(
            Ui.button(this, "\"꿔바로우 추가하고 소스는 따로 포장해줘\"") {
                onSend("꿔바로우 추가하고 소스는 따로 포장해줘")
            },
        )
        content.addView(
            Ui.button(this, "사이드 품절 event 도착 (더 높은 catalog version)") {
                act { it.applyCatalogEvent("catalog.marahyang.menu.guobaorou.soldout") }
            },
        )
        content.addView(
            Ui.button(this, "\"메모 지워줘\"") { onSend("메모 지워줘") },
        )
    }

    private fun renderInterruptions() {
        content.addView(Ui.divider(this))
        content.addView(Ui.section(this, "network·중단 상황"))
        listOf("ONLINE", "DELAYED", "OFFLINE", "UNKNOWN").forEach { network ->
            content.addView(
                Ui.button(this, "network을 $network 으로 설정") { act { it.setNetwork(network) } },
            )
        }
        content.addView(
            Ui.button(this, "이 process 종료 (재실행 뒤 연속성 확인)") {
                // A real process death, not a screen that pretends. State is already
                // durably committed, so the next launch reconciles from disk.
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            },
        )
    }

    private fun renderNavigation() {
        content.addView(Ui.divider(this))
        content.addView(
            Ui.button(this, "내 취향과 기억") {
                startActivity(Intent(this, MemoryActivity::class.java))
            },
        )
        content.addView(
            Ui.button(this, "비교 실행 (full / claim-off)") {
                startActivity(Intent(this, ComparisonActivity::class.java))
            },
        )
        content.addView(
            Ui.button(this, "평가·내보내기") {
                startActivity(Intent(this, ProbeConsoleActivity::class.java))
            },
        )
    }

    // -------------------------------------------------------------- helpers

    private fun act(block: (ProductSurface) -> StepOutcome) {
        val outcome = try {
            block(surface)
        } catch (error: Exception) {
            Toast.makeText(this, "처리하지 못했습니다: ${error.message}", Toast.LENGTH_LONG).show()
            return
        }
        lastDecision = describe(outcome)
        render()
    }

    /** Same as [act] for actions whose step outcome carries nothing to report. */
    private fun actQuiet(block: (ProductSurface) -> Unit) {
        try {
            block(surface)
        } catch (error: Exception) {
            Toast.makeText(this, "처리하지 못했습니다: ${error.message}", Toast.LENGTH_LONG).show()
            return
        }
        render()
    }

    private fun describe(outcome: StepOutcome): String {
        val result = outcome.result
        return "${humanDecision(result.optString("decision_state"))} " +
            "(${result.optString("decision_state")}, ${result.optString("operation")})"
    }

    private fun decisionSentence(outcome: StepOutcome): String =
        when (outcome.result.optString("decision_state")) {
            "ACT" -> "초안을 확인해 가상 주문으로 기록했습니다."
            "ASK" -> "확인이 필요한 항목이 남아 있습니다."
            "WAIT" -> "최신 메뉴정보를 받지 못해 기다리는 중입니다. 초안은 그대로 있습니다."
            "ABSTAIN" -> "조건을 모두 만족하는 안이 없어 진행을 멈췄습니다."
            "PENDING" -> "아직 확정하지 않았습니다."
            "CONFIRMED_COMPLETE" -> "이미 처리한 요청입니다. 중복으로 반영하지 않았습니다."
            "FAILED" -> "복구가 필요합니다."
            else -> "상태를 갱신했습니다."
        }

    private fun humanDecision(decision: String): String = when (decision) {
        "ACT" -> "초안 확정 — 가상 주문 기록"
        "ASK" -> "확인 필요 — 질문"
        "WAIT" -> "대기 — 최신 메뉴정보 필요"
        "ABSTAIN" -> "중단 — 안전한 대안 없음"
        "PENDING" -> "진행 중 — 아직 확정 아님"
        "CONFIRMED_COMPLETE" -> "이미 처리된 요청 (중복 반영 없음)"
        "FAILED" -> "복구 필요"
        else -> "상태 변경"
    }

    private fun networkLabel(state: ProductionState): String = when (state.network.name) {
        "ONLINE" -> "ONLINE — 현재 메뉴정보 확인 가능"
        "DELAYED" -> "DELAYED — 최신 메뉴정보 지연"
        "OFFLINE" -> "OFFLINE — commit 보류"
        else -> "UNKNOWN — 최신 여부 미확인"
    }

    private fun armLabel(): String = when (Production.selectedArm(this).namespace) {
        "claim-off" -> "claim-off (최근 주문 스냅샷 재사용)"
        else -> "full (ASPR 켜짐)"
    }

    private companion object {
        const val MAX_CHAT_LINES = 12
    }
}
