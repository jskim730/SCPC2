package com.scpc.deliveryagent.delivery

import com.scpc.deliveryagent.core.AsprEngine
import com.scpc.deliveryagent.core.DepKind
import com.scpc.deliveryagent.core.DraftConfiguration
import com.scpc.deliveryagent.core.DraftConstraint
import com.scpc.deliveryagent.core.DraftField
import com.scpc.deliveryagent.core.DraftLineDecl
import com.scpc.deliveryagent.core.FactKind
import com.scpc.deliveryagent.core.FieldStatus
import com.scpc.deliveryagent.core.LineSlotBinding
import com.scpc.deliveryagent.core.PostCommitOutcome
import com.scpc.deliveryagent.core.ProbeStep
import com.scpc.deliveryagent.core.ProductionCore
import com.scpc.deliveryagent.core.ProductionState
import com.scpc.deliveryagent.core.Relevance
import com.scpc.deliveryagent.core.Role
import com.scpc.deliveryagent.core.RoleExt
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
        provenance == "CATALOG_CANNOT_FULFIL_ASK_AGAIN" -> "지금 이 식당에서 고를 수 없음 — 다시 확인"
        provenance == "AUTO_APPLY_REVOKED_ASK_AGAIN" -> "자동 적용 권한 철회 — 다시 확인"
        // Every provenance the engine produces has a sentence above. Reaching
        // here would put an internal constant on the screen, so it says nothing
        // rather than something meaningless.
        else -> "확인 필요"
    }
}

fun FieldStatus.userLabel(): String = when (this) {
    FieldStatus.AUTO_APPLIED -> "자동 적용"
    FieldStatus.NEEDS_CONFIRMATION -> "확인 필요"
    FieldStatus.CONFIRMED -> "확인 완료"
    FieldStatus.INVALIDATED -> "변경됨 — 다시 확인"
}

/**
 * Status and origin of one row, without saying the same thing twice.
 *
 * A confirmed field's provenance is that the user confirmed it, so repeating it
 * reads as a stutter on screen.
 */
fun DraftField.statusLine(state: ProductionState): String {
    val status = status.userLabel()
    val origin = provenanceLabel(state)
    return if (origin == status) status else "$status · $origin"
}

/**
 * What a draft row shows as its value.
 *
 * The order total is a roll-up whose stored value is a digest of the priced
 * lines — useful to the engine, meaningless to a person — so the row shows the
 * amount instead.
 */
fun DraftField.displayValue(
    catalog: SyntheticCatalog,
    pricing: DraftPricing,
    state: ProductionState,
): String = when {
    slotId != AsprEngine.SLOT_TOTAL -> catalog.valueLabel(value)
    value == null -> "다시 계산 필요"
    else -> pricing.formatAmount(pricing.total(state))
}

/**
 * Korean particles, chosen by whether the preceding syllable ends in a consonant.
 *
 * Sentences are assembled from catalog labels, so the particle cannot be written
 * into the template: "맵기를"과 "밥 양을" both come from the same line of code.
 */
object Particles {

    /** 을 / 를 */
    fun obj(word: String): String = if (endsWithConsonant(word)) "을" else "를"

    /** 이 / 가 */
    fun subject(word: String): String = if (endsWithConsonant(word)) "이" else "가"

    /** 으로 / 로 — ㄹ takes the short form, like a vowel ending. */
    fun into(word: String): String = when (finalConsonant(word)) {
        null, RIEUL -> "로"
        else -> "으로"
    }

    fun withObj(word: String): String = word + obj(word)

    fun withInto(word: String): String = word + into(word)

    private const val RIEUL = 8

    private fun endsWithConsonant(word: String): Boolean = finalConsonant(word) != null

    /**
     * Index of the trailing consonant of the last Hangul syllable, or null when
     * there is none. A label ending in something else — a digit or a latin
     * letter — is treated as vowel-final, which is what reads naturally for the
     * amounts and counts this app shows.
     */
    private fun finalConsonant(word: String): Int? {
        val last = word.trimEnd().lastOrNull() ?: return null
        if (last !in HANGUL_FIRST..HANGUL_LAST) return null
        val index = (last - HANGUL_FIRST) % 28
        return if (index == 0) null else index
    }

    private const val HANGUL_FIRST = '가'
    private const val HANGUL_LAST = '힣'
}

/**
 * Token scheme for line-scoped slots.
 *
 * The delivery layer mints these tokens and is the only layer that reads them
 * back. The core treats them as opaque slot identity like every other token, so
 * nothing below this layer branches on their spelling.
 */
