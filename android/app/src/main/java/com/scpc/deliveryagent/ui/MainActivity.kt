package com.scpc.deliveryagent.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import com.scpc.deliveryagent.core.AsprEngine
import com.scpc.deliveryagent.core.FieldStatus
import com.scpc.deliveryagent.core.ProductionState
import com.scpc.deliveryagent.core.StepOutcome
import com.scpc.deliveryagent.delivery.DraftPricing
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
    private var lastDecision = "-"
    private var showScript = false

    private val catalog: SyntheticCatalog get() = Production.catalog(this)
    private val pricing: DraftPricing get() = DraftPricing(catalog)
    private val surface: ProductSurface get() = Production.surface(this)

    private fun restaurant(token: String): RestaurantDefinition =
        catalog.restaurant(token) ?: error("synthetic catalog is missing $token")

    private val daon get() = restaurant("restaurant.daon")
    private val ongi get() = restaurant("restaurant.ongi")
    private val bulkkot get() = restaurant("restaurant.bulkkot")

    /** The restaurant the order is currently against. */
    private fun currentRestaurant(): RestaurantDefinition? =
        catalog.restaurant(surface.state().targetEntityId)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = Ui.column(this)
        setContentView(Ui.scroller(this, content))
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ----------------------------------------------------------------- chat

    private fun onSend(message: String) {
        chat += ChatLine("나:", message)
        var restaurant = currentRestaurant()
        if (restaurant == null) {
            // Nothing to order against yet, so the first message opens an order and
            // says so rather than silently picking somewhere.
            restaurant = daon
            surface.startNewOrder(restaurant)
            chat += ChatLine("에이전트:", "${restaurant.name}으로 새 주문을 시작했습니다.")
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
                "${slot.label}을 ${catalog.valueLabel(value.valueToken)}로 반영했습니다 ($scope).",
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
        val pending = pendingScope
        pendingScope = emptyList()
        pending.forEach { value ->
            surface.remember(
                restaurant = restaurant,
                slot = catalog.slot(value.scopeToken),
                value = value.valueToken,
                stable = remember,
            )
        }
        chat += ChatLine("나:", if (remember) "앞으로도 기억해줘" else "이번 주문만")
        pending.forEach { value ->
            chat += ChatLine(
                "에이전트:",
                "${catalog.slot(value.scopeToken).label}을 " +
                    "${catalog.valueLabel(value.valueToken)}로 " +
                    (if (remember) "저장하고 다음 주문에도 씁니다." else "이번 주문에만 적용합니다."),
            )
        }
        render()
    }

    /** Answers one open option question by tapping a value the current menu offers. */
    private fun answerOption(slot: SlotDefinition, valueToken: String) {
        val restaurant = currentRestaurant() ?: return
        act {
            if (slot.priced) {
                it.chooseLine(restaurant, slot, valueToken)
            } else {
                it.remember(restaurant, slot, valueToken, stable = false)
            }
        }
        chat += ChatLine("나:", "${slot.label}: ${catalog.valueLabel(valueToken)}")
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

        content.addView(
            Ui.inputRow(
                context = this,
                hint = "예: 2만원 이하로 따뜻한 국물, 앞으로도 맵지 않게 해줘",
                sendLabel = "보내기",
                onSend = ::onSend,
            ),
        )

        renderConversation()
        renderQuestions(state)
        renderCandidates()
        renderReview(state)
        renderSituation(state)
        renderDraft(state)
        renderScript()
        renderInterruptions()
        renderNavigation()
    }

    private fun renderConversation() {
        if (chat.isEmpty()) {
            content.addView(
                Ui.body(this, "무엇을 먹고 싶은지, 예산과 조건을 그대로 말해 주세요."),
            )
            return
        }
        content.addView(Ui.section(this, "대화"))
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
            val slot = catalog.slotOfFieldSlotId(field.slotId) ?: return@forEach
            content.addView(Ui.body(this, "${slot.label}을 정해 주세요."))
            val choices = catalog.valuesFor(restaurant, slot.scopeToken)
                .filter { it.inStock }
                .map { option ->
                    val label = if (option.priceDelta > 0) {
                        "${option.label} ${pricing.formatAmount(option.priceDelta)}"
                    } else {
                        option.label
                    }
                    label to { answerOption(slot, option.token) }
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
                        // The user's own past ratings, shown for comparison only.
                        // They do not move the ranking above.
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
                            answerOption(catalog.slot(Slots.MAIN), candidate.valueToken)
                            candidates = emptyList()
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

        if (reviewOffers.isNotEmpty()) {
            content.addView(
                Ui.body(this, "이 내용을 다음 주문에 참고할까요? 참고해도 자동으로 적용하지는 않습니다."),
            )
            reviewOffers.forEach { offer ->
                val slot = catalog.slot(offer.slotToken)
                content.addView(
                    Ui.body(
                        this,
                        "'${offer.matchedText}' → 다음에는 ${slot.label}을 " +
                            "${catalog.valueLabel(offer.impliesValue)}로 제안",
                    ),
                )
                content.addView(
                    Ui.chipRow(
                        this,
                        listOf(
                            "참고할게요" to { acceptReviewOffer(offer) },
                            "이번만이었어요" to { declineReviewOffer(offer) },
                        ),
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
                        surface.deleteReview(record.reviewId)
                        if (reviewId == record.reviewId) {
                            reviewId = null
                            reviewOffers = emptyList()
                        }
                        chat += ChatLine("에이전트:", "평가와 그 평가로 배운 내용을 지웠습니다.")
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
        chat += ChatLine("나:", text)
        turn.notes.forEach { chat += ChatLine("에이전트:", it) }
        chat += ChatLine(
            "에이전트:",
            if (turn.offers.isEmpty()) {
                "평가를 기록했습니다. 다음 주문에 참고할 항목은 없습니다."
            } else {
                "평가를 기록했습니다. 다음 주문에 참고할지 아래에서 정해 주세요."
            },
        )
        render()
    }

    private fun acceptReviewOffer(offer: ReviewCandidate) {
        val id = reviewId ?: return
        surface.rememberFromReview(id, offer)
        reviewOffers = reviewOffers - offer
        chat += ChatLine("나:", "참고할게요")
        chat += ChatLine(
            "에이전트:",
            "${catalog.slot(offer.slotToken).label}을 " +
                "${catalog.valueLabel(offer.impliesValue)}로 다음 주문에 제안하겠습니다. " +
                "자동 적용은 하지 않고 그때 확인을 받습니다.",
        )
        render()
    }

    private fun declineReviewOffer(offer: ReviewCandidate) {
        surface.dismissFromReview(offer)
        reviewOffers = reviewOffers - offer
        chat += ChatLine("나:", "이번만이었어요")
        chat += ChatLine("에이전트:", "기억하지 않겠습니다. 평가 기록만 남습니다.")
        render()
    }

    private fun renderSituation(state: ProductionState) {
        content.addView(Ui.section(this, "현재 상황"))
        content.addView(
            Ui.body(
                this,
                buildString {
                    append("주문 session: ${state.sessionLabel.ifEmpty { "없음" }}\n")
                    val restaurant = currentRestaurant()
                    append("대상 식당: ${restaurant?.name ?: "미선택"}")
                    if (restaurant != null) {
                        // The user's own past ratings here, for comparison only.
                        val tally = Recommender(catalog).restaurantTally(state, restaurant)
                        if (tally.count > 0) append(" · ${tally.label()}")
                    }
                    append("\n")
                    append("배달지: ${catalog.deliveryAlias}\n")
                    append("network: ${networkLabel(state)}\n")
                    append("실행 세대(process epoch): ${state.processEpoch}\n")
                    append("비교 arm: ${armLabel()}\n")
                    append("마지막 판단: $lastDecision")
                },
            ),
        )
    }

    private fun renderDraft(state: ProductionState) {
        content.addView(Ui.section(this, "주문 초안"))
        if (state.fields.isEmpty()) {
            content.addView(Ui.body(this, "아직 초안이 없습니다."))
            return
        }
        state.fields.values.sortedBy { it.fieldId }.forEach { field ->
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
                    catalog.valueLabel(field.value) + amount,
                    "${field.status.userLabel()} · ${field.provenanceLabel(state)}",
                ),
            )
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
        if (openQuestions.isEmpty() && state.fields.isNotEmpty()) {
            content.addView(
                Ui.button(this, "이대로 확인하고 가상 주문 기록", primary = true) {
                    currentRestaurant()?.let { restaurant -> act { it.requestDecision(restaurant) } }
                },
            )
        }
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

        content.addView(Ui.section(this, "E1 — 첫 주문과 기억 허용범위"))
        content.addView(
            Ui.button(this, "${daon.name}에서 새 주문 시작") { act { it.startNewOrder(daon) } },
        )
        content.addView(
            Ui.button(this, "\"2만원 이하로 따뜻한 국물, 앞으로도 맵지 않게 해줘\"") {
                onSend("2만원 이하로 따뜻한 국물, 앞으로도 맵지 않게 해줘")
            },
        )
        content.addView(Ui.button(this, "\"추천해줘\"") { onSend("추천해줘") })
        content.addView(
            Ui.button(this, "\"앞으로도 수저 빼고 밥은 보통으로\"") {
                onSend("앞으로도 수저 빼고 밥은 보통으로")
            },
        )
        content.addView(
            Ui.button(this, "\"국물은 괜찮았는데 간이 좀 셌어\" 평가 남기기") {
                currentRestaurant()?.let { restaurant ->
                    reviewRating = surface.ratingOptions().firstOrNull { it.token == "rating.ok" }
                    onReview(restaurant, "국물은 괜찮았는데 간이 좀 셌어")
                }
            },
        )

        content.addView(Ui.section(this, "E2 — 새 식당에서 선택적 재사용"))
        content.addView(
            Ui.button(this, "다음 주문 session · ${ongi.name}") { act { it.nextOrderSession(ongi) } },
        )
        content.addView(Ui.button(this, "시간 경과 — 예약된 평가 도착") { act { it.advanceTime() } })
        content.addView(
            Ui.button(this, "\"1만8천원 이하로 30분 안에, 추천해줘\"") {
                onSend("1만8천원 이하로 30분 안에, 추천해줘")
            },
        )

        content.addView(Ui.section(this, "E3 — 오늘의 예외와 권한 철회"))
        content.addView(
            Ui.button(this, "다음 주문 session · ${bulkkot.name}") {
                act { it.nextOrderSession(bulkkot) }
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
            Ui.button(this, "저장된 밥 양을 적게로 정정") {
                act { it.correct(catalog.slot(Slots.RICE), "rice.small") }
            },
        )

        content.addView(Ui.section(this, "E4 — 품절·재실행·부분복구"))
        content.addView(
            Ui.button(this, "다음 주문 session · ${ongi.name}") { act { it.nextOrderSession(ongi) } },
        )
        content.addView(
            Ui.button(this, "\"만두 추가하고 소스는 따로 포장해줘\"") {
                onSend("만두 추가하고 소스는 따로 포장해줘")
            },
        )
        content.addView(
            Ui.button(this, "사이드 품절 event 도착 (더 높은 catalog version)") {
                act { it.applyCatalogEvent("catalog.ongi.side.dumpling.soldout") }
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
        content.addView(
            Ui.mono(this, "합성 catalog snapshot ${catalog.snapshotDigest.take(16)}"),
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
