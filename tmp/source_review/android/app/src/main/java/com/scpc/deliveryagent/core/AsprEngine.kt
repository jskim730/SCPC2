package com.scpc.deliveryagent.core

import org.json.JSONArray
import org.json.JSONObject

/** Why a stored fact is or is not usable for the current goal and target. */
enum class Relevance {
    ACTIVE,
    EXPIRED_SESSION,
    EXPIRED_TIME,
    OTHER_ENTITY,
    OTHER_GOAL,
    DISTRACTOR,
    REVOKED_UNCERTAIN,
}

/** Facts chosen for the current decision, and why the rest were left out. */
class Selection(
    val winners: Map<String, Fact>,
    val ranking: Map<String, List<Fact>>,
    val excluded: Map<String, Relevance>,
    val expired: List<Fact>,
) {
    fun selectedFactIds(): List<String> =
        (winners.values.map { it.factId } + ranking.values.flatten().map { it.factId })
            .distinct()
            .sorted()
}

/**
 * Comparable snapshot used to report what one step invalidated or preserved.
 *
 * A field counts as resolved when it holds a valid value the user does not have
 * to supply again. Only the value is recorded, not the status, so committing a
 * draft is not mistaken for invalidating it.
 */
class StateSnapshot(
    val factIds: Set<String>,
    val resolvedFields: Map<String, String>,
    val allFields: Set<String>,
) {
    companion object {
        fun of(state: ProductionState): StateSnapshot = StateSnapshot(
            factIds = state.facts.keys.toSet(),
            resolvedFields = state.fields.values
                .filter { it.status == FieldStatus.AUTO_APPLIED || it.status == FieldStatus.CONFIRMED }
                .mapNotNull { field -> field.value?.let { field.fieldId to it } }
                .toMap(),
            allFields = state.fields.keys.toSet(),
        )
    }
}

/**
 * ASPR — Authority-Scoped Preference Reconciler.
 *
 * Compiles typed memory into the current order draft:
 *
 *  1. drop memory that does not belong to the current goal or target,
 *  2. inside each slot pick the fact with the highest precedence, then the most
 *     current authority,
 *  3. apply a value only when it is explicit, permitted and not revoked,
 *  4. record which fact, permission scope and catalog line every field depends
 *     on, so a later correction, revocation, deletion or stock change can
 *     invalidate exactly the affected descendants and preserve the rest.
 *
 * With [ProductionState.asprEnabled] off, the same code path collapses to the
 * `claim-off` baseline: latest value per slot, no scope or lifetime typing and
 * no dependency edges, so any value not stated in the current session has to be
 * asked again and any change re-opens the whole unconfirmed draft.
 */
object AsprEngine {

    const val SLOT_TOTAL = "slot.total"
    private const val OUTCOME_SUFFIX = "outcome"
    private const val REQUEST_SUFFIX = "request"

    // ---------------------------------------------------------------- slots

    /**
     * Slot identity for a fact addressed by this step.
     *
     * A slot is the order field a value competes for. It is derived from the
     * addressing roles in a fixed order, never from the value itself, so a
     * stable preference and a one-off condition addressed the same way land in
     * the same slot and the one-off can win for the current session only.
     */
    fun slotBase(view: RoleView, state: ProductionState): String {
        val token = view.value(Role.PRESERVED_SCOPE)
            ?: view.value(Role.TARGET_ENTITY)
            ?: view.value(Role.PRIMARY_GOAL)
            ?: state.targetEntityId
            ?: state.goalId
            ?: "global"
        return Ids.state("slot", token)
    }

    fun slotFor(kind: FactKind, view: RoleView, state: ProductionState): String {
        val base = slotBase(view, state)
        return when (kind) {
            FactKind.OUTCOME -> "$base.$OUTCOME_SUFFIX"
            FactKind.EPHEMERAL -> "$base.$REQUEST_SUFFIX"
            else -> base
        }
    }

    fun factIdFor(kind: FactKind, slotId: String): String = Ids.state("fact", kind.name, slotId)