object LineTokens {
    fun menu(lineId: String): String = "line.$lineId.${Slots.MAIN}"
    fun quantity(lineId: String): String = "line.$lineId.${Slots.QUANTITY}"
    fun option(lineId: String, baseScopeToken: String): String = "line.$lineId.$baseScopeToken"

    fun menuSlotId(lineId: String): String = Ids.state("slot", menu(lineId))
    fun quantitySlotId(lineId: String): String = Ids.state("slot", quantity(lineId))
    fun optionSlotId(lineId: String, baseScopeToken: String): String =
        Ids.state("slot", option(lineId, baseScopeToken))

    fun menuFieldId(lineId: String): String = AsprEngine.fieldIdFor(menuSlotId(lineId))
    fun optionFieldId(lineId: String, baseScopeToken: String): String =
        AsprEngine.fieldIdFor(optionSlotId(lineId, baseScopeToken))

    data class Parsed(val lineId: String, val baseScopeToken: String)

    /** Reads a line-scoped slot id back, or null when it is not line-scoped. */
    fun parseSlotId(slotId: String): Parsed? {
        val token = slotId.removePrefix("slot.")
        if (!token.startsWith("line.")) return null
        val rest = token.removePrefix("line.")
        val lineId = rest.substringBefore('.')
        val base = rest.substringAfter('.', "")
        if (lineId.isEmpty() || base.isEmpty()) return null
        return Parsed(lineId, base)
    }
}

/** The three reuse scopes an explicit stable preference can be saved at. */
enum class PreferenceScopeLevel { GLOBAL_DEFAULT, MENU_TYPE, RESTAURANT_MENU_OVERRIDE }

/**
 * Token scheme for the three preference scopes.
 *
 * Each scope level of one option slot gets its own token, so it keeps its own
 * fact, tombstone and auto-apply permission; saving or withdrawing one level
 * never touches another. The delivery layer mints and reads these tokens; the
 * core only matches the structural scope fields stored alongside them.
 */
object PreferenceScopes {
    /** "모든 메뉴" — the base slot token itself, as stored since the first release. */
    fun global(baseScopeToken: String): String = baseScopeToken

    /** "어느 식당이든 이 메뉴 유형" — bound to a catalog-authored type token. */
    fun menuType(baseScopeToken: String, menuTypeToken: String): String =
        "$baseScopeToken.type.$menuTypeToken"

