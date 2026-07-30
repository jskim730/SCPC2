package com.scpc.deliveryagent.delivery

import com.scpc.deliveryagent.core.AsprEngine
import com.scpc.deliveryagent.core.DepKind
import com.scpc.deliveryagent.core.DraftField
import com.scpc.deliveryagent.core.FieldStatus
import com.scpc.deliveryagent.core.ProbeStep
import com.scpc.deliveryagent.core.ProductionCore
import com.scpc.deliveryagent.core.ProductionState
import com.scpc.deliveryagent.core.Role
import com.scpc.deliveryagent.core.StepOutcome
import com.scpc.deliveryagent.core.Ids
import org.json.JSONObject

/**
 * The delivery presentation layer.
 *
 * The generic core only knows fact identity, opaque values, authority, scope,
 * lifetime and dependency. This layer is the only place that turns those into
 * restaurants, menus and option labels a person can read, and turns a person's
 * tap into the same operations the Probe path uses. All prices, stock and
 * actions are synthetic; nothing leaves the device.
 */

/** How a draft field reached its current value, in words the user can act on. */
fun DraftField.provenanceLabel(state: ProductionState): String {
    val revoked = deps.any { it.kind == DepKind.PERMISSION && it.ref in state.revokedScopes }
    return when {
        status == FieldStatus.CONFIRMED -> "확인 완료"
        revoked -> "자동 적용 권한 철회 — 다시 확인"
        provenance == "EXPLICIT_STABLE_AUTO_APPLIED" -> "직접 저장, 자동 적용"
        provenance == "EXPLICIT_STABLE_NEEDS_CONFIRMATION" -> "직접 저장 — 확인 필요"
        provenance == "CURRENT_SESSION_INSTRUCTION" -> "오늘 입력"
        provenance == "CURRENT_DRAFT_REQUEST" -> "이번 주문 요청사항"
        provenance == "DELAYED_OUTCOME_PROPOSAL" -> "지난 평가 반영 — 확인 필요"
        provenance == "RANKING_PROPOSAL" -> "추천 순위 근거 — 확인 필요"
        provenance == "INFERRED_RANKING_ONLY" -> "추론 — 자동 적용하지 않음"
        provenance == "PRICED_LINE_ROLLUP" -> "현재 메뉴정보로 재계산"
        provenance == "REQUIRED_OPTION_UNRESOLVED" -> "확인 필요"
        else -> provenance
    }
}

fun FieldStatus.userLabel(): String = when (this) {
    FieldStatus.AUTO_APPLIED -> "자동 적용"
    FieldStatus.NEEDS_CONFIRMATION -> "확인 필요"
    FieldStatus.CONFIRMED -> "확인 완료"
    FieldStatus.INVALIDATED -> "변경됨 — 다시 확인"
}

/**
 * Drives the production core from the product screens.
 *
 * Every product action becomes one of the same thirteen operations, with the
 * same identifiers and the same ledger, so what a person sees on screen and what
 * the Probe path reports come from one engine.
 */