    fun fieldIdFor(slotId: String): String = Ids.state("field", slotId.removePrefix("slot."))

    fun scopeIdFor(token: String): String = Ids.state("scope", token)

    fun catalogDepRef(entityId: String, slotId: String, authorityVersion: Long): String =
        Ids.state("catalog", entityId, slotId) + ".v$authorityVersion"

    // ------------------------------------------------------------ selection

    fun select(state: ProductionState): Selection {
        val candidates = linkedMapOf<String, MutableList<Fact>>()
        val ranking = linkedMapOf<String, MutableList<Fact>>()
        val excluded = linkedMapOf<String, Relevance>()
        val expired = mutableListOf<Fact>()

        state.facts.values.sortedBy { it.factId }.forEach { fact ->
            when (val relevance = relevanceOf(state, fact)) {
                Relevance.ACTIVE ->
                    if (fact.permission == Permission.RANK_ONLY && fact.kind == FactKind.INFERRED) {
                        ranking.getOrPut(fact.slotId) { mutableListOf() }.add(fact)
                    } else {
                        candidates.getOrPut(fact.slotId) { mutableListOf() }.add(fact)
                    }

                Relevance.EXPIRED_SESSION, Relevance.EXPIRED_TIME -> {
                    expired += fact
                    excluded[fact.factId] = relevance
                }

                else -> excluded[fact.factId] = relevance
            }
        }

        val winners = linkedMapOf<String, Fact>()
        candidates.forEach { (slotId, facts) ->
            val ordered = facts.sortedWith(precedence(state))
            winners[slotId] = ordered.first()
            // Facts that lost their slot still contribute ranking provenance.
            ordered.drop(1).forEach { loser ->
                ranking.getOrPut(slotId) { mutableListOf() }.add(loser)
            }
        }
        return Selection(winners, ranking.mapValues { it.value.toList() }, excluded, expired)
    }

    /**
     * Current instruction beats correction, correction beats stored preference,
     * stored preference beats delayed outcome, outcome beats inference. Ties
     * inside one precedence band are broken by the most current authority and
     * then by arrival order.
     */
    private fun precedence(state: ProductionState): Comparator<Fact> =
        if (state.asprEnabled) {
            compareByDescending<Fact> { it.kind.precedence }
                .thenByDescending { it.specificity }
                .thenByDescending { it.authorityVersion }
                .thenByDescending { it.seq }
        } else {
            // Recent-order baseline: no lifetime or permission typing, latest wins.
            compareByDescending<Fact> { it.seq }
        }

    fun relevanceOf(state: ProductionState, fact: Fact): Relevance {
        val expiresAt = fact.expiresAtVirtual
        if (expiresAt != null && state.virtualNow >= expiresAt) {
            return Relevance.EXPIRED_TIME
        }
        if (!state.asprEnabled) {
            // The baseline has no lifetime or scope typing, so it cannot tell a
            // condition that was only for one order from a stored preference, and
            // nothing is excluded here. It stays safe a different way: anything not
            // stated in the current session is asked about rather than applied.
            return Relevance.ACTIVE
        }
        if (fact.lifetime == Lifetime.SESSION && fact.boundSessionId != state.sessionId) {
            return Relevance.EXPIRED_SESSION
        }
        val entityId = fact.entityId
        if (entityId != null) {
            if (entityId in state.distractorEntityIds) return Relevance.DISTRACTOR
            val target = state.targetEntityId
            if (target != null && entityId != target) return Relevance.OTHER_ENTITY
        }
        val goalId = fact.goalId
        if (goalId != null) {
            val currentGoal = state.goalId
            if (currentGoal != null && goalId != currentGoal) return Relevance.OTHER_GOAL
        }
        return Relevance.ACTIVE
    }

    // ----------------------------------------------------------- projection