    /** "이 식당의 이 메뉴만" — bound to one exact restaurant and menu. */
    fun menuOverride(baseScopeToken: String, restaurantToken: String, menuToken: String): String =
        "$baseScopeToken.at.$restaurantToken.$menuToken"
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
            when {
                slot.kind == SlotKind.USER_REQUEST -> addRequestNote(restaurant, value.valueToken)

                // A menu is an order line the user is adding, never a preference.
                value.scopeToken == Slots.MAIN -> addLine(restaurant, value.valueToken)

                // A quantity belongs to one line. With several lines the target is
                // ambiguous, and an ambiguous instruction is asked about, not spread.
                value.scopeToken == Slots.QUANTITY -> {
                    val only = state().draftLines.singleOrNull()
                    if (only == null) {
                        questions += "수량을 바꿀 메뉴를 먼저 정해 주세요."
                        return@forEach
                    }
                    setQuantity(restaurant, only.lineId, value.valueToken)
                }

                else -> {
                    // An option named without a target applies to the lines that
                    // carry it. When more than one line does, the target is asked.
                    val drafted = state().draftLines
                    val bound = drafted.count { decl ->
                        decl.slots.any { it.baseSlotId == slot.slotId }
                    }
                    if (bound > 1) {
                        questions += "${Particles.withObj(slot.label)} 바꿀 메뉴를 정해 주세요. " +
                            "지금은 여러 항목에 해당합니다."
                        return@forEach
                    }
                    // With dishes already chosen and none of them offering this
                    // option, the instruction has nothing to land on. Filing it
                    // against the order instead would lose it — and, for a paid
                    // extra, lose its price too — so the app says so.
                    val orderLevel = slot.scopeToken in restaurant.orderLevelSlots
                    if (drafted.isNotEmpty() && bound == 0 && !orderLevel &&
                        slot.kind == SlotKind.MENU_OPTION
                    ) {
                        questions += "지금 주문한 메뉴에는 ${slot.label} 선택이 없습니다. " +
                            "${slot.label} 선택이 있는 메뉴를 담을까요?"
                        return@forEach
                    }
                    remember(
                        restaurant = restaurant,
                        slot = slot,
                        value = value.valueToken,
                        // A budget, a wanted time or a kind of food is what the user
                        // wants *this* time. Keeping one as a standing preference
                        // would silently apply an old budget to a later order, so a
                        // stated condition never becomes stable however the sentence
                        // was scoped.
                        stable = value.scope == ValueScope.REMEMBER_FOR_REUSE &&
                            slot.kind != SlotKind.USER_CONDITION,
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

        // Stating what you want is itself a request for candidates while no menu
        // has been chosen: the declared product answers conditions with
        // restaurant and menu candidates, and demanding the word "추천" first
        // left "2만원 이하로 따뜻한 국물" - the app's own example - with nothing
        // to show. Once a menu is in the draft the conversation is about that
        // order, so candidates only return when they are asked for by name.
        val wantsCandidates = Intent.RECOMMEND in utterance.intents ||
            (state().draftLines.isEmpty() && applied.isNotEmpty())

        return ChatTurn(
            utterance = utterance,
            applied = applied,
            questions = questions,
            recommendations = if (wantsCandidates) {
                Recommender(catalog).candidates(state(), restaurant)
            } else {
                emptyList()
            },
            decision = decision,
        )
    }

    fun state(): ProductionState = core.state()

    fun startNewOrder(restaurant: RestaurantDefinition): StepOutcome {
        return step(
            operation = ProductionCore.Op.RESET_AND_START,
            roles = roles(
                Role.PRIMARY_GOAL to GOAL_VALID_DRAFT,
                Role.TARGET_ENTITY to restaurant.entityToken,
            ),
            newSession = true,
            draftConfiguration = draftConfiguration(restaurant, emptyList()),
        )
    }

    fun nextOrderSession(restaurant: RestaurantDefinition): StepOutcome {
        return step(
            operation = ProductionCore.Op.ADVANCE_SESSION,
            roles = roles(
                Role.PRIMARY_GOAL to GOAL_VALID_DRAFT,
                Role.TARGET_ENTITY to restaurant.entityToken,
            ),
            newSession = true,
            draftConfiguration = draftConfiguration(restaurant, emptyList()),
        )
    }

    /** Order lines of the current draft, oldest first. */
    fun lines(): List<DraftLineDecl> = state().draftLines.toList()

    /**
     * Declares the order lines of the current draft and the option schema they
     * need.
     *
     * Everything here is catalog data about the draft the user built: which lines
     * exist, which slots each line carries, which of them need a value, which the
     * user must choose for every order, and which values the catalog currently
     * cannot fulfil.
     */
    private fun draftConfiguration(
        restaurant: RestaurantDefinition,
        lines: List<DraftLineDecl>,
    ): DraftConfiguration {
        val required = mutableListOf<String>()
        val neverAuto = mutableListOf<String>()
        lines.forEach { line ->
            required += LineTokens.menuSlotId(line.lineId)
            // A priced line and its quantity are the order itself, so they are
            // never filled automatically.
            neverAuto += LineTokens.menuSlotId(line.lineId)
            neverAuto += LineTokens.quantitySlotId(line.lineId)
            lineOptionSlots(restaurant, line.menuValueToken).forEach { slot ->
                if (slot.required) {
                    required += LineTokens.optionSlotId(line.lineId, slot.scopeToken)
                }
            }
        }
        restaurant.orderLevelSlots.map(catalog::slot).filter { it.required }.forEach { slot ->
            required += slot.slotId
        }
        return DraftConfiguration(
            lines = lines,
            requiredSlotIds = required.toSet(),
            neverAutoApplySlotIds = neverAuto.toSet(),
            // Out of stock everywhere, plus anything this kitchen simply does not
            // do. A preference for a heat level this restaurant does not offer is
            // not quietly rounded to the nearest one; that field is asked again.
            unusableValues = (catalog.unusableValueTokens() +
                catalog.valuesNotOfferedBy(restaurant)).toSet(),
        )
    }

    /** Option slots that attach to a line of this menu at this restaurant. */
    private fun lineOptionSlots(
        restaurant: RestaurantDefinition,
        menuToken: String?,
    ): List<SlotDefinition> {
        val menu = catalog.value(menuToken ?: return emptyList()) ?: return emptyList()
        return menu.lineOptionSlots.filter { it in restaurant.slotTokens }.map(catalog::slot)
    }

    private fun lineDecl(
        restaurant: RestaurantDefinition,
        lineId: String,
        menuToken: String,
    ): DraftLineDecl {
        val menu = catalog.value(menuToken) ?: error("unknown menu $menuToken")
        val bindings = mutableListOf(
            LineSlotBinding(LineTokens.menuSlotId(lineId), null),
            LineSlotBinding(LineTokens.quantitySlotId(lineId), null),
        )
        lineOptionSlots(restaurant, menuToken).forEach { slot ->
            bindings += LineSlotBinding(
                LineTokens.optionSlotId(lineId, slot.scopeToken),
                slot.slotId,
            )
        }
        return DraftLineDecl(
            lineId = lineId,
            menuValueToken = menuToken,
            menuTypeToken = menu.menuType,
            slots = bindings,
        )
    }

    /** Line ids count up and are never reused inside one draft, even after removal. */
    private fun nextLineId(state: ProductionState): String {
        val fromRegistry = state.lineSlotRegistry.mapNotNull { LineTokens.parseSlotId(it)?.lineId }
        val used = (state.draftLines.map { it.lineId } + fromRegistry)
            .mapNotNull { it.removePrefix("l").toIntOrNull() }
            .maxOrNull() ?: 0
        return "l${used + 1}"
    }

    /**
     * Adds one order line for a menu of this restaurant.
     *
     * A priced line is never filled automatically, so this is the user choosing,
     * and the choice is recorded as theirs.
     */
    fun addLine(restaurant: RestaurantDefinition, menuToken: String): StepOutcome {
        requireOffered(restaurant, catalog.slot(Slots.MAIN), menuToken)
        val state = state()
        val lineId = nextLineId(state)
        val configuration = draftConfiguration(
            restaurant,
            state.draftLines + lineDecl(restaurant, lineId, menuToken),
        )
        return recordMenuChoice(restaurant, lineId, menuToken, configuration)
    }

    /** Replaces the menu of one existing line. The other lines are untouched. */
    fun chooseLineMenu(
        restaurant: RestaurantDefinition,
        lineId: String,
        menuToken: String,
    ): StepOutcome {
        requireOffered(restaurant, catalog.slot(Slots.MAIN), menuToken)
        val current = state().draftLines
        require(current.any { it.lineId == lineId }) { "no order line $lineId" }
        val configuration = draftConfiguration(
            restaurant,
            current.map { if (it.lineId == lineId) lineDecl(restaurant, lineId, menuToken) else it },
        )
        return recordMenuChoice(restaurant, lineId, menuToken, configuration)
    }

    private fun recordMenuChoice(
        restaurant: RestaurantDefinition,
        lineId: String,
        menuToken: String,
        configuration: DraftConfiguration,
    ): StepOutcome {
        val outcome = step(
            operation = ProductionCore.Op.UPSERT_FACT,
            roles = roles(
                Role.PRIMARY_GOAL to GOAL_VALID_DRAFT,
                Role.TARGET_ENTITY to restaurant.entityToken,
                Role.CURRENT_AUTHORITY to nextAuthorityToken(),
                Role.PRESERVED_SCOPE to LineTokens.menu(lineId),
                Role.ONE_OFF_VALUE to menuToken,
            ),
            userConfirmedSlotIds = listOf(LineTokens.menuSlotId(lineId)),
            draftConfiguration = configuration,
        )
        return outcome
    }

    /**
     * Removes one order line.
     *
     * The line's own values are deleted with tombstones, so a replay or restart
     * cannot bring them back, and the other lines and the stored preferences are
     * untouched.
     */
    fun removeLine(restaurant: RestaurantDefinition, lineId: String): StepOutcome? {
        val state = state()
        val line = state.draftLines.firstOrNull { it.lineId == lineId } ?: return null
        val configuration = draftConfiguration(
            restaurant,
            state.draftLines.filter { it.lineId != lineId },
        )
        val storedBindings = line.slots.filter { binding ->
            state.facts.values.any { it.slotId == binding.lineSlotId }
        }
        if (storedBindings.isEmpty()) {
            return step(
                operation = ProductionCore.Op.DELETE_FACT,
                roles = roles(Role.PRESERVED_SCOPE to LineTokens.menu(lineId)),
                draftConfiguration = configuration,
            )
        }
        var last: StepOutcome? = null
        storedBindings.forEachIndexed { index, binding ->
            last = step(
                operation = ProductionCore.Op.DELETE_FACT,
                roles = roles(
                    Role.PRESERVED_SCOPE to binding.lineSlotId.removePrefix("slot."),
                ),
                draftConfiguration = configuration.takeIf { index == storedBindings.lastIndex },
            )
        }
        return last
    }

    /** Sets one option of one line for this order only. Stored memory is untouched. */
    fun setLineOption(
        restaurant: RestaurantDefinition,
        lineId: String,
        slot: SlotDefinition,
        value: String,
    ): StepOutcome {
        requireOffered(restaurant, slot, value)
        val line = state().draftLines.firstOrNull { it.lineId == lineId }
        require(line != null) { "no order line $lineId" }
        // Addressing a line with an option that line does not carry — an
        // order-level slot such as the utensil, say — would put a second, orphan
        // row for it on the draft. The slot belongs to one address or the other.
        require(line.slots.any { it.baseSlotId == slot.slotId }) {
            "order line $lineId does not carry ${slot.label}"
        }
        return step(
            operation = ProductionCore.Op.UPSERT_FACT,
            roles = roles(
                Role.TARGET_ENTITY to restaurant.entityToken,
                Role.CURRENT_AUTHORITY to nextAuthorityToken(),
                Role.PRESERVED_SCOPE to LineTokens.option(lineId, slot.scopeToken),
                Role.ONE_OFF_VALUE to value,
            ),
        )
    }

    /** Sets how many of one line's menu to order. */
    fun setQuantity(
        restaurant: RestaurantDefinition,
        lineId: String,
        quantityToken: String,
    ): StepOutcome {
        requireOffered(restaurant, catalog.slot(Slots.QUANTITY), quantityToken)
        require(state().draftLines.any { it.lineId == lineId }) { "no order line $lineId" }
        val outcome = step(
            operation = ProductionCore.Op.UPSERT_FACT,
            roles = roles(
                Role.TARGET_ENTITY to restaurant.entityToken,
                Role.CURRENT_AUTHORITY to nextAuthorityToken(),
                Role.PRESERVED_SCOPE to LineTokens.quantity(lineId),
                Role.ONE_OFF_VALUE to quantityToken,
            ),
            userConfirmedSlotIds = listOf(LineTokens.quantitySlotId(lineId)),
        )
        return outcome
    }

    /** Stores a value for one option slot with an explicit reuse scope. */
    fun remember(
        restaurant: RestaurantDefinition,
        slot: SlotDefinition,
        value: String,
        stable: Boolean,
        sourceReviewId: String? = null,
    ): StepOutcome {
        requireOffered(restaurant, slot, value)
        val extraRoles = sourceReviewId
            ?.let { arrayOf(RoleExt.SOURCE_REVIEW_ID to it) }
            .orEmpty()
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
                *extraRoles,
            ),
        )
    }