class ProductSurface(
    private val core: ProductionCore,
    private val catalog: SyntheticCatalog,
    private val intake: PreferenceIntake = RuleBasedIntake(catalog),
) {

    /** What one chat turn did, so the screen can report it honestly. */
    data class ChatTurn(
        val utterance: Utterance,
        val applied: List<ReadValue>,
        val questions: List<String>,
        val recommendations: List<Recommender.Candidate>,
        val decision: StepOutcome?,
    )

    /**
     * Reads one chat message and applies only what it settled.
     *
     * A value whose reuse scope the user did not state, an expression the catalog
     * marks as vague, and an option this restaurant does not offer all become
     * questions. They are never stored as a guess, because a draft that shortened
     * the conversation by overriding the user is not the value being claimed.
     */
    fun say(restaurant: RestaurantDefinition, message: String): ChatTurn {
        val offered = catalog.slotsOf(restaurant).map { it.scopeToken }.toSet() +
            catalog.slots.filter { it.kind != SlotKind.MENU_OPTION }.map { it.scopeToken }
        val utterance = intake.read(message, IntakeContext(restaurant, offered))

        val questions = utterance.questions.map { it.question }.toMutableList()
        val unresolved = utterance.questions.map { it.about }.toSet()
        val applied = mutableListOf<ReadValue>()

        utterance.values.forEach { value ->
            // A slot the reading left open stays open.
            if (value.scopeToken in unresolved) return@forEach
            val slot = catalog.slot(value.scopeToken)
            if (!catalog.accepts(restaurant, value.scopeToken, value.valueToken)) {
                questions += "${restaurant.name}에서 '${value.matchedText}'를 고를 수 없습니다. 다른 것으로 할까요?"
                return@forEach
            }
            when (slot.kind) {
                SlotKind.USER_REQUEST -> addRequestNote(restaurant, value.valueToken)
                else ->
                    if (slot.priced) {
                        chooseLine(restaurant, slot, value.valueToken)
                    } else {
                        remember(
                            restaurant = restaurant,
                            slot = slot,
                            value = value.valueToken,
                            stable = value.scope == ValueScope.REMEMBER_FOR_REUSE,
                        )
                    }
            }
            applied += value
        }

        utterance.intents.forEach { intent ->
            when (intent) {
                Intent.REVOKE_AUTO_APPLY ->
                    utterance.referencedSlots.forEach { revokeAutoApply(catalog.slot(it)) }

                Intent.DELETE_NOTE -> deleteRequestNote()
                Intent.RECOMMEND, Intent.CONFIRM_ORDER, Intent.SHOW_MEMORY -> Unit
            }
        }

        if (utterance.unrecognised.isNotEmpty() && applied.isEmpty() && utterance.intents.isEmpty()) {
            questions += "'" + utterance.unrecognised.joinToString(" ") +
                "'는 이해하지 못했습니다. 다시 말씀해 주시겠어요?"
        }

        val wantsDecision = Intent.RECOMMEND in utterance.intents ||
            Intent.CONFIRM_ORDER in utterance.intents
        val decision = if (wantsDecision) requestDecision(restaurant) else null

        return ChatTurn(
            utterance = utterance,
            applied = applied,
            questions = questions,
            recommendations = if (Intent.RECOMMEND in utterance.intents) {
                Recommender(catalog).candidates(state(), restaurant)
            } else {
                emptyList()
            },
            decision = decision,
        )
    }

    fun state(): ProductionState = core.state()

    fun startNewOrder(restaurant: RestaurantDefinition): StepOutcome {
        val outcome = step(
            operation = ProductionCore.Op.RESET_AND_START,
            roles = roles(Role.PRIMARY_GOAL to GOAL_VALID_DRAFT),
            newSession = true,
        )
        declareSchema(restaurant)
        return outcome
    }

    fun nextOrderSession(restaurant: RestaurantDefinition): StepOutcome {
        val outcome = step(
            operation = ProductionCore.Op.ADVANCE_SESSION,
            roles = roles(
                Role.PRIMARY_GOAL to GOAL_VALID_DRAFT,
                Role.TARGET_ENTITY to restaurant.entityToken,
            ),
            newSession = true,
        )
        declareSchema(restaurant)
        return outcome
    }

    /**
     * Tells the engine what this restaurant's menu needs.
     *
     * All three inputs are catalog data: which slots need a value, which of them
     * the user must choose for every order, and which values the catalog currently
     * cannot fulfil.
     */
    private fun declareSchema(restaurant: RestaurantDefinition) {
        core.declareOptionSchema(
            slotIds = catalog.slotsOf(restaurant).filter { it.required }.map { it.slotId },
            // A priced line is the order itself, so it is never auto-applied.
            neverAutoApplySlotIds = catalog.slotsOf(restaurant)
                .filter { it.priced }
                .map { it.slotId },
            unusableValues = catalog.unusableValueTokens(),
        )
    }

    /** Stores a value for one option slot with an explicit reuse scope. */
    fun remember(
        restaurant: RestaurantDefinition,
        slot: SlotDefinition,
        value: String,
        stable: Boolean,
    ): StepOutcome {
        requireOffered(restaurant, slot, value)
        return step(
            operation = ProductionCore.Op.UPSERT_FACT,
            roles = roles(
                Role.PRIMARY_GOAL to GOAL_VALID_DRAFT,
                Role.TARGET_ENTITY to restaurant.entityToken,
                Role.CURRENT_AUTHORITY to nextAuthorityToken(),
                // The scope names the order field this value competes for, and grants
                // reuse of that field beyond this restaurant. Whether a field may be
                // filled without asking is declared separately by the option schema.
                Role.PRESERVED_SCOPE to slot.scopeToken,
                *(
                    if (stable) {
                        arrayOf(Role.STABLE_VALUE to value)
                    } else {
                        arrayOf(Role.ONE_OFF_VALUE to value)
                    }
                    ),
            ),
        )
    }

    /**
     * Picks a line of this restaurant's menu for the current order.
     *
     * A priced line is never filled automatically, so this is the user choosing, and
     * the choice is recorded as theirs.
     */
    fun chooseLine(
        restaurant: RestaurantDefinition,
        slot: SlotDefinition,
        value: String,
    ): StepOutcome {
        val outcome = remember(restaurant, slot, value, stable = false)
        core.confirmUserChoice(listOf(slot.slotId))
        return outcome
    }

    /** Adds a deletable one-time request note to the current draft. */
    fun addRequestNote(restaurant: RestaurantDefinition, value: String): StepOutcome = step(
        operation = ProductionCore.Op.UPSERT_FACT,
        roles = roles(
            Role.TARGET_ENTITY to restaurant.entityToken,
            Role.PRESERVED_SCOPE to Slots.NOTE,
            Role.CURRENT_AUTHORITY to nextAuthorityToken(),
            Role.EPHEMERAL_VALUE to value,
        ),
    )

    fun deleteRequestNote(): StepOutcome = step(
        operation = ProductionCore.Op.DELETE_FACT,
        roles = roles(Role.PRESERVED_SCOPE to Slots.NOTE),
    )

    /** What one review produced, before the user has decided anything. */
    data class ReviewTurn(
        val reviewId: String,
        val reading: ReviewReading,
        /** Remarks that could become memory, each still needing a yes or no. */
        val offers: List<ReviewCandidate>,
        val notes: List<String>,
    )

    /**
     * Leaves a review of the order that was just recorded.
     *
     * The review is stored, and what it said about an option is offered back as a
     * question. Nothing becomes memory here: a remark about one order is not
     * permission to change later ones, and over-generalising it is exactly the
     * failure this product exists to avoid.
     */
    fun submitReview(
        restaurant: RestaurantDefinition,
        ratingToken: String?,
        text: String,
    ): ReviewTurn {
        val reading = intake.readReview(text, ratingToken)
        val current = state()
        val reviewId = core.recordReview(
            ratingToken = reading.ratingToken,
            entityId = restaurant.entityToken,
            // The line the reviewed order contained. A rating is about that line at
            // that restaurant and nowhere else.
            lineValueToken = current.fields[catalog.slot(Slots.MAIN).fieldId]?.value,
            // The order this review is about, so the record is traceable to it.
            actionId = current.actions.values.maxByOrNull { it.seq }?.actionId,
            text = text,
        )

        val notes = mutableListOf<String>()
        reading.observations.forEach { notes += "'$it' 로 이해했습니다." }
        if (reading.unrecognised.isNotEmpty()) {
            notes += "'" + reading.unrecognised.joinToString(" ") + "' 부분은 이해하지 못했습니다."
        }
        val offers = reading.candidates.filter { candidate ->
            // Only offer to remember something this app can actually act on later.
            catalog.slots.any { it.scopeToken == candidate.slotToken }
        }
        if (offers.isEmpty() && reading.candidates.isNotEmpty()) {
            notes += "다음 주문에 반영할 항목을 찾지 못했습니다."
        }
        return ReviewTurn(reviewId, reading, offers, notes)
    }

    /**
     * Accepts one thing a review said, as a result that arrives later.
     *
     * It is stored as a delayed outcome, so it changes the ranking and the proposed
     * option in later orders but is never applied without asking.
     */
    fun rememberFromReview(reviewId: String, candidate: ReviewCandidate): StepOutcome {
        val outcome = scheduleOutcome(catalog.slot(candidate.slotToken), candidate.impliesValue)
        core.linkReviewMemory(reviewId, candidate.impliesValue)
        return outcome
    }

    /** Declines one thing a review said. The review stays; no memory is created. */
    fun dismissFromReview(candidate: ReviewCandidate) {
        // Nothing to do: not remembering is the default, so this records no state.
    }

    fun deleteReview(reviewId: String) = core.deleteReview(reviewId)

    fun reviews(): List<com.scpc.deliveryagent.core.ReviewRecord> = state().reviews.toList()

    /** Ratings a person can pick, in the order the catalog lists them. */
    fun ratingOptions(): List<RatingValue> = catalog.ratingValues

    /** Records a satisfaction result that will arrive after virtual time. */
    fun scheduleOutcome(slot: SlotDefinition, value: String): StepOutcome = step(
        operation = ProductionCore.Op.UPSERT_FACT,
        roles = roles(
            Role.PRESERVED_SCOPE to slot.scopeToken,
            Role.CURRENT_AUTHORITY to nextAuthorityToken(),
            Role.DELAYED_OUTCOME to value,
        ),
    )

    fun advanceTime(): StepOutcome = step(operation = ProductionCore.Op.ADVANCE_TIME, roles = roles())

    fun correct(slot: SlotDefinition, value: String): StepOutcome = step(
        operation = ProductionCore.Op.CORRECT_FACT,
        roles = roles(
            Role.PRESERVED_SCOPE to slot.scopeToken,
            Role.CURRENT_AUTHORITY to nextAuthorityToken(),
            Role.STABLE_VALUE to value,
        ),
    )

    fun revokeAutoApply(slot: SlotDefinition): StepOutcome = step(
        operation = ProductionCore.Op.REVOKE_SCOPE,
        roles = roles(Role.REVOKED_SCOPE to slot.scopeToken),
    )

    /**
     * Delivers one of the catalog changes the synthetic catalog declares, such as a
     * side going out of stock. The change is data, so it arrives as an ordinary
     * update at a higher authority on exactly one slot; nothing here reaches into
     * the draft directly.
     */
    fun applyCatalogEvent(eventToken: String): StepOutcome {
        val change = catalog.event(eventToken)
        return step(
            operation = ProductionCore.Op.UPSERT_FACT,
            roles = roles(
                Role.TARGET_ENTITY to change.entityToken,
                Role.PRESERVED_SCOPE to change.scopeToken,
                Role.CURRENT_AUTHORITY to nextAuthorityToken(),
                // A stock or price change is a fact about this order, not a stored
                // preference, so it enters at the same precedence as the choice the
                // user made for this order and wins on being the more current
                // authority. A preference the user asked to keep is untouched.
                Role.ONE_OFF_VALUE to change.valueToken,
            ),
        )
    }

    /**
     * Refuses a value the current synthetic menu does not offer.
     *
     * Applying an option that is not in the current catalog is one of the validity
     * guardrails, so it is blocked at the point of entry rather than explained
     * afterwards.
     */
    private fun requireOffered(
        restaurant: RestaurantDefinition,
        slot: SlotDefinition,
        value: String,
    ) {
        // A menu option only exists where the restaurant offers it. A condition the
        // user states for this order, or a one-time request, belongs to the order
        // rather than to the menu.
        if (slot.kind == SlotKind.MENU_OPTION) {
            require(restaurant.slotTokens.contains(slot.scopeToken)) {
                "${restaurant.name} has no ${slot.label} option"
            }
        }
        val offered = catalog.valuesFor(restaurant, slot.scopeToken)
        require(offered.any { it.token == value }) {
            "${restaurant.name} does not offer '$value' for ${slot.label}"
        }
    }

    fun setNetwork(state: String): StepOutcome = step(
        operation = ProductionCore.Op.SET_NETWORK,
        roles = roles("NETWORK_STATE" to state),
    )

    fun requestDecision(restaurant: RestaurantDefinition): StepOutcome {
        // Budget and time are amounts, so they are evaluated here and handed to the
        // engine as a plain satisfied or not.
        val pricing = DraftPricing(catalog)
        val state = state()
        val total = pricing.total(state)
        val budget = pricing.budgetLimit(state)
        val estimate = pricing.estimateMinutes(state)
        val etaLimit = pricing.etaLimitMinutes(state)
        val reason = when {
            budget != null && total > budget -> "총액 ${total}이 오늘 예산 ${budget}을 넘습니다"
            etaLimit != null && estimate != null && estimate > etaLimit ->
                "예상시간 ${estimate}분이 희망 ${etaLimit}분을 넘습니다"
            else -> null
        }
        core.declareDraftConstraint(satisfied = reason == null, reason = reason.orEmpty())
        return step(
            operation = ProductionCore.Op.REQUEST_DECISION,
            roles = roles(
                Role.PRIMARY_GOAL to GOAL_VALID_DRAFT,
                Role.TARGET_ENTITY to restaurant.entityToken,
            ),
        )
    }

    fun exportAndEnd(): StepOutcome =
        step(operation = ProductionCore.Op.EXPORT_AND_END, roles = roles())

    fun resetEverything() = core.resetEverything()

    // --------------------------------------------------------------- plumbing

    private fun roles(vararg pairs: Pair<String, String>): JSONObject =
        JSONObject().also { out -> pairs.forEach { (key, value) -> out.put(key, value) } }

    private fun nextAuthorityToken(): String = "authority.v${state().authorityCounter + 1}"

    /**
     * Builds one step. Event ids and the virtual clock advance monotonically from
     * persisted state, so the product surface stays deterministic and replayable.
     */
    private fun step(
        operation: String,
        roles: JSONObject,
        newSession: Boolean = false,
    ): StepOutcome {
        val current = state()
        val ordinal = current.runStepCount + 1
        val sessionOrdinal = if (newSession) current.sessionSeq + 1 else maxOf(current.sessionSeq, 1)
        return core.execute(
            ProbeStep(
                index = ordinal.toInt(),
                stepId = "PRODUCT-%03d".format(ordinal),
                eventId = "EVENT-PRODUCT-$operation-$ordinal",
                operation = operation,
                sessionId = "ORDER-$sessionOrdinal",
                virtualTime = virtualTime(ordinal),
                roles = roles,
            ),
        )
    }

    private fun virtualTime(ordinal: Long): String {
        // Synthetic clock: no real waiting is needed to reach a late outcome.
        val totalMinutes = ordinal * 5
        val day = 1 + (totalMinutes / (60 * 24)).toInt()
        val minuteOfDay = (totalMinutes % (60 * 24)).toInt()
        return "2026-01-%02dT%02d:%02d:00Z".format(day, minuteOfDay / 60, minuteOfDay % 60)
    }

    companion object {
        const val GOAL_VALID_DRAFT = "goal.valid_order_draft"
    }
}