    /**
     * Rebuilds the draft from the current selection.
     *
     * Fields the user already resolved in this session stay resolved; a change
     * only re-opens fields whose dependencies it actually touched.
     */
    fun project(state: ProductionState, stepId: String) {
        val selection = select(state)

        // Expired session-scoped memory leaves the active set but is not deleted:
        // it stays in the audit ledger, only unusable for the current session.
        state.lastExpiredFactIds.clear()
        selection.expired.forEach { state.lastExpiredFactIds += it.factId }

        val previous = state.fields.toMap()
        val rebuilt = linkedMapOf<String, DraftField>()

        // Slots the declared lines own or distribute. The registry also covers
        // lines removed earlier in this draft, so their leftover facts stay
        // line-addressed. With no declared lines all sets are empty and projection
        // keeps its one-field-per-slot shape, which is the shape every probe-path
        // test exercises.
        val lineSlotIds = state.lineSlotRegistry +
            state.draftLines.flatMap { line -> line.slots.map { it.lineSlotId } }
        val lineBaseSlotIds = state.draftLines
            .flatMap { line -> line.slots.mapNotNull { it.baseSlotId } }
            .toSet()

        selection.winners.toSortedMap().forEach { (slotId, winner) ->
            // A slot a declared line distributes is resolved per line below, so the
            // base preference itself does not become an order-level field.
            if (slotId in lineSlotIds || slotId in lineBaseSlotIds) return@forEach
            // A scoped preference only ever fills the lines its scope matches; it
            // never becomes an order-level field of its own.
            if (winner.baseSlotId != null && winner.baseSlotId != slotId) return@forEach
            // Nor does a preference for something only an order line can carry. No
            // line here distributes it, which means this kitchen does not offer it
            // at all — so it is not a row of this order, in any state.
            if (slotId in state.lineOnlySlots) return@forEach
            rebuilt[fieldIdFor(slotId)] = buildField(
                state = state,
                stepId = stepId,
                previous = previous,
                fieldSlotId = slotId,
                winner = winner,
                rankingLosers = selection.ranking[slotId].orEmpty(),
            )
        }

        // Each declared line resolves its own fields: a value addressed directly at
        // the line wins over an exact restaurant-menu override, which wins over a
        // menu-type preference, which wins over a global default — inside the same
        // authority rules every other value follows.
        state.draftLines.forEach { line ->
            line.slots.forEach lineSlot@{ binding ->
                val ranked = state.facts.values
                    .filter { relevanceOf(state, it) == Relevance.ACTIVE }
                    .mapNotNull { fact ->
                        lineSpecificityOf(state, fact, line, binding)?.let { fact to it }
                    }
                    .sortedWith(linePrecedence(state))
                val winner = ranked.firstOrNull()?.first ?: return@lineSlot
                rebuilt[fieldIdFor(binding.lineSlotId)] = buildField(
                    state = state,
                    stepId = stepId,
                    previous = previous,
                    fieldSlotId = binding.lineSlotId,
                    winner = winner,
                    rankingLosers = ranked.drop(1).map { it.first },
                )
            }
        }

        // Ranking-only memory with no winning value still has to be visible as a
        // proposal the user can accept, so it opens its own confirmation.
        selection.ranking.toSortedMap().forEach { (slotId, facts) ->
            if (slotId in lineSlotIds || slotId in lineBaseSlotIds) return@forEach
            if (slotId in state.lineOnlySlots) return@forEach
            val fieldId = fieldIdFor(slotId)
            if (rebuilt.containsKey(fieldId)) return@forEach
            val proposal = facts.minByOrNull { it.factId } ?: return@forEach
            if (proposal.baseSlotId != null && proposal.baseSlotId != slotId) return@forEach
            rebuilt[fieldId] = DraftField(
                fieldId = fieldId,
                slotId = slotId,
                value = proposal.value,
                status = FieldStatus.NEEDS_CONFIRMATION,
                priced = false,
                deps = listOf(Dep(DepKind.RANKING, proposal.factId)),
                provenance = "RANKING_PROPOSAL",
                factId = proposal.factId,
                updatedSeq = proposal.seq,
            )
        }

        // A slot the current menu requires but no valid, permitted fact fills is a
        // question, never a guess.
        state.requiredSlots.sorted().forEach { slotId ->
            val fieldId = fieldIdFor(slotId)
            if (rebuilt.containsKey(fieldId)) return@forEach
            val prior = previous[fieldId]
            val kept = prior != null && prior.userConfirmed
            rebuilt[fieldId] = DraftField(
                fieldId = fieldId,
                slotId = slotId,
                value = if (kept) prior.value else null,
                status = if (kept) FieldStatus.CONFIRMED else FieldStatus.NEEDS_CONFIRMATION,
                priced = false,
                deps = prior?.deps.orEmpty(),
                provenance = "REQUIRED_OPTION_UNRESOLVED",
                factId = prior?.factId,
                updatedSeq = prior?.updatedSeq ?: 0,
                userConfirmed = kept,
            )
        }

        state.fields.clear()
        state.fields.putAll(rebuilt)
        projectTotal(state)
    }