    /**
     * Stores a stable preference for one authored menu type: "이 메뉴 유형이면
     * 어느 식당이든".
     *
     * Only a slot the catalog author declared stable for that type may cross
     * restaurants this way; anything else must be saved per menu or globally. The
     * value still only applies where the current restaurant offers the slot, and
     * the token scheme keeps each scope level its own fact, tombstone and
     * permission.
     */
    fun rememberForMenuType(
        restaurant: RestaurantDefinition,
        slot: SlotDefinition,
        value: String,
        menuTypeToken: String,
        sourceReviewId: String? = null,
    ): StepOutcome {
        requireOffered(restaurant, slot, value)
        val menuType = catalog.menuType(menuTypeToken)
        require(slot.scopeToken in menuType.stableOptionSlots) {
            "${menuType.label} 유형은 ${slot.label}을 식당을 넘어 재사용하도록 authored되지 않았다"
        }
        return step(
            operation = ProductionCore.Op.UPSERT_FACT,
            roles = roles(
                Role.PRIMARY_GOAL to GOAL_VALID_DRAFT,
                Role.TARGET_ENTITY to restaurant.entityToken,
                Role.CURRENT_AUTHORITY to nextAuthorityToken(),
                Role.PRESERVED_SCOPE to PreferenceScopes.menuType(slot.scopeToken, menuTypeToken),
                RoleExt.BASE_SCOPE to slot.scopeToken,
                RoleExt.SPECIFICITY to "1",
                RoleExt.MENU_TYPE_ID to menuTypeToken,
                Role.STABLE_VALUE to value,
                *(sourceReviewId
                    ?.let { arrayOf(RoleExt.SOURCE_REVIEW_ID to it) }
                    .orEmpty()),
            ),
        )
    }

