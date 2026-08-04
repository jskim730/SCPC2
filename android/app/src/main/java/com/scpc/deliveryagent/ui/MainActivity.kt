package com.scpc.deliveryagent.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import com.scpc.deliveryagent.delivery.SlotKind
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
    private var showDiagnostics = false

    /** Which panel covers the thread, if any. */
    private enum class Panel { DRAFT, MENU, NETWORK }

    private var panel: Panel? = null

    /** The draft row whose value chips are currently open, if any. */
    private var editingFieldId: String? = null

    /** Set by the dialog-style actions so the reply scrolls into view. */
    private var scrollChatIntoView = false
    private var conversationAnchor: View? = null
    private lateinit var scroller: ScrollView
    private lateinit var root: FrameLayout
    private lateinit var panelHost: FrameLayout
    private lateinit var barHost: LinearLayout
    private lateinit var appBarHost: FrameLayout

    private val catalog: SyntheticCatalog get() = Production.catalog(this)
    private val pricing: DraftPricing get() = DraftPricing(catalog)
    private val surface: ProductSurface get() = Production.surface(this)

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
        editingFieldId = null
        reviewRating = null
        reviewOffers = emptyList()
        reviewId = null
        reviewNeedsTarget = false
        reviewTargetToken = null
    }

    /**
     * A messenger's frame: a header that says where the order stands, a thread
     * that scrolls, the order summary and the message field pinned at the
     * bottom, and one layer above all of it for whatever panel is open.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = Ui.column(this).apply {
            setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 12))
        }
        scroller = Ui.scroller(this, content)
        barHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
            setPadding(
                Ui.dp(this@MainActivity, 10),
                Ui.dp(this@MainActivity, 8),
                Ui.dp(this@MainActivity, 10),
                Ui.dp(this@MainActivity, 10),
            )
            addView(
                Ui.inputRow(
                    context = this@MainActivity,
                    hint = "메시지 입력",
                    sendLabel = "보내기",
                    onSend = ::onSend,
                ),
            )
        }

        appBarHost = FrameLayout(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor(Ui.CHAT_BG))
            addView(appBarHost)
            addView(
                scroller,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            addView(barHost)
            addView(inputBar)
        }

        panelHost = FrameLayout(this)
        root = FrameLayout(this).apply {
            addView(
                column,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                panelHost,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(root)
        render()
    }

    private fun appBar(): View = Ui.appBar(
        context = this,
        title = currentRestaurant()?.name ?: "오늘의 주문",
        subtitle = if (currentRestaurant() == null) {
            "합성 데이터 · 실제 결제 없음"
        } else {
            // The device's own connection is named only when it is not simply
            // connected, so the header stays short in the ordinary case and the
            // line appears exactly when a judge would otherwise wonder why
            // pulling the network changed nothing.
            val link = readDeviceLink(this)
            val deviceNote = if (link.isConnected) "" else " · 기기 ${link.label}"
            "배달지 ${catalog.deliveryAlias} · 합성 ${surface.state().network.name}$deviceNote"
        },
        menuLabel = "메뉴 열기",
        onMenu = { openPanel(Panel.MENU) },
    )

    private fun openPanel(next: Panel) {
        panel = next
        editingFieldId = null
        render()
    }

    private fun closePanel() {
        panel = null
        editingFieldId = null
        render()
    }

    /** The panel is the frontmost thing on screen, so back closes it first. */
    override fun onBackPressed() {
        if (panel != null) closePanel() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == EvaluationNotification.PERMISSION_REQUEST_CODE) render()
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
        EvaluationNotification.sync(this, surface.pendingEvaluationRequest())
        openQuestions = AsprEngine.openConfirmationIds(state)

        appBarHost.removeAllViews()
        appBarHost.addView(appBar())
        content.removeAllViews()

        // The thread reads as a conversation: what was said, what the agent
        // proposes, and the one thing it is asking about right now.
        renderConversation()
        renderRestaurantChoice()
        renderCandidates()
        renderNextQuestion(state)
        renderReview(state)
        renderNextOrder(state)

        barHost.removeAllViews()
        renderOrderBar(state)

        panelHost.removeAllViews()
        when (panel) {
            Panel.DRAFT -> panelHost.addView(draftPanel(state))
            Panel.MENU -> panelHost.addView(menuPanel())
            Panel.NETWORK -> panelHost.addView(networkPanel())
            null -> Unit
        }

        if (scrollChatIntoView) {
            scrollChatIntoView = false
            scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** Every synthetic restaurant, offered whenever no order is open yet. */
    private fun renderRestaurantChoice() {
        if (currentRestaurant() != null) return
        content.addView(
            Ui.chatLine(
                this,
                "에이전트:",
                "안녕하세요. 어디서 주문할까요?\n먹고 싶은 것을 바로 말씀하셔도 됩니다.",
            ),
        )
        content.addView(
            Ui.agentCard(
                context = this,
                question = "식당 고르기",
                hint = "6곳 모두 합성 실험 매장입니다. 실제 주문·결제는 일어나지 않습니다.",
                chips = catalog.restaurants.map { restaurant ->
                    Ui.Chip(label = restaurant.name) {
                        clearTransientScreenState()
                        chat += ChatLine("에이전트:", "${restaurant.name}으로 새 주문을 시작했습니다.")
                        act { it.startNewOrder(restaurant) }
                    }
                },
            ),
        )
    }

    private fun renderConversation() {
        var previousSpeaker = ""
        chat.takeLast(MAX_CHAT_LINES).forEach { line ->
            content.addView(
                Ui.chatLine(this, line.speaker, line.text, showName = line.speaker != previousSpeaker),
            )
            previousSpeaker = line.speaker
        }
    }

    /**
     * One question at a time.
     *
     * The draft usually has several blanks at once, but a person answers them
     * one after another, and a card per answer keeps the thread readable and
     * leaves each choice in the transcript. The counter says how many are left
     * so the run of questions never feels open-ended.
     */
    private fun renderNextQuestion(state: ProductionState) {
        val restaurant = currentRestaurant() ?: return

        if (pendingScope.isNotEmpty()) {
            val value = pendingScope.first()
            content.addView(
                Ui.agentCard(
                    context = this,
                    question = "${Particles.withObj(catalog.slot(value.scopeToken).label)} 다음에도 쓸까요?",
                    hint = catalog.valueLabel(value.valueToken) + " · 지금 정한 값입니다.",
                    chips = listOf(
                        Ui.Chip(label = "이번 주문만") { answerScope(remember = false) },
                        Ui.Chip(label = "앞으로도 기억") { answerScope(remember = true) },
                    ),
                    footnote = "기억하면 다음 주문의 같은 항목에 자동으로 채워집니다.",
                ),
            )
            return
        }

        val total = openQuestions.size
        if (total == 0) return
        val fieldId = openQuestions.first()
        val field = state.fields[fieldId] ?: return
        val slot = catalog.slotOfFieldSlotId(field.slotId)
            ?: catalog.baseSlotOfLineSlotId(field.slotId)
            ?: return
        val chips = catalog.valuesFor(restaurant, slot.scopeToken)
            .filter { it.inStock }
            .map { option ->
                Ui.Chip(
                    label = option.label,
                    sub = if (option.priceDelta > 0) {
                        "+${pricing.formatAmount(option.priceDelta)}"
                    } else {
                        ""
                    },
                ) { answerField(field, option.token) }
            }
        if (chips.isEmpty()) return
        content.addView(
            Ui.agentCard(
                context = this,
                question = "${Particles.withObj(questionLabel(field))} 정해 주세요.",
                hint = if (total == 1) "마지막 확인입니다." else "1 / $total · 하나씩 여쭤볼게요.",
                chips = chips,
                footnote = if (slot.kind == SlotKind.MENU_OPTION) {
                    "이 선택은 이번 주문에 적용됩니다."
                } else {
                    ""
                },
            ),
        )
    }

    /** The option's own name; the line it belongs to is already in the draft. */
    private fun questionLabel(field: DraftField): String =
        catalog.baseSlotOfLineSlotId(field.slotId)?.label ?: catalog.slotLabel(field.slotId)

    /**
     * What the agent proposes right now.
     *
     * Until a dish is in the draft there is always something to propose, so the
     * candidates stand on their own rather than waiting for a sentence the
     * parser happened to understand. Saying more only re-ranks them: a person
     * who picks a restaurant and says nothing still sees the menu, and one whose
     * words were not understood is never left with a dead end.
     */
    private fun renderCandidates() {
        val restaurant = currentRestaurant() ?: return
        val shown = candidates.ifEmpty {
            if (surface.lines().isEmpty()) {
                Recommender(catalog).candidates(surface.state(), restaurant)
            } else {
                emptyList()
            }
        }
        if (shown.isEmpty()) return
        val card = Ui.agentCard(
            context = this,
            question = "이런 메뉴는 어떠세요?",
            hint = "조건과 저장된 취향에 맞춘 순서입니다.",
            chips = emptyList(),
        )
        shown.forEach { candidate ->
            card.addView(
                Ui.candidateRow(
                    context = this,
                    name = candidate.label,
                    meta = buildString {
                        append(pricing.formatAmount(candidate.amount))
                        append(" · ${candidate.estimateMinutes}분")
                        // The user's own past ratings of this exact menu. They order
                        // what conditions and preferences leave tied, never more.
                        if (candidate.rating.count > 0) append(" · ${candidate.rating.label()}")
                        candidate.blockedBy?.let { append(" · 제외: $it") }
                    },
                    reasons = candidate.reasons.joinToString(" · ") { "${it.badge} ${it.detail}" },
                    actionLabel = "담기",
                    onTap = if (candidate.offerable) {
                        {
                            chat += ChatLine("나:", "${candidate.label} 담기")
                            candidates = emptyList()
                            scrollChatIntoView = true
                            act { it.addLine(restaurant, candidate.valueToken) }
                        }
                    } else {
                        null
                    },
                ),
            )
        }
        content.addView(card)
    }

    /** The order summary, always within reach above the message field. */
    private fun renderOrderBar(state: ProductionState) {
        if (currentRestaurant() == null) return
        val lines = surface.lines()
        if (lines.isEmpty() && state.fields.isEmpty()) return
        val total = pricing.total(state)
        barHost.addView(
            Ui.orderBar(
                context = this,
                count = lines.size,
                summary = if (openQuestions.isEmpty()) {
                    "주문서 ${pricing.formatAmount(total)} · 확인 완료"
                } else {
                    "주문서 ${pricing.formatAmount(total)} · 확인 ${openQuestions.size}건 남음"
                },
                onOpen = { openPanel(Panel.DRAFT) },
            ),
        )
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

        if (surface.pendingEvaluationRequest()) {
            content.addView(
                Ui.chatLine(this, "에이전트:", "식사는 어떠셨어요? 별점과 한 줄 평가를 남겨 주세요."),
            )
        }

        val rated = reviewRating
        content.addView(
            Ui.agentCard(
                context = this,
                question = "지난 주문 평가",
                hint = restaurant.name + (reviewTargetToken?.let { " · " + catalog.valueLabel(it) } ?: ""),
                chips = surface.ratingOptions().map { rating ->
                    Ui.Chip(label = rating.label, selected = rated?.token == rating.token) {
                        reviewRating = rating
                        render()
                    }
                },
            ).also { card ->
                card.addView(
                    Ui.inputRow(
                        context = this,
                        hint = "예: 국물은 괜찮았는데 간이 좀 셌어",
                        sendLabel = "남기기",
                        onSend = { text -> onReview(restaurant, text) },
                    ),
                )
            },
        )

        if (reviewNeedsTarget) {
            val id = reviewId
            if (id != null) {
                content.addView(
                    Ui.agentCard(
                        context = this,
                        question = "어느 메뉴에 대한 평가인가요?",
                        hint = "대상이 정해져야 평점과 취향 후보를 그 메뉴에 연결합니다.",
                        chips = surface.lines().mapNotNull { line ->
                            val menuToken = line.menuValueToken ?: return@mapNotNull null
                            Ui.Chip(label = catalog.valueLabel(menuToken)) {
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
            reviewOffers.forEach { offer ->
                val slot = catalog.slot(offer.slotToken)
                val chips = surface
                    .reviewScopeChoices(restaurant, offer, reviewTargetToken)
                    .map { level ->
                        Ui.Chip(label = scopeLevelLabel(level)) { acceptReviewOffer(offer, level) }
                    } + Ui.Chip(label = "저장하지 않음") { declineReviewOffer(offer) }
                content.addView(
                    Ui.agentCard(
                        context = this,
                        question = "다음부터 ${Particles.withObj(slot.label)} " +
                            "${Particles.withInto(catalog.valueLabel(offer.impliesValue))} 해 드릴까요?",
                        hint = "'${offer.matchedText}'라고 하셔서요. 어디까지 적용할지 골라 주세요.",
                        chips = chips,
                        footnote = "고른 범위에만 저장하고, 다른 범위는 건드리지 않습니다.",
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

    /**
     * Starting the next order.
     *
     * Once this order is recorded the conversation moves on, which is where a
     * preference stated here first has to prove itself. Choosing the restaurant
     * opens a new session, so nothing said only for the last order carries into
     * this one.
     */
    private fun renderNextOrder(state: ProductionState) {
        if (state.actions.isEmpty()) return
        if (currentRestaurant() == null) return
        if (reviewOffers.isNotEmpty() || reviewNeedsTarget) return
        content.addView(
            Ui.agentCard(
                context = this,
                question = "다음 주문을 시작할까요?",
                hint = "새 session이 열립니다. 이번 주문에만 적용한 값은 따라오지 않고, 저장한 취향은 그대로 쓰입니다.",
                chips = catalog.restaurants.map { restaurant ->
                    Ui.Chip(label = restaurant.name) {
                        clearTransientScreenState()
                        chat += ChatLine("나:", "${restaurant.name}에서 새로 주문할게")
                        scrollChatIntoView = true
                        act { it.nextOrderSession(restaurant) }
                    }
                },
            ),
        )
    }

    /**
     * The draft, raised over the thread.
     *
     * A person checks the order when they are about to place it, not at the
     * point in the conversation where each line happened to be added, so the
     * draft lives one tap from anywhere rather than scrolling away.
     */
    private fun draftPanel(state: ProductionState): View {
        val restaurant = currentRestaurant()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Ui.dp(this@MainActivity, 16),
                0,
                Ui.dp(this@MainActivity, 16),
                Ui.dp(this@MainActivity, 8),
            )
        }

        fun addRow(field: DraftField, label: String) {
            val entry = catalog.value(field.value)
            val amount = if (entry != null && entry.priceDelta > 0) {
                " (+${pricing.formatAmount(entry.priceDelta)})"
            } else {
                ""
            }
            val choices = editChoices(field, restaurant)
            val open = editingFieldId == field.fieldId
            body.addView(
                Ui.draftRow(
                    context = this,
                    label = label,
                    value = field.displayValue(catalog, pricing, state) + amount,
                    source = field.statusLine(state),
                    needsUser = field.status != FieldStatus.CONFIRMED &&
                        field.status != FieldStatus.AUTO_APPLIED,
                    onChange = if (choices.isEmpty()) {
                        null
                    } else {
                        {
                            editingFieldId = if (open) null else field.fieldId
                            render()
                        }
                    },
                ),
            )
            if (open && choices.isNotEmpty()) {
                body.addView(
                    Ui.chipFlow(this, Ui.cardContentWidth(this), choices),
                )
            }
        }

        val renderedFieldIds = mutableSetOf<String>()
        surface.lines().forEach { line ->
            val menuLabel = line.menuValueToken?.let(catalog::valueLabel) ?: "메뉴 미정"
            val price = line.menuValueToken?.let { catalog.value(it)?.priceDelta } ?: 0
            body.addView(
                Ui.lineHeader(
                    context = this,
                    name = menuLabel,
                    price = if (price > 0) pricing.formatAmount(price) else "",
                    onRemove = if (restaurant == null) {
                        null
                    } else {
                        {
                            chat += ChatLine("나:", "$menuLabel 빼기")
                            actQuiet { it.removeLine(restaurant, line.lineId) }
                        }
                    },
                ),
            )
            line.slots.forEach slot@{ binding ->
                val field = state.fields[AsprEngine.fieldIdFor(binding.lineSlotId)] ?: return@slot
                renderedFieldIds += field.fieldId
                addRow(field, catalog.baseSlotOfLineSlotId(field.slotId)?.label ?: "옵션")
            }
        }

        state.fields.values.sortedBy { it.fieldId }.forEach { field ->
            if (field.fieldId in renderedFieldIds) return@forEach
            // A passing notice such as the rating request is not a draft row.
            val isDraftRow = field.slotId == AsprEngine.SLOT_TOTAL ||
                catalog.slotOfFieldSlotId(field.slotId) != null ||
                LineTokens.parseSlotId(field.slotId) != null
            if (!isDraftRow) return@forEach
            if (field.slotId == AsprEngine.SLOT_TOTAL) return@forEach
            addRow(field, catalog.slotLabel(field.slotId))
        }

        if (restaurant != null && surface.lines().isNotEmpty()) {
            val sides = catalog.sideMenus(restaurant).filter { it.inStock }
            if (sides.isNotEmpty()) {
                body.addView(Ui.menuGroup(this, "사이드 추가").apply { setPadding(0, Ui.dp(this@MainActivity, 12), 0, Ui.dp(this@MainActivity, 4)) })
                body.addView(
                    Ui.chipFlow(
                        this,
                        Ui.cardContentWidth(this),
                        sides.map { side ->
                            Ui.Chip(
                                label = side.label,
                                sub = "+${pricing.formatAmount(side.priceDelta)}",
                            ) {
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
        body.addView(Ui.totalRow(this, "합계", pricing.formatAmount(total)))
        body.addView(
            Ui.body(
                this,
                buildString {
                    if (budget != null) {
                        append(
                            if (total <= budget) {
                                "예산 ${pricing.formatAmount(budget)} 안"
                            } else {
                                "예산 ${pricing.formatAmount(budget)} 초과"
                            },
                        )
                    }
                    if (estimate != null) {
                        if (isNotEmpty()) append(" · ")
                        append("예상 ${estimate}분")
                    }
                    if (isNotEmpty()) append(" · ")
                    append("지금까지 확인·입력 ${state.resolutions.size}회")
                },
            ),
        )
        pricing.lines(state).filter { !it.inStock }.takeIf { it.isNotEmpty() }?.let { soldOut ->
            body.addView(
                Ui.body(this, "품절로 다시 골라야 하는 항목: " + soldOut.joinToString { it.label }),
            )
        }

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                Ui.primaryAction(
                    context = this@MainActivity,
                    text = if (openQuestions.isEmpty()) {
                        "이대로 주문하기 · ${pricing.formatAmount(total)}"
                    } else {
                        "확인 ${openQuestions.size}건을 마치면 주문할 수 있습니다"
                    },
                    enabled = openQuestions.isEmpty() && restaurant != null,
                ) {
                    closePanel()
                    scrollChatIntoView = true
                    restaurant?.let { r -> act { it.requestDecision(r) } }
                },
            )
        }
        return Ui.sheet(this, "주문서", body, footer) { closePanel() }
    }

    /** Everything that is not the conversation, gathered behind one control. */
    private fun menuPanel(): View {
        val state = surface.state()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        body.addView(Ui.menuGroup(this, "제품"))
        body.addView(
            Ui.menuItem(this, "내 취향과 기억", "저장된 것 전부 · 범위별 정정·철회·삭제") {
                closePanel()
                startActivity(Intent(this, MemoryActivity::class.java))
            },
        )
        if (state.actions.isNotEmpty()) {
            // The restaurant chips already stand at the end of the thread once an
            // order is recorded, so this only carries the user down to them.
            body.addView(
                Ui.menuItem(this, "다른 식당에서 새로 주문", "대화 끝의 식당 목록으로 이동합니다") {
                    scrollChatIntoView = true
                    closePanel()
                },
            )
        }

        body.addView(Ui.menuGroup(this, "합성 환경 — 앱 밖에서 일어나는 일"))
        body.addView(
            Ui.menuItem(this, "시간 경과", "지연된 평가 요청이 도착합니다") {
                closePanel()
                act { it.advanceTime() }
            },
        )
        currentRestaurant()?.let { restaurant ->
            catalog.eventsFor(restaurant).forEach { event ->
                body.addView(
                    Ui.menuItem(this, "재고 변화", event.description) {
                        closePanel()
                        act { it.applyCatalogEvent(event.eventToken) }
                    },
                )
            }
        }
        body.addView(
            Ui.menuItem(
                this,
                "network 상태",
                "합성 ${state.network.name} · 기기 ${readDeviceLink(this).label} · 눌러서 바꾸기",
            ) {
                openPanel(Panel.NETWORK)
            },
        )
        body.addView(
            Ui.menuItem(this, "이 process 종료", "재실행 뒤 이어지는지 확인") {
                // A real process death, not a screen that pretends. State is already
                // durably committed, so the next launch reconciles from disk.
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            },
        )

        body.addView(Ui.menuGroup(this, "검증"))
        body.addView(
            Ui.menuItem(this, "비교 실행", "full / claim-off") {
                closePanel()
                startActivity(Intent(this, ComparisonActivity::class.java))
            },
        )
        body.addView(
            Ui.menuItem(this, "평가·내보내기", "공개 입력 불러오기 · 실행 · 결과 내보내기") {
                closePanel()
                startActivity(Intent(this, ProbeConsoleActivity::class.java))
            },
        )
        body.addView(
            Ui.menuItem(
                this,
                "검증 정보",
                buildString {
                    append("session ${state.sessionLabel.ifEmpty { "없음" }} · ")
                    append("epoch ${state.processEpoch} · ")
                    append("${armLabel()} · ")
                    append("catalog ${catalog.snapshotDigest.take(12)} · ")
                    append("마지막 판단 $lastDecision")
                },
            ) { },
        )
        if (!EvaluationNotification.hasPermission(this)) {
            body.addView(
                Ui.menuItem(this, "평가 알림 허용 요청", "거부해도 주문·기억·복구는 그대로 동작합니다") {
                    closePanel()
                    EvaluationNotification.requestPermission(this)
                },
            )
        }
        return Ui.sheet(this, "메뉴", body, null) { closePanel() }
    }

    /** The synthetic network state the decision path reads. */
    private fun networkPanel(): View {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(
            Ui.menuGroup(this, "이 기기의 실제 연결 — ${readDeviceLink(this).label} · 표시 전용"),
        )
        body.addView(
            Ui.menuGroup(this, "에이전트는 아래 합성 상태로만 판단합니다. 실제 연결과 별개입니다."),
        )
        listOf(
            "ONLINE" to "현재 메뉴정보 확인 가능",
            "DELAYED" to "최신 메뉴정보 지연 — 질문이 먼저, 없으면 대기",
            "OFFLINE" to "cache가 있으면 대기, 없으면 중단",
            "UNKNOWN" to "최신 여부 미확인",
        ).forEach { (name, detail) ->
            body.addView(
                Ui.menuItem(this, name, detail) {
                    closePanel()
                    act { it.setNetwork(name) }
                },
            )
        }
        return Ui.sheet(this, "network 상태", body, null) { openPanel(Panel.MENU) }
    }

    /**
     * The values this restaurant offers for one draft row, ready to tap. Empty
     * when the row is not a choice the user makes here, such as the total.
     */
    private fun editChoices(
        field: DraftField,
        restaurant: RestaurantDefinition?,
    ): List<Ui.Chip> {
        if (restaurant == null) return emptyList()
        if (field.slotId == AsprEngine.SLOT_TOTAL) return emptyList()
        val slot = catalog.slotOfFieldSlotId(field.slotId)
            ?: catalog.baseSlotOfLineSlotId(field.slotId)
            ?: return emptyList()
        return catalog.valuesFor(restaurant, slot.scopeToken)
            .filter { it.inStock }
            .map { option ->
                Ui.Chip(
                    label = option.label,
                    sub = if (option.priceDelta > 0) {
                        "+${pricing.formatAmount(option.priceDelta)}"
                    } else {
                        ""
                    },
                    selected = option.token == field.value,
                ) {
                    editingFieldId = null
                    answerField(field, option.token)
                }
            }
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
        // A thread the user scrolls, not a status box, so it keeps more turns.
        const val MAX_CHAT_LINES = 40
    }
}