    /**
     * The order total is a roll-up of priced lines, so it is never auto-applied
     * and is recomputed whenever any line changes.
     */
    private fun projectTotal(state: ProductionState) {
        val priced = state.fields.values.filter { it.priced }.sortedBy { it.fieldId }
        if (priced.isEmpty()) return
        val fieldId = fieldIdFor(SLOT_TOTAL)
        val components = JSONArray().also { array ->
            priced.forEach { array.put(JSONObject().put(it.fieldId, it.value ?: JSONObject.NULL)) }
        }
        val deps = priced.map { Dep(DepKind.TOTAL, it.fieldId) }
        state.fields[fieldId] = DraftField(
            fieldId = fieldId,
            slotId = SLOT_TOTAL,
            value = "total." + Digest.canonical(components).take(16),
            status = FieldStatus.NEEDS_CONFIRMATION,
            priced = false,
            deps = deps,
            provenance = "PRICED_LINE_ROLLUP",
            factId = null,
            updatedSeq = priced.maxOf { it.updatedSeq },
        )
    }

    /**
     * Builds one draft field from the fact that won it. Shared by the base-slot
     * projection and the per-line projection so both follow exactly the same
     * permission, revocation, stock and confirmation rules.
     */
    private fun buildField(
        state: ProductionState,
        stepId: String,
        previous: Map<String, DraftField>,
        fieldSlotId: String,
        winner: Fact,
        rankingLosers: List<Fact>,
    ): DraftField {
        val fieldId = fieldIdFor(fieldSlotId)
        val prior = previous[fieldId]
        val catalogEntity = winner.catalogEntityId
        val revoked = state.asprEnabled &&
            winner.scopeIds.any { it in state.revokedScopes }

        // The current catalog cannot fulfil this value, so it is never treated
        // as settled no matter where it came from.
        val unusable = winner.value in state.unusableValues
        val autoApplicable = when {
            winner.confidence != Confidence.EXPLICIT -> false
            unusable -> false
            // The final menu, its lines and the total are the user's choice for
            // each order, never an automatic application.
            winner.slotId in state.neverAutoApplySlots -> false
            fieldSlotId in state.neverAutoApplySlots -> false
            !state.asprEnabled ->
                // Baseline cannot tell whether stored memory still applies to
                // this target, so it only reuses what this session stated.
                winner.boundSessionId == state.sessionId
            revoked -> false
            else -> winner.permission == Permission.AUTO_APPLY
        }

        val deps = mutableListOf(Dep(DepKind.HARD_VALUE, winner.factId))
        if (state.asprEnabled) {
            winner.scopeIds.sorted().forEach { deps += Dep(DepKind.PERMISSION, it) }
            if (catalogEntity != null) {
                deps += Dep(
                    DepKind.CATALOG,
                    catalogDepRef(catalogEntity, fieldSlotId, winner.authorityVersion),
                )
            }
            rankingLosers.sortedBy { it.factId }.forEach {
                deps += Dep(DepKind.RANKING, it.factId)
            }
        }

        // A new fact event for a slot the agent had asked about is the user's
        // answer to that question.
        val answered = prior != null &&
            (prior.status == FieldStatus.NEEDS_CONFIRMATION || prior.status == FieldStatus.INVALIDATED) &&
            winner.seq > prior.updatedSeq
        // A slot's value always comes from the fact that currently wins it: a
        // more current instruction, a correction or a catalog change is why the
        // user asked for the draft in the first place. What survives is the fact
        // that the user resolved this slot, which is what a revocation or the
        // deletion of some other fact must not undo.
        val userConfirmed = (prior?.userConfirmed == true || answered) && !unusable
        val status = when {
            unusable -> FieldStatus.NEEDS_CONFIRMATION
            userConfirmed -> FieldStatus.CONFIRMED
            autoApplicable -> FieldStatus.AUTO_APPLIED
            else -> FieldStatus.NEEDS_CONFIRMATION
        }
        if (userConfirmed) {
            deps += Dep(DepKind.CONFIRMATION, "session.${Ids.segment(state.sessionId)}")
        }
        if (answered) {
            state.resolutions += Resolution(fieldId, "FIELD_ANSWER", stepId, state.nextSeq())
        }

        return DraftField(
            fieldId = fieldId,
            slotId = fieldSlotId,
            value = winner.value,
            status = status,
            priced = catalogEntity != null,
            deps = deps.distinct(),
            provenance = provenanceOf(winner, revoked, autoApplicable, unusable),
            factId = winner.factId,
            updatedSeq = winner.seq,
            userConfirmed = userConfirmed,
        )
    }