    /**
     * Stores a stable exception for one exact menu of one restaurant: "이 식당의
     * 이 메뉴만".
     *
     * It overrides the menu-type and global values on that menu's lines and
     * nowhere else. Deleting or revoking it leaves the wider scopes untouched.
     */
    fun rememberForMenu(
        restaurant: RestaurantDefinition,
        slot: SlotDefinition,
        value: String,
        menuToken: String,
        sourceReviewId: String? = null,
    ): StepOutcome {
        requireOffered(restaurant, slot, value)
        val menu = catalog.value(menuToken)
        require(menu != null && restaurant.menu.any { it.token == menuToken }) {
            "${restaurant.name} has no menu $menuToken"
        }
        require(slot.scopeToken in menu.lineOptionSlots) {
            "${menu.label}에는 ${slot.label} 선택이 없다"
        }
        return step(
            operation = ProductionCore.Op.UPSERT_FACT,
            roles = roles(
                Role.PRIMARY_GOAL to GOAL_VALID_DRAFT,
                Role.TARGET_ENTITY to restaurant.entityToken,
                Role.CURRENT_AUTHORITY to nextAuthorityToken(),
                Role.PRESERVED_SCOPE to
                    PreferenceScopes.menuOverride(slot.scopeToken, restaurant.entityToken, menuToken),
                RoleExt.BASE_SCOPE to slot.scopeToken,
                RoleExt.SPECIFICITY to "2",
                RoleExt.SCOPE_MENU_ID to menuToken,
                Role.STABLE_VALUE to value,
                *(sourceReviewId
                    ?.let { arrayOf(RoleExt.SOURCE_REVIEW_ID to it) }
                    .orEmpty()),
            ),
        )
    }

    /** Withdraws auto-apply for one preference scope token, wider or narrower. */
    fun revokeAutoApplyScope(scopeToken: String): StepOutcome = step(
        operation = ProductionCore.Op.REVOKE_SCOPE,
        roles = roles(Role.REVOKED_SCOPE to scopeToken),
    )

    /** Corrects the stored value behind one preference scope token. */
    fun correctPreference(scopeToken: String, value: String): StepOutcome = step(
        operation = ProductionCore.Op.CORRECT_FACT,
        roles = roles(
            Role.PRESERVED_SCOPE to scopeToken,
            Role.CURRENT_AUTHORITY to nextAuthorityToken(),
            Role.STABLE_VALUE to value,
        ),
    )

    /** Deletes the stored value behind one preference scope token, leaving a marker. */
    fun deletePreference(scopeToken: String): StepOutcome = step(
        operation = ProductionCore.Op.DELETE_FACT,
        roles = roles(Role.PRESERVED_SCOPE to scopeToken),
    )

    /** One stored explicit preference, with the scope it was saved at. */
    data class StoredPreference(
        val factId: String,
        val scopeToken: String,
        val baseSlot: SlotDefinition?,
        val level: PreferenceScopeLevel,
        val menuTypeToken: String?,
        val menuToken: String?,
        val value: String,
        val autoApplyRevoked: Boolean,
    )

    /** The explicit stable preferences the app currently holds, most recent last. */
    fun storedPreferences(): List<StoredPreference> {
        val state = state()
        return state.facts.values
            .filter { it.kind == FactKind.STABLE }
            .sortedBy { it.seq }
            .map { fact ->
                val baseSlotId = fact.baseSlotId ?: fact.slotId
                StoredPreference(
                    factId = fact.factId,
                    scopeToken = fact.scopeIds.firstOrNull()
                        ?.removePrefix("scope.") ?: baseSlotId.removePrefix("slot."),
                    baseSlot = catalog.slotOfFieldSlotId(baseSlotId),
                    level = when (fact.specificity) {
                        2 -> PreferenceScopeLevel.RESTAURANT_MENU_OVERRIDE
                        1 -> PreferenceScopeLevel.MENU_TYPE
                        else -> PreferenceScopeLevel.GLOBAL_DEFAULT
                    },
                    menuTypeToken = fact.menuTypeId,
                    menuToken = fact.scopeMenuId,
                    value = fact.value,
                    autoApplyRevoked = fact.scopeIds.any { it in state.revokedScopes },
                )
            }
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
        /** The menu this review is about, once settled. */
        val targetMenuToken: String?,
        /** True when several menus were ordered and the user must name one first. */
        val needsTarget: Boolean,
        /** Remarks that could become memory, each still needing a scope and a yes. */
        val offers: List<ReviewCandidate>,
        val notes: List<String>,
    )