    /**
     * How narrowly a fact applies to one slot of one declared line, or null when
     * it does not apply there at all.
     *
     * A value addressed directly at the line always applies and outranks every
     * scoped preference. A scoped preference applies only when its structural
     * scope matches the line: the exact menu for an override, the authored menu
     * type for a type preference, no qualifier for a global default. The baseline
     * arm has no scope typing, so any value of the base slot matches and the
     * latest wins.
     */
    private fun lineSpecificityOf(
        state: ProductionState,
        fact: Fact,
        line: DraftLineDecl,
        binding: LineSlotBinding,
    ): Int? {
        if (fact.slotId == binding.lineSlotId) return LINE_DIRECT_SPECIFICITY
        val base = binding.baseSlotId ?: return null
        if ((fact.baseSlotId ?: fact.slotId) != base) return null
        if (!state.asprEnabled) return 0
        return when {
            fact.scopeMenuId != null -> if (fact.scopeMenuId == line.menuValueToken) 2 else null
            fact.menuTypeId != null -> if (fact.menuTypeId == line.menuTypeToken) 1 else null
            else -> 0
        }
    }

    private fun linePrecedence(state: ProductionState): Comparator<Pair<Fact, Int>> =
        if (state.asprEnabled) {
            compareByDescending<Pair<Fact, Int>> { it.first.kind.precedence }
                .thenByDescending { it.second }
                .thenByDescending { it.first.authorityVersion }
                .thenByDescending { it.first.seq }
        } else {
            compareByDescending { it.first.seq }
        }

    private const val LINE_DIRECT_SPECIFICITY = 3

    private fun provenanceOf(
        fact: Fact,
        revoked: Boolean,
        autoApplicable: Boolean,
        unusable: Boolean,
    ): String = when {
        unusable -> "CATALOG_CANNOT_FULFIL_ASK_AGAIN"
        revoked -> "AUTO_APPLY_REVOKED_ASK_AGAIN"
        fact.kind == FactKind.ONE_OFF -> "CURRENT_SESSION_INSTRUCTION"
        fact.kind == FactKind.EPHEMERAL -> "CURRENT_DRAFT_REQUEST"
        fact.kind == FactKind.OUTCOME -> "DELAYED_OUTCOME_PROPOSAL"
        fact.kind == FactKind.INFERRED -> "INFERRED_RANKING_ONLY"
        autoApplicable -> "EXPLICIT_STABLE_AUTO_APPLIED"
        else -> "EXPLICIT_STABLE_NEEDS_CONFIRMATION"
    }

    // ---------------------------------------------------------- invalidation