    /**
     * Leaves a review of the order that was just recorded.
     *
     * The review is stored, and what it said about an option is offered back as a
     * question. Nothing becomes memory here: a remark about one order is not
     * permission to change later ones, and over-generalising it is exactly the
     * failure this product exists to avoid. With several ordered menus the review
     * stays unattributed — no rating evidence, no offers — until the user names
     * the menu it was about.
     */
    fun submitReview(
        restaurant: RestaurantDefinition,
        ratingToken: String?,
        text: String,
        targetMenuToken: String? = null,
    ): ReviewTurn {
        if (targetMenuToken != null) {
            require(restaurant.menu.any { it.token == targetMenuToken }) {
                "${restaurant.name} has no menu $targetMenuToken"
            }
        }
        val reading = intake.readReview(text, ratingToken)
        val current = state()
        val target = targetMenuToken ?: current.draftLines.singleOrNull()?.menuValueToken
        val needsTarget = target == null && current.draftLines.size > 1
        val reviewId = core.recordReview(
            ratingToken = reading.ratingToken,
            entityId = restaurant.entityToken,
            lineValueToken = target,
            // The order this review is about, so the record is traceable to it.
            actionId = current.actions.values.maxByOrNull { it.seq }?.actionId,
            text = text,
        )
        clearEvaluationRequest()

        val notes = mutableListOf<String>()
        reading.observations.forEach { notes += "'$it' 로 이해했습니다." }
        if (reading.unrecognised.isNotEmpty()) {
            notes += "'" + reading.unrecognised.joinToString(" ") + "' 부분은 이해하지 못했습니다."
        }
        if (needsTarget) {
            notes += "여러 메뉴를 주문했습니다. 어느 메뉴에 대한 평가인지 먼저 정해 주세요."
        }
        val offers = if (needsTarget) emptyList() else offersOf(reading)
        if (offers.isEmpty() && reading.candidates.isNotEmpty() && !needsTarget) {
            notes += "다음 주문에 반영할 항목을 찾지 못했습니다."
        }
        return ReviewTurn(reviewId, reading, target, needsTarget, offers, notes)
    }

    private fun offersOf(reading: ReviewReading): List<ReviewCandidate> =
        reading.candidates.filter { candidate ->
            // Only offer to remember something this app can actually act on later.
            catalog.slots.any { it.scopeToken == candidate.slotToken }
        }

    /** Settles which menu an ambiguous review was about and returns its offers. */
    fun setReviewTarget(
        restaurant: RestaurantDefinition,
        reviewId: String,
        menuToken: String,
    ): List<ReviewCandidate> {
        require(restaurant.menu.any { it.token == menuToken }) {
            "${restaurant.name} has no menu $menuToken"
        }
        core.setReviewTarget(reviewId, menuToken)
        val review = state().reviews.firstOrNull { it.reviewId == reviewId }
            ?: return emptyList()
        return offersOf(intake.readReview(review.text, review.ratingToken))
    }

    /** Scope levels one review remark can honestly be saved at for its menu. */
    fun reviewScopeChoices(
        restaurant: RestaurantDefinition,
        candidate: ReviewCandidate,
        menuToken: String?,
    ): List<PreferenceScopeLevel> {
        val slot = catalog.slot(candidate.slotToken)
        val levels = mutableListOf<PreferenceScopeLevel>()
        val menu = menuToken?.let(catalog::value)
        if (menu != null && restaurant.menu.any { it.token == menuToken } &&
            slot.scopeToken in menu.lineOptionSlots
        ) {
            levels += PreferenceScopeLevel.RESTAURANT_MENU_OVERRIDE
            val type = menu.menuType?.let(catalog::menuType)
            if (type != null && slot.scopeToken in type.stableOptionSlots) {
                levels += PreferenceScopeLevel.MENU_TYPE
            }
        }
        levels += PreferenceScopeLevel.GLOBAL_DEFAULT
        return levels
    }

    /**
     * Accepts one thing a review said, at the scope the user chose.
     *
     * Only now does it become an explicit stable preference with auto-apply
     * permission at exactly that scope. The review keeps the id of the fact it
     * created, so deleting the review takes back precisely that and nothing the
     * user stated independently.
     */
    fun acceptFromReview(
        restaurant: RestaurantDefinition,
        reviewId: String,
        candidate: ReviewCandidate,
        level: PreferenceScopeLevel,
    ): StepOutcome {
        val review = state().reviews.firstOrNull { it.reviewId == reviewId }
            ?: error("unknown review $reviewId")
        val slot = catalog.slot(candidate.slotToken)
        val menuToken = review.lineValueToken
        val outcome = when (level) {
            PreferenceScopeLevel.GLOBAL_DEFAULT ->
                remember(
                    restaurant,
                    slot,
                    candidate.impliesValue,
                    stable = true,
                    sourceReviewId = reviewId,
                )

            PreferenceScopeLevel.MENU_TYPE -> {
                requireNotNull(menuToken) { "review target menu is not settled" }
                val type = catalog.value(menuToken)?.menuType
                    ?: error("menu $menuToken has no authored type")
                rememberForMenuType(
                    restaurant,
                    slot,
                    candidate.impliesValue,
                    type,
                    sourceReviewId = reviewId,
                )
            }

            PreferenceScopeLevel.RESTAURANT_MENU_OVERRIDE -> {
                requireNotNull(menuToken) { "review target menu is not settled" }
                rememberForMenu(
                    restaurant,
                    slot,
                    candidate.impliesValue,
                    menuToken,
                    sourceReviewId = reviewId,
                )
            }
        }
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
        // A change to a menu hits the order line that holds it, so only that
        // line and the total re-open. Anything else keeps the change's own scope.
        val line = state().draftLines.firstOrNull { it.menuValueToken == change.replacesValue }
        val scopeToken = if (change.scopeToken == Slots.MAIN && line != null) {
            LineTokens.menu(line.lineId)
        } else {
            change.scopeToken
        }
        return step(
            operation = ProductionCore.Op.UPSERT_FACT,
            roles = roles(
                Role.TARGET_ENTITY to change.entityToken,
                Role.PRESERVED_SCOPE to scopeToken,
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
        // An amount or a duration is whatever the user said, so it is checked for
        // being well formed rather than for being one of the listed presets. Only
        // an enumerated option has a fixed list to be offered from.
        require(catalog.accepts(restaurant, slot.scopeToken, value)) {
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
        return step(
            operation = ProductionCore.Op.REQUEST_DECISION,
            roles = roles(
                Role.PRIMARY_GOAL to GOAL_VALID_DRAFT,
                Role.TARGET_ENTITY to restaurant.entityToken,
            ),
            draftConstraint = DraftConstraint(
                satisfied = reason == null,
                reason = reason.orEmpty(),
            ),
            postCommitOutcome = PostCommitOutcome(
                scopeToken = REVIEW_REQUEST_SCOPE,
                valuePrefix = REVIEW_REQUEST_SCOPE,
                expiresAtVirtual = virtualTime(
                    state.runStepCount + EVALUATION_REQUEST_TTL_STEPS,
                ),
            ),
        )
    }

    /** True when a request to rate the finished order has arrived and stands. */
    fun pendingEvaluationRequest(): Boolean {
        val state = state()
        return state.facts.values.any { fact ->
            fact.kind == FactKind.OUTCOME &&
                fact.value.startsWith("review.request.") &&
                AsprEngine.relevanceOf(state, fact) == Relevance.ACTIVE
        }
    }

    /** Answering the request with a review takes the arrived request down. */
    private fun clearEvaluationRequest() {
        val hasRequest = state().facts.values.any {
            it.kind == FactKind.OUTCOME && it.value.startsWith("review.request.")
        }
        if (hasRequest) {
            step(
                operation = ProductionCore.Op.DELETE_FACT,
                roles = roles(Role.PRESERVED_SCOPE to REVIEW_REQUEST_SCOPE),
            )
        }
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
        userConfirmedSlotIds: Collection<String> = emptySet(),
        draftConfiguration: DraftConfiguration? = null,
        draftConstraint: DraftConstraint? = null,
        postCommitOutcome: PostCommitOutcome? = null,
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
            userConfirmedSlotIds = userConfirmedSlotIds,
            draftConfiguration = draftConfiguration,
            draftConstraint = draftConstraint,
            postCommitOutcome = postCommitOutcome,
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

        /** Scope token of the app-local request to rate a finished order. */
        const val REVIEW_REQUEST_SCOPE = "review.request"

        /**
         * How long a rating request stands, in product steps of the synthetic
         * clock counted from scheduling: one step to schedule, one to arrive,
         * about two to stand. Short by design — the request is a passing notice,
         * not a task, and must never gate the next order.
         */
        const val EVALUATION_REQUEST_TTL_STEPS = 4L
    }
}