    /**
     * Removes descendants of a changed dependency.
     *
     * With ASPR on, only fields that actually depend on [refs] and are not
     * already confirmed are re-opened. With ASPR off there are no dependency
     * edges, so the whole unconfirmed draft has to be reviewed again.
     */
    fun invalidateDescendants(state: ProductionState, refs: Set<String>, kinds: Set<DepKind>) {
        val touched = mutableSetOf<String>()
        state.fields.values.toList().forEach { field ->
            // A value the user separately chose survives; the ledger entry of an
            // already committed order is never rewritten either way.
            if (field.userConfirmed) return@forEach
            val hit = if (state.asprEnabled) {
                field.deps.any { it.ref in refs && it.kind in kinds }
            } else {
                true
            }
            if (hit) {
                state.fields[field.fieldId] = field.copy(
                    status = FieldStatus.INVALIDATED,
                    value = null,
                    updatedSeq = state.nextSeq(),
                )
                touched += field.fieldId
            }
        }
        if (touched.isEmpty()) return
        // A roll-up of an invalidated line is invalid too.
        state.fields.values.toList().forEach { field ->
            if (field.userConfirmed) return@forEach
            if (field.deps.any { it.kind == DepKind.TOTAL && it.ref in touched }) {
                state.fields[field.fieldId] = field.copy(
                    status = FieldStatus.INVALIDATED,
                    value = null,
                    updatedSeq = state.nextSeq(),
                )
            }
        }
    }

    /**
     * Fields still waiting for the user, in deterministic priority order.
     *
     * The order total is excluded: it is not a separate question but part of the
     * single final confirmation of the draft.
     */
    fun openConfirmationIds(state: ProductionState): List<String> =
        state.fields.values
            .filter { it.slotId != SLOT_TOTAL }
            .filter {
                it.status == FieldStatus.NEEDS_CONFIRMATION || it.status == FieldStatus.INVALIDATED
            }
            .map { it.fieldId }
            .sorted()

    /**
     * True when the current draft still needs the user, or holds values that were
     * never committed. Used so a kill or an export never reports work as finished
     * that was not.
     */
    fun hasUncommittedWork(state: ProductionState): Boolean {
        if (state.fields.isEmpty()) return false
        if (openConfirmationIds(state).isNotEmpty()) return true
        return !state.actions.containsKey(idempotencyKeyFor(draftIdentity(state)))
    }

    fun idempotencyKeyFor(draftIdentity: String): String =
        Ids.state("idem", draftIdentity.take(24))

    /**
     * Digest of the resolved draft, used as the action idempotency identity.
     *
     * Only field values matter, not their confirmation status, so committing a
     * draft does not change its commit identity and a repeated decision request
     * over the same draft reuses the same action.
     */
    fun draftIdentity(state: ProductionState): String {
        val payload = JSONObject()
            .put("runId", state.runId)
            .put("namespace", state.namespace)
            .put("sessionId", state.sessionId)
            .put(
                "fields",
                JSONArray().also { array ->
                    state.fields.values.sortedBy { it.fieldId }.forEach { field ->
                        array.put(
                            JSONObject()
                                .put("fieldId", field.fieldId)
                                .put("value", field.value ?: JSONObject.NULL),
                        )
                    }
                },
            )
        return Digest.canonical(payload)
    }

    // -------------------------------------------------------------- reports

    fun invalidatedIds(before: StateSnapshot, after: StateSnapshot): List<String> {
        val removedFacts = before.factIds - after.factIds
        val reopened = before.resolvedFields.keys.filter { fieldId ->
            after.resolvedFields[fieldId] != before.resolvedFields[fieldId]
        }
        return (removedFacts + reopened).distinct().sorted()
    }

    fun preservedIds(before: StateSnapshot, after: StateSnapshot): List<String> {
        val keptFacts = before.factIds intersect after.factIds
        val keptFields = before.resolvedFields.keys.filter { fieldId ->
            after.resolvedFields[fieldId] == before.resolvedFields[fieldId]
        }
        return (keptFacts + keptFields).distinct().sorted()
    }
}
