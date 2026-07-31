package com.scpc.deliveryagent.core

import org.json.JSONArray
import org.json.JSONObject

/** One probe input step, decoupled from the starter AAR types. */
data class ProbeStep(
    val index: Int,
    val stepId: String,
    val eventId: String,
    val operation: String,
    val sessionId: String,
    val virtualTime: String,
    val roles: JSONObject,
)

/** One structured evidence document that a step or the export produced. */
data class EvidenceDoc(
    val evidenceId: String,
    val relativePath: String,
    val content: JSONObject,
)

/** Result of applying one step: the contract step result plus its evidence. */
data class StepOutcome(
    val result: JSONObject,
    val evidence: List<EvidenceDoc>,
)

/** Durable storage for the one production state document. */
interface StateStore {
    /** Encoded document, or null when nothing is stored. */
    fun load(): String?

    /** Atomically replaces the stored document. */
    fun save(encoded: String)

    fun clear()
}

class InMemoryStateStore : StateStore {
    private var encoded: String? = null
    override fun load(): String? = encoded
    override fun save(encoded: String) {
        this.encoded = encoded
    }

    override fun clear() {
        encoded = null
    }
}

/**
 * The production core.
 *
 * Product screens, the public Probe screen and the protected Probe callback all
 * go through this class. Every operation is order independent: it reads the
 * persisted document, applies the meaning of the operation to it, writes it back
 * and reports what it selected, invalidated and preserved. There is no fixed
 * thirteen step narrative and no branch that inspects a pack id or a caller
 * string.
 */
class ProductionCore(
    private val store: StateStore,
    private val processMarker: String,
    private val namespace: String = ProductionState.NAMESPACE_FULL,
    private val asprEnabled: Boolean = true,
    private val runBinding: String? = null,
) {

    // ------------------------------------------------------------- lifecycle

    fun state(): ProductionState = loadState()

    fun cumulativeInvocations(): Int = loadState().modelInvocations

    fun evidenceIds(): List<String> {
        val state = loadState()
        return (state.evidence + state.receipts).distinct()
    }

    private fun loadState(): ProductionState {
        val encoded = store.load() ?: return ProductionState.fresh(namespace, asprEnabled)
        val state = try {
            ProductionState.fromJson(JSONObject(encoded))
        } catch (error: Exception) {
            // An unreadable document is never guessed at. The run reports that
            // recovery is required instead of inventing a completed state.
            ProductionState.fresh(namespace, asprEnabled).also { it.recoveryRequired = true }
        }
        state.namespace = namespace
        state.asprEnabled = asprEnabled
        return state
    }

    /**
     * Bumps the process epoch when the persisted document was written by an
     * earlier process. This is how a real kill and relaunch becomes observable.
     */
    private fun reconcileProcess(state: ProductionState) {
        if (state.processMarker == processMarker) return
        if (state.processMarker.isNotEmpty()) state.processEpoch += 1
        state.processMarker = processMarker
    }

    /**
     * Declares which option slots the current synthetic menu needs a value for.
     *
     * Called by the product surface when the user moves to another restaurant.
     * Probe input never declares an option schema, so this is product data, not a
     * second decision path.
     */
    fun declareOptionSchema(
        slotIds: Collection<String>,
        neverAutoApplySlotIds: Collection<String> = emptySet(),
        unusableValues: Collection<String> = emptySet(),
    ) {
        val state = loadState()
        reconcileProcess(state)
        state.requiredSlots.clear()
        state.requiredSlots.addAll(slotIds)
        state.neverAutoApplySlots.clear()
        state.neverAutoApplySlots.addAll(neverAutoApplySlotIds)
        state.unusableValues.clear()
        state.unusableValues.addAll(unusableValues)
        AsprEngine.project(state, "option-schema")
        store.save(state.encode())
    }

    /**
     * Declares the order lines of the current draft together with the option
     * schema.
     *
     * Like [declareOptionSchema] this is product data about the current synthetic
     * menu, not a second decision path: which lines exist and which slots each
     * line carries is what the user built on screen, and the engine still decides
     * every value through the same selection, permission and dependency rules.
     * Probe input declares no lines.
     */
    fun declareDraftStructure(
        lines: List<DraftLineDecl>,
        requiredSlotIds: Collection<String>,
        neverAutoApplySlotIds: Collection<String> = emptySet(),
        unusableValues: Collection<String> = emptySet(),
    ) {
        val state = loadState()
        reconcileProcess(state)
        state.draftLines.clear()
        state.draftLines.addAll(lines)
        // Every slot that ever belonged to a declared line of this draft stays
        // registered, so a removed line's leftover facts can never re-surface as
        // an order-level field.
        lines.forEach { line -> line.slots.forEach { state.lineSlotRegistry += it.lineSlotId } }
        state.requiredSlots.clear()
        state.requiredSlots.addAll(requiredSlotIds)
        state.neverAutoApplySlots.clear()
        state.neverAutoApplySlots.addAll(neverAutoApplySlotIds)
        state.unusableValues.clear()
        state.unusableValues.addAll(unusableValues)
        AsprEngine.project(state, "draft-structure")
        store.save(state.encode())
    }

    /**
     * Records whether the current draft satisfies the constraints the user stated.
     *
     * Money and minutes are presentation, so the product surface evaluates them and
     * the engine only refuses to act on a draft that breaks them. Probe input states
     * no such constraint, so this stays satisfied there.
     */
    fun declareDraftConstraint(satisfied: Boolean, reason: String) {
        val state = loadState()
        reconcileProcess(state)
        state.constraintSatisfied = satisfied
        state.constraintReason = if (satisfied) "" else reason
        store.save(state.encode())
    }

    /**
     * Records that the user chose these fields themselves.
     *
     * The engine never fills a priced line on its own, so a value there only ever
     * comes from a person tapping it. Marking that explicitly is what lets a later
     * revocation or the deletion of some other fact leave the choice alone, while a
     * catalog change that makes the value unavailable still re-opens it.
     */
    fun confirmUserChoice(slotIds: Collection<String>) {
        val state = loadState()
        reconcileProcess(state)
        var changed = false
        slotIds.forEach { slotId ->
            val fieldId = AsprEngine.fieldIdFor(slotId)
            val field = state.fields[fieldId] ?: return@forEach
            val value = field.value ?: return@forEach
            if (value in state.unusableValues) return@forEach
            if (field.status == FieldStatus.CONFIRMED && field.userConfirmed) return@forEach
            state.fields[fieldId] = field.copy(
                status = FieldStatus.CONFIRMED,
                userConfirmed = true,
                updatedSeq = state.nextSeq(),
            )
            state.resolutions += Resolution(
                fieldId = fieldId,
                kind = "USER_CONFIRMATION",
                stepId = "product-confirm",
                seq = state.nextSeq(),
            )
            changed = true
        }
        if (changed) store.save(state.encode())
    }

    /**
     * Records a review of an order that already happened.
     *
     * The review by itself changes nothing. What it produced is attached later, and
     * only for the remarks the user agreed to remember.
     */
    fun recordReview(
        ratingToken: String?,
        entityId: String?,
        lineValueToken: String?,
        actionId: String?,
        text: String,
    ): String {
        val state = loadState()
        reconcileProcess(state)
        val reviewId = Ids.state("review", "${state.runId}.${state.reviews.size + 1}")
        state.reviews += ReviewRecord(
            reviewId = reviewId,
            ratingToken = ratingToken,
            entityId = entityId,
            lineValueToken = lineValueToken,
            actionId = actionId,
            text = text.take(MAX_REVIEW_TEXT),
            atVirtual = state.virtualNow,
            derivedValueTokens = emptyList(),
            seq = state.nextSeq(),
        )
        store.save(state.encode())
        return reviewId
    }

    /** Notes that a review is where one remembered value and fact came from. */
    fun linkReviewMemory(reviewId: String, valueToken: String, factId: String? = null) {
        val state = loadState()
        reconcileProcess(state)
        val index = state.reviews.indexOfFirst { it.reviewId == reviewId }
        if (index < 0) return
        val review = state.reviews[index]
        val values = if (valueToken in review.derivedValueTokens) {
            review.derivedValueTokens
        } else {
            review.derivedValueTokens + valueToken
        }
        val factIds = if (factId == null || factId in review.derivedFactIds) {
            review.derivedFactIds
        } else {
            review.derivedFactIds + factId
        }
        if (values == review.derivedValueTokens && factIds == review.derivedFactIds) return
        state.reviews[index] = review.copy(
            derivedValueTokens = values,
            derivedFactIds = factIds,
        )
        store.save(state.encode())
    }

    /** Settles which menu a review was about. */
    fun setReviewTarget(reviewId: String, menuToken: String) {
        val state = loadState()
        reconcileProcess(state)
        val index = state.reviews.indexOfFirst { it.reviewId == reviewId }
        if (index < 0) return
        state.reviews[index] = state.reviews[index].copy(lineValueToken = menuToken)
        store.save(state.encode())
    }

    /**
     * Deletes a review and everything learned from it.
     *
     * The text goes, the scheduled or already folded-in result goes, and the fields
     * that depended on it are re-opened. Only a marker stays, so a later restart or
     * a late event cannot bring the review or its memory back.
     */
    fun deleteReview(reviewId: String) {
        val state = loadState()
        reconcileProcess(state)
        val review = state.reviews.firstOrNull { it.reviewId == reviewId } ?: return
        state.reviews.removeAll { it.reviewId == reviewId }

        fun removeFactWithMarker(fact: Fact) {
            state.facts.remove(fact.factId)
            val tombstone = Tombstone(
                tombstoneId = Ids.state("tomb", fact.slotId),
                slotId = fact.slotId,
                scopeIds = fact.scopeIds,
                authorityVersion = fact.authorityVersion,
                deletedAtVirtual = state.virtualNow,
                kind = fact.kind,
                seq = state.nextSeq(),
            )
            state.tombstones[tombstone.tombstoneId] = tombstone
            state.fields.values
                .filter { it.dependsOn(fact.factId) }
                .map { it.fieldId }
                .forEach(state.fields::remove)
            AsprEngine.invalidateDescendants(
                state,
                refs = setOf(fact.factId),
                kinds = setOf(DepKind.HARD_VALUE, DepKind.RANKING),
            )
        }

        // Exactly the scoped facts this review produced. A same-valued preference
        // the user stated independently keeps living under its own fact id.
        review.derivedFactIds.forEach { factId ->
            state.facts[factId]?.let(::removeFactWithMarker)
        }
        review.derivedValueTokens.forEach { valueToken ->
            val outcomeId = Ids.state("outcome", valueToken)
            state.pendingOutcomes.removeAll { it.outcomeId == outcomeId }
            state.appliedOutcomes.remove(outcomeId)
            state.facts.values
                .filter { it.kind == FactKind.OUTCOME && it.value == valueToken }
                .toList()
                .forEach(::removeFactWithMarker)
        }
        AsprEngine.project(state, "review-delete")
        store.save(state.encode())
    }

    /** Full reset offered on the memory screen. Removes everything, keeps nothing. */
    fun resetEverything() {
        store.clear()
    }

    // -------------------------------------------------------------- stepping

    fun execute(step: ProbeStep): StepOutcome {
        val state = loadState()
        // The digest of what was actually persisted, taken before this step changes
        // anything, so consecutive step results chain. Process reconciliation is an
        // effect of this step, not of the previous one.
        val stateBeforeDigest = state.digest()
        reconcileProcess(state)

        val before = StateSnapshot.of(state)
        val view = RoleView(step.roles)
        val notes = JSONObject()

        if (step.virtualTime > state.virtualNow) state.virtualNow = step.virtualTime

        var decision = DecisionState.NO_DECISION
        var action: ActionRecord? = null

        if (state.recoveryRequired && !state.recoveryReported) {
            // Reported once, honestly, before anything else is claimed.
            state.recoveryReported = true
            notes.put("recovery", "RECOVERY_REQUIRED_INTERRUPTED_WRITE")
            decision = DecisionState.FAILED
        }

        val duplicateStep = state.events.containsKey(step.eventId) &&
            step.operation != Op.RESET_AND_START
        if (duplicateStep) {
            // Exactly-once delivery: a re-delivered step never applies twice.
            state.duplicateActionAttempts += 1
            state.rejectedEvents += RejectedEvent(
                eventId = step.eventId,
                reason = "DUPLICATE_STEP_DELIVERY_IGNORED",
                incomingAuthorityVersion = 0,
                currentAuthorityVersion = 0,
                seq = state.nextSeq(),
            )
            action = state.actionByEvent[step.eventId]?.let(state.actions::get)
            decision = if (action != null) DecisionState.CONFIRMED_COMPLETE else DecisionState.NO_DECISION
            notes.put("duplicate_step", true)
        } else {
            openSessionIfNeeded(state, step)
            val applied = apply(state, step, view, notes)
            decision = if (decision == DecisionState.FAILED) decision else applied.first
            action = applied.second
            state.events[step.eventId] = EventRecord(
                eventId = step.eventId,
                stepId = step.stepId,
                operation = step.operation,
                sessionId = state.sessionId,
                seq = state.nextSeq(),
            )
            state.runStepCount += 1
        }

        val receiptId = Ids.file("rc", step.stepId)
        if (receiptId !in state.receipts) state.receipts += receiptId
        val stepEvidenceId = Ids.file("ev", step.stepId, "step")
        if (stepEvidenceId !in state.evidence) state.evidence += stepEvidenceId

        val after = StateSnapshot.of(state)
        val selection = AsprEngine.select(state)
        val openConfirmations = AsprEngine.openConfirmationIds(state)

        val result = JSONObject()
            .put("step_id", step.stepId)
            .put("event_id", step.eventId)
            .put("operation", step.operation)
            .put("session_id", step.sessionId)
            .put("virtual_time", step.virtualTime)
            .put("process_epoch", state.processEpoch)
            .put("network_state", state.network.name)
            .put("decision_state", decision.name)
            .put("selected_context_ids", JSONArray(selection.selectedFactIds()))
            .put("invalidated_state_ids", JSONArray(AsprEngine.invalidatedIds(before, after)))
            .put("preserved_state_ids", JSONArray(AsprEngine.preservedIds(before, after)))
            .put("state_before_sha256", stateBeforeDigest)
            .put("state_after_sha256", state.digest())
            .put("action", action?.toResultJson() ?: JSONObject.NULL)
            .put("tombstone_ids", JSONArray(state.tombstones.keys.sorted()))
            .put("receipt_ids", JSONArray(listOf(receiptId)))
            .put("auto_check_evidence_ids", JSONArray(listOf(stepEvidenceId)))

        val evidence = mutableListOf(
            stepEvidence(stepEvidenceId, step, state, decision, selection, openConfirmations, notes),
            receiptEvidence(receiptId, step, state, action),
        )
        if (step.operation == Op.EXPORT_AND_END) evidence += exportEvidence(state)

        store.save(state.encode())
        return StepOutcome(result, evidence)
    }

    // ------------------------------------------------------------ operations

    private fun apply(
        state: ProductionState,
        step: ProbeStep,
        view: RoleView,
        notes: JSONObject,
    ): Pair<DecisionState, ActionRecord?> = when (step.operation) {
        Op.RESET_AND_START -> resetAndStart(state, step, notes)
        Op.UPSERT_FACT -> upsertFact(state, step, view, notes)
        Op.ADVANCE_SESSION -> advanceSession(state, step, view, notes)
        Op.REQUEST_DECISION -> requestDecision(state, step, view, notes)
        Op.CORRECT_FACT -> correctFact(state, step, view, notes)
        Op.REVOKE_SCOPE -> revokeScope(state, step, view, notes)
        Op.DELETE_FACT -> deleteFact(state, step, view, notes)
        Op.SET_NETWORK -> setNetwork(state, step, view, notes)
        Op.PROCESS_KILL_RELAUNCH -> prepareForKill(state, step, notes)
        Op.REPLAY_EVENT -> replayEvent(state, step, view, notes)
        Op.DELIVER_OUT_OF_ORDER -> deliverOutOfOrder(state, step, view, notes)
        Op.ADVANCE_TIME -> advanceTime(state, step, view, notes)
        Op.EXPORT_AND_END -> exportAndEnd(state, step, notes)
        else -> throw IllegalArgumentException("unsupported operation ${step.operation}")
    }

    private fun resetAndStart(
        state: ProductionState,
        step: ProbeStep,
        notes: JSONObject,
    ): Pair<DecisionState, ActionRecord?> {
        if (state.runId.isNotEmpty()) {
            // The interrupted or finished previous run stays available for audit;
            // only the active state is cleaned.
            state.archive += ArchivedRun(
                runId = state.runId,
                terminalDigest = state.digest(),
                endedAtVirtual = state.virtualNow,
                stepCount = state.runStepCount,
                terminal = state.runTerminal,
            )
            notes.put("archived_run_id", state.runId)
        }
        state.clearActiveRunState()
        state.runId = Ids.state("run", runBinding ?: step.eventId)
        state.virtualNow = step.virtualTime
        state.sessionSeq += 1
        state.sessionLabel = step.sessionId
        state.sessionId = internalSessionKey(step.sessionId, state.sessionSeq)
        state.recoveryRequired = false
        notes.put("run_id", state.runId)
        return DecisionState.NO_DECISION to null
    }

    private fun upsertFact(
        state: ProductionState,
        step: ProbeStep,
        view: RoleView,
        notes: JSONObject,
    ): Pair<DecisionState, ActionRecord?> {
        updateContext(state, view)
        val stored = storeFactsFrom(state, step, view, FactSource.USER_EXPLICIT, notes)
        AsprEngine.project(state, step.stepId)
        return (if (stored.isEmpty()) DecisionState.NO_DECISION else DecisionState.PENDING) to null
    }

    private fun advanceSession(
        state: ProductionState,
        step: ProbeStep,
        view: RoleView,
        notes: JSONObject,
    ): Pair<DecisionState, ActionRecord?> {
        updateContext(state, view)
        AsprEngine.project(state, step.stepId)
        notes.put("session_id", state.sessionId)
        notes.put("expired_fact_ids", JSONArray(state.lastExpiredFactIds.toList()))
        return DecisionState.NO_DECISION to null
    }

    private fun requestDecision(
        state: ProductionState,
        step: ProbeStep,
        view: RoleView,
        notes: JSONObject,
    ): Pair<DecisionState, ActionRecord?> {
        state.decisionRequests += 1
        updateContext(state, view)
        AsprEngine.project(state, step.stepId)

        val open = AsprEngine.openConfirmationIds(state)
        notes.put("open_confirmation_ids", JSONArray(open))
        notes.put("decision_request_count", state.decisionRequests)

        if (state.fields.isEmpty()) {
            // Nothing valid is known yet, so the agent asks instead of guessing.
            notes.put("reason", "NO_ACTIVE_CONTEXT_ASK")
            return DecisionState.ASK to null
        }
        if (open.isNotEmpty()) {
            // Repeating the request without new facts returns the same unresolved
            // question set; call count alone never advances the conversation.
            notes.put("reason", "OPEN_CONFIRMATIONS_ASK")
            return DecisionState.ASK to null
        }
        val blocked = networkPosture(state)
        if (blocked != null) {
            notes.put("reason", "CATALOG_FRESHNESS_${state.network.name}")
            return blocked to null
        }
        if (!state.constraintSatisfied) {
            // Every question is answered, but no draft satisfies what the user asked
            // for. The agent stops and says so rather than committing anyway.
            notes.put("reason", "CONSTRAINT_NOT_SATISFIABLE")
            notes.put("constraint", state.constraintReason)
            return DecisionState.ABSTAIN to null
        }
        val action = commitDraft(state, step)
        notes.put("reason", if (action.originEventId == step.eventId) "COMMITTED" else "IDEMPOTENT_REUSE")
        return DecisionState.ACT to action
    }

    private fun correctFact(
        state: ProductionState,
        step: ProbeStep,
        view: RoleView,
        notes: JSONObject,
    ): Pair<DecisionState, ActionRecord?> {
        updateContext(state, view)
        val newValue = view.firstValue(
            Role.STABLE_VALUE,
            Role.ONE_OFF_VALUE,
            Role.EPHEMERAL_VALUE,
            Role.DELAYED_OUTCOME,
        )
        val target = resolveCorrectionTarget(state, view)
        if (target == null || newValue == null) {
            notes.put("reason", "NO_CORRECTABLE_FACT")
            return DecisionState.ABSTAIN to null
        }
        val authorityToken = view.value(Role.CURRENT_AUTHORITY)
        val authority = state.authorityVersionOf(authorityToken)
        if (authority <= target.authorityVersion) {
            // A correction that is not more current than the value it targets is
            // not applied; the current value stays authoritative.
            state.rejectedEvents += RejectedEvent(
                eventId = step.eventId,
                reason = "STALE_CORRECTION_IGNORED",
                incomingAuthorityVersion = authority,
                currentAuthorityVersion = target.authorityVersion,
                seq = state.nextSeq(),
            )
            notes.put("reason", "STALE_CORRECTION_IGNORED")
            return DecisionState.ABSTAIN to null
        }
        state.facts[target.factId] = target.copy(
            value = newValue,
            authorityVersion = authority,
            source = FactSource.USER_CORRECTION,
            seq = state.nextSeq(),
        )
        AsprEngine.invalidateDescendants(
            state,
            refs = setOf(target.factId),
            kinds = setOf(DepKind.HARD_VALUE, DepKind.RANKING),
        )
        AsprEngine.project(state, step.stepId)
        notes.put("corrected_fact_id", target.factId)
        notes.put("authority_version", authority)
        return DecisionState.NO_DECISION to null
    }

    private fun revokeScope(
        state: ProductionState,
        step: ProbeStep,
        view: RoleView,
        notes: JSONObject,
    ): Pair<DecisionState, ActionRecord?> {
        updateContext(state, view)
        val token = view.value(Role.REVOKED_SCOPE)
        if (token == null) {
            notes.put("reason", "NO_SCOPE_NAMED")
            return DecisionState.ABSTAIN to null
        }
        val scopeId = AsprEngine.scopeIdFor(token)
        state.revokedScopes += scopeId
        // Only the auto-apply permission is withdrawn. The stored preference and
        // every other scope keep their value.
        AsprEngine.invalidateDescendants(
            state,
            refs = setOf(scopeId),
            kinds = setOf(DepKind.PERMISSION),
        )
        AsprEngine.project(state, step.stepId)
        notes.put("revoked_scope_id", scopeId)
        notes.put(
            "preserved_fact_ids",
            JSONArray(state.facts.keys.sorted()),
        )
        return DecisionState.NO_DECISION to null
    }

    private fun deleteFact(
        state: ProductionState,
        step: ProbeStep,
        view: RoleView,
        notes: JSONObject,
    ): Pair<DecisionState, ActionRecord?> {
        updateContext(state, view)
        val target = resolveDeletionTarget(state, view)
        if (target == null) {
            notes.put("reason", "NO_DELETABLE_FACT")
            return DecisionState.ABSTAIN to null
        }
        state.facts.remove(target.factId)
        val tombstone = Tombstone(
            tombstoneId = Ids.state("tomb", target.slotId),
            slotId = target.slotId,
            scopeIds = target.scopeIds,
            authorityVersion = target.authorityVersion,
            deletedAtVirtual = state.virtualNow,
            kind = target.kind,
            seq = state.nextSeq(),
        )
        state.tombstones[tombstone.tombstoneId] = tombstone
        // The original text and every field derived from it are removed. Only the
        // marker stays, and it carries no restorable value.
        state.fields.values.filter { it.dependsOn(target.factId, DepKind.HARD_VALUE) }
            .map { it.fieldId }
            .forEach(state.fields::remove)
        AsprEngine.invalidateDescendants(
            state,
            refs = setOf(target.factId),
            kinds = setOf(DepKind.HARD_VALUE, DepKind.RANKING),
        )
        AsprEngine.project(state, step.stepId)
        notes.put("deleted_fact_id", target.factId)
        notes.put("tombstone_id", tombstone.tombstoneId)
        return DecisionState.NO_DECISION to null
    }

    private fun setNetwork(
        state: ProductionState,
        step: ProbeStep,
        view: RoleView,
        notes: JSONObject,
    ): Pair<DecisionState, ActionRecord?> {
        updateContext(state, view)
        // The key name is not assumed; the closed contract enum is searched for
        // in the role values, and an unreadable state is UNKNOWN, not ONLINE.
        state.network = view.networkState() ?: NetworkState.UNKNOWN
        AsprEngine.project(state, step.stepId)
        notes.put("network_state", state.network.name)
        notes.put(
            "catalog_cached_for_target",
            state.targetEntityId != null && state.targetEntityId in state.cachedCatalogEntities,
        )
        if (!AsprEngine.hasUncommittedWork(state)) return DecisionState.NO_DECISION to null
        return (networkPosture(state) ?: DecisionState.PENDING) to null
    }

    private fun prepareForKill(
        state: ProductionState,
        step: ProbeStep,
        notes: JSONObject,
    ): Pair<DecisionState, ActionRecord?> {
        val checkpoint = state.digest()
        state.killCheckpointSeq = state.seq
        state.killCheckpointDigest = checkpoint
        val uncommitted = AsprEngine.hasUncommittedWork(state)
        notes.put("checkpoint_digest", checkpoint)
        notes.put("open_confirmation_ids", JSONArray(AsprEngine.openConfirmationIds(state)))
        notes.put("uncommitted_draft", uncommitted)
        notes.put("committed_action_count", state.actions.size)
        // An action that was never committed is never reported as complete.
        return (if (uncommitted) DecisionState.PENDING else DecisionState.NO_DECISION) to null
    }

    private fun replayEvent(
        state: ProductionState,
        step: ProbeStep,
        view: RoleView,
        notes: JSONObject,
    ): Pair<DecisionState, ActionRecord?> {
        val referenced = view.referencedEventId(state.events.keys, step.eventId)
        if (referenced == null) {
            notes.put("reason", "UNKNOWN_REPLAY_TARGET")
            return DecisionState.ABSTAIN to null
        }
        // The duplicate is recorded and the original action is returned unchanged.
        // No additional commit happens.
        state.duplicateActionAttempts += 1
        state.rejectedEvents += RejectedEvent(
            eventId = referenced,
            reason = "DUPLICATE_EVENT_NO_ADDITIONAL_COMMIT",
            incomingAuthorityVersion = 0,
            currentAuthorityVersion = 0,
            seq = state.nextSeq(),
        )
        val action = state.actionByEvent[referenced]?.let(state.actions::get)
        notes.put("replayed_event_id", referenced)
        notes.put("additional_commits", 0)
        return (if (action != null) DecisionState.CONFIRMED_COMPLETE else DecisionState.NO_DECISION) to action
    }

    private fun deliverOutOfOrder(
        state: ProductionState,
        step: ProbeStep,
        view: RoleView,
        notes: JSONObject,
    ): Pair<DecisionState, ActionRecord?> {
        val referenced = view.referencedEventId(state.events.keys, step.eventId)
        val hasValue = view.firstValue(
            Role.STABLE_VALUE,
            Role.ONE_OFF_VALUE,
            Role.EPHEMERAL_VALUE,
            Role.DELAYED_OUTCOME,
        ) != null
        val authorityToken = view.value(Role.CURRENT_AUTHORITY)
        // A late event never mints a fresh authority version. An unknown token is
        // treated as older than everything already stored.
        val incoming = state.knownAuthorityVersionOf(authorityToken) ?: 0L

        val upfrontReason = when {
            referenced != null -> "ALREADY_PROCESSED_EVENT_REDELIVERED"
            !hasValue -> "NO_NEW_INFORMATION"
            else -> null
        }
        if (upfrontReason != null) {
            state.rejectedEvents += RejectedEvent(
                eventId = referenced ?: step.eventId,
                reason = upfrontReason,
                incomingAuthorityVersion = incoming,
                currentAuthorityVersion = state.authorityCounter,
                seq = state.nextSeq(),
            )
            notes.put("reason", upfrontReason)
            notes.put("preserved_fact_ids", JSONArray(state.facts.keys.sorted()))
            return DecisionState.ABSTAIN to null
        }

        // The same per-slot authority and deletion rules an ordinary update goes
        // through, so a late event cannot take a different route into state.
        val stored = storeFactsFrom(state, step, view, FactSource.CATALOG_EVENT, notes, lateDelivery = true)
        if (stored.isEmpty()) {
            notes.put("reason", "STALE_OR_DELETED_NOT_APPLIED")
            notes.put("preserved_fact_ids", JSONArray(state.facts.keys.sorted()))
            return DecisionState.ABSTAIN to null
        }
        updateContext(state, view)
        AsprEngine.project(state, step.stepId)
        notes.put("reason", "NEWER_THAN_CURRENT_APPLIED")
        return DecisionState.PENDING to null
    }

    private fun advanceTime(
        state: ProductionState,
        step: ProbeStep,
        view: RoleView,
        notes: JSONObject,
    ): Pair<DecisionState, ActionRecord?> {
        updateContext(state, view)
        val arrived = mutableListOf<String>()

        view.value(Role.DELAYED_OUTCOME)?.let { token ->
            materialiseOutcome(
                state = state,
                token = token,
                slotId = AsprEngine.slotFor(FactKind.OUTCOME, view, state),
                scopeIds = scopeIdsFrom(view),
                arrived = arrived,
            )
        }
        state.pendingOutcomes.toList()
            .filter { it.scheduledAtVirtual <= state.virtualNow }
            .forEach { pending ->
                materialiseOutcome(
                    state = state,
                    token = pending.value,
                    slotId = pending.slotId,
                    scopeIds = pending.scopeIds,
                    arrived = arrived,
                    expiresAtVirtual = pending.expiresAtVirtual,
                )
                state.pendingOutcomes.remove(pending)
            }

        AsprEngine.project(state, step.stepId)
        notes.put("virtual_time", state.virtualNow)
        notes.put("materialised_outcome_ids", JSONArray(arrived))
        notes.put("expired_fact_ids", JSONArray(state.lastExpiredFactIds.toList()))
        return (if (arrived.isEmpty()) DecisionState.NO_DECISION else DecisionState.PENDING) to null
    }

    private fun exportAndEnd(
        state: ProductionState,
        step: ProbeStep,
        notes: JSONObject,
    ): Pair<DecisionState, ActionRecord?> {
        AsprEngine.project(state, step.stepId)
        state.runTerminal = true
        val openWork = AsprEngine.hasUncommittedWork(state)
        notes.put("terminal", true)
        notes.put("open_work", openWork)
        notes.put("committed_action_count", state.actions.size)
        return when {
            openWork -> DecisionState.PENDING
            state.actions.isNotEmpty() -> DecisionState.CONFIRMED_COMPLETE
            else -> DecisionState.NO_DECISION
        } to null
    }

    // --------------------------------------------------------------- helpers

    private fun internalSessionKey(label: String, seq: Long): String = "$label#$seq"

    /**
     * Opens a new order session when the step declares a different session, and
     * always when the caller explicitly advances the session. Only conditions
     * bound to the previous session expire; stored preferences stay.
     */
    private fun openSessionIfNeeded(state: ProductionState, step: ProbeStep) {
        if (step.operation == Op.RESET_AND_START) return
        val boundary = state.sessionLabel != step.sessionId ||
            step.operation == Op.ADVANCE_SESSION
        if (!boundary) return
        state.sessionSeq += 1
        state.sessionLabel = step.sessionId
        state.sessionId = internalSessionKey(step.sessionId, state.sessionSeq)
        // A new order session starts a new draft. The previous order stays in the
        // action ledger; it is not silently carried into the new one.
        state.fields.clear()
        state.draftLines.clear()
        state.lineSlotRegistry.clear()
    }

    private fun updateContext(state: ProductionState, view: RoleView) {
        view.value(Role.PRIMARY_GOAL)?.let { state.goalId = it }
        view.value(Role.TARGET_ENTITY)?.let { entity ->
            state.targetEntityId = entity
            state.distractorEntityIds.remove(entity)
            if (state.network == NetworkState.ONLINE) state.cachedCatalogEntities += entity
        }
        view.value(Role.DISTRACTOR_ENTITY)?.let { entity ->
            if (entity != state.targetEntityId) state.distractorEntityIds += entity
        }
    }

    private fun scopeIdsFrom(view: RoleView): List<String> =
        listOfNotNull(view.value(Role.PRESERVED_SCOPE)?.let(AsprEngine::scopeIdFor))

    /**
     * Stores every typed value the step carries.
     *
     * A named preserved scope is the signal that the value is option semantics
     * the user allowed to be reused and auto-applied beyond one restaurant.
     * Without it the value stays bound to the current entity and goal, so it is
     * not carried to another target.
     */
    private fun storeFactsFrom(
        state: ProductionState,
        step: ProbeStep,
        view: RoleView,
        source: FactSource,
        notes: JSONObject,
        lateDelivery: Boolean = false,
    ): List<String> {
        val preservedScope = view.value(Role.PRESERVED_SCOPE)
        val scopeIds = scopeIdsFrom(view)
        val namedEntity = view.value(Role.TARGET_ENTITY)
        // A named preserved scope is what makes a value reusable beyond one target.
        // Without it the value stays exclusive to the target it was learned at.
        val exclusiveToEntity = preservedScope == null && namedEntity != null
        val entityId = if (exclusiveToEntity) namedEntity else null
        val goalId = if (exclusiveToEntity) view.value(Role.PRIMARY_GOAL) ?: state.goalId else null
        val authorityToken = view.value(Role.CURRENT_AUTHORITY)
        val authority = if (lateDelivery) {
            // A late event does not get to become the most current instruction just
            // by arriving now.
            state.knownAuthorityVersionOf(authorityToken) ?: 0L
        } else {
            state.authorityVersionOf(authorityToken)
        }
        // Structural scope of the value, when the product surface addressed one.
        // Absent on every official probe step, so those store unscoped facts.
        val baseScopeToken = view.value(RoleExt.BASE_SCOPE)
        val scopeSpecificity = view.value(RoleExt.SPECIFICITY)?.toIntOrNull() ?: 0
        val scopeMenuTypeId = view.value(RoleExt.MENU_TYPE_ID)
        val scopeMenuId = view.value(RoleExt.SCOPE_MENU_ID)
        val stored = mutableListOf<String>()
        val rejected = mutableListOf<String>()

        fun put(kind: FactKind, value: String, permission: Permission, lifetime: Lifetime) {
            val slotId = AsprEngine.slotFor(kind, view, state)
            val factId = AsprEngine.factIdFor(kind, slotId)
            val tombstoned = state.tombstones.values
                .filter { it.slotId == slotId }
                .maxOfOrNull { it.authorityVersion }
            if (tombstoned != null && authority <= tombstoned) {
                // A deleted value is not resurrected by a stale re-delivery. A
                // strictly more current instruction is a new fact, not a restore.
                rejected += factId
                state.rejectedEvents += RejectedEvent(
                    eventId = step.eventId,
                    reason = "DELETED_VALUE_NOT_RESURRECTED",
                    incomingAuthorityVersion = authority,
                    currentAuthorityVersion = tombstoned,
                    seq = state.nextSeq(),
                )
                return
            }
            val existing = state.facts[factId]
            val loses = existing != null &&
                (if (lateDelivery) authority <= existing.authorityVersion else authority < existing.authorityVersion)
            if (loses) {
                rejected += factId
                state.rejectedEvents += RejectedEvent(
                    eventId = step.eventId,
                    reason = "STALE_AUTHORITY_VERSION",
                    incomingAuthorityVersion = authority,
                    currentAuthorityVersion = existing.authorityVersion,
                    seq = state.nextSeq(),
                )
                return
            }
            state.facts[factId] = Fact(
                factId = factId,
                kind = kind,
                slotId = slotId,
                value = value,
                source = source,
                confidence = Confidence.EXPLICIT,
                permission = permission,
                lifetime = lifetime,
                scopeIds = scopeIds,
                authorityVersion = authority,
                entityId = entityId,
                catalogEntityId = namedEntity,
                goalId = goalId,
                boundSessionId = state.sessionId,
                createdAtVirtual = state.virtualNow,
                expiresAtVirtual = null,
                seq = state.nextSeq(),
                baseSlotId = baseScopeToken?.let { Ids.state("slot", it) },
                specificity = scopeSpecificity,
                menuTypeId = scopeMenuTypeId,
                scopeMenuId = scopeMenuId,
            )
            stored += factId
        }

        view.value(Role.STABLE_VALUE)?.let { value ->
            put(
                kind = FactKind.STABLE,
                value = value,
                // Auto-apply is only granted inside a scope the caller named as
                // preserved; otherwise the value is proposed and confirmed.
                permission = if (preservedScope != null) {
                    Permission.AUTO_APPLY
                } else {
                    Permission.ASK_BEFORE_APPLY
                },
                lifetime = Lifetime.STABLE,
            )
        }
        view.value(Role.ONE_OFF_VALUE)?.let { value ->
            put(
                kind = FactKind.ONE_OFF,
                value = value,
                permission = Permission.AUTO_APPLY,
                lifetime = Lifetime.SESSION,
            )
        }
        view.value(Role.EPHEMERAL_VALUE)?.let { value ->
            put(
                kind = FactKind.EPHEMERAL,
                value = value,
                permission = Permission.AUTO_APPLY,
                lifetime = Lifetime.EPHEMERAL,
            )
        }
        view.value(Role.DELAYED_OUTCOME)?.let { value ->
            // Scheduled now, folded in when virtual time reaches it.
            val outcomeId = Ids.state("outcome", value)
            if (state.appliedOutcomes.containsKey(outcomeId)) return@let
            if (state.pendingOutcomes.none { it.outcomeId == outcomeId }) {
                state.pendingOutcomes += PendingOutcome(
                    outcomeId = outcomeId,
                    value = value,
                    slotId = AsprEngine.slotFor(FactKind.OUTCOME, view, state),
                    scopeIds = scopeIds,
                    entityId = entityId,
                    scheduledAtVirtual = state.virtualNow,
                    seq = state.nextSeq(),
                    expiresAtVirtual = view.value(RoleExt.EXPIRES_AT),
                )
                stored += outcomeId
            }
        }

        notes.put("stored_fact_ids", JSONArray(stored))
        if (rejected.isNotEmpty()) notes.put("rejected_fact_ids", JSONArray(rejected))
        return stored
    }

    /**
     * Folds a delayed outcome into the app-local record of truth exactly once.
     * This does not depend on any optional permission surface.
     */
    private fun materialiseOutcome(
        state: ProductionState,
        token: String,
        slotId: String,
        scopeIds: List<String>,
        arrived: MutableList<String>,
        expiresAtVirtual: String? = null,
    ) {
        val outcomeId = Ids.state("outcome", token)
        if (state.appliedOutcomes.containsKey(outcomeId)) return
        val factId = AsprEngine.factIdFor(FactKind.OUTCOME, slotId)
        state.facts[factId] = Fact(
            factId = factId,
            kind = FactKind.OUTCOME,
            slotId = slotId,
            value = token,
            source = FactSource.DELAYED_OUTCOME,
            confidence = Confidence.EXPLICIT,
            // An outcome changes ranking and the proposed option, never an
            // automatic application.
            permission = Permission.ASK_BEFORE_APPLY,
            lifetime = Lifetime.STABLE,
            scopeIds = scopeIds,
            authorityVersion = state.authorityVersionOf(null),
            entityId = null,
            // A satisfaction result is not a priced catalog line.
            catalogEntityId = null,
            goalId = null,
            boundSessionId = state.sessionId,
            createdAtVirtual = state.virtualNow,
            expiresAtVirtual = expiresAtVirtual,
            seq = state.nextSeq(),
        )
        state.appliedOutcomes[outcomeId] = AppliedOutcome(
            outcomeId = outcomeId,
            slotId = slotId,
            appliedAtVirtual = state.virtualNow,
            seq = state.nextSeq(),
        )
        state.pendingOutcomes.removeAll { it.outcomeId == outcomeId }
        arrived += outcomeId
    }

    /** Returns WAIT or ABSTAIN when the current catalog cannot be trusted. */
    private fun networkPosture(state: ProductionState): DecisionState? = when (state.network) {
        NetworkState.ONLINE -> null
        NetworkState.DELAYED, NetworkState.UNKNOWN -> DecisionState.WAIT
        NetworkState.OFFLINE -> {
            val target = state.targetEntityId
            if (target != null && target in state.cachedCatalogEntities) {
                // The cached draft is preserved and the commit is held.
                DecisionState.WAIT
            } else {
                // No safe alternative exists, so the agent stops instead of guessing.
                DecisionState.ABSTAIN
            }
        }
    }

    /**
     * Commits the resolved draft once per draft identity. A repeated decision
     * request over the same draft returns the same action.
     */
    private fun commitDraft(state: ProductionState, step: ProbeStep): ActionRecord {
        val identity = AsprEngine.draftIdentity(state)
        val key = AsprEngine.idempotencyKeyFor(identity)
        val existing = state.actions[key]
        if (existing != null) {
            state.duplicateActionAttempts += 1
            state.actionByEvent[step.eventId] = key
            return existing
        }
        val action = ActionRecord(
            actionId = Ids.state("action", step.eventId),
            idempotencyKey = key,
            commitState = CommitState.COMMITTED,
            outcomeId = Ids.state("outcome", "commit", step.eventId),
            originEventId = step.eventId,
            draftDigest = identity,
            seq = state.nextSeq(),
        )
        state.actions[key] = action
        state.actionByEvent[step.eventId] = key
        // The action ledger, not a field status, is the record that this draft was
        // committed. Field statuses keep describing where each value comes from, so
        // a later correction, revocation or deletion still re-opens what it touches
        // while this ledger entry stays unchanged.
        state.resolutions += Resolution(
            fieldId = AsprEngine.fieldIdFor(AsprEngine.SLOT_TOTAL),
            kind = "FINAL_CONFIRMATION",
            stepId = step.stepId,
            seq = state.nextSeq(),
        )
        return action
    }

    private fun resolveCorrectionTarget(state: ProductionState, view: RoleView): Fact? {
        val slotId = AsprEngine.slotBase(view, state)
        val inSlot = state.facts.values.filter { it.slotId == slotId }
        inSlot.firstOrNull { it.kind == FactKind.STABLE }?.let { return it }
        inSlot.maxByOrNull { it.kind.precedence }?.let { return it }
        val scopeIds = scopeIdsFrom(view).toSet()
        if (scopeIds.isNotEmpty()) {
            state.facts.values
                .filter { fact -> fact.scopeIds.any { it in scopeIds } }
                .maxByOrNull { it.kind.precedence }
                ?.let { return it }
        }
        val entity = view.value(Role.TARGET_ENTITY)
        if (entity != null) {
            state.facts.values.firstOrNull { it.entityId == entity }?.let { return it }
        }
        return null
    }

    /**
     * Picks what the caller asked to delete, preferring the most disposable
     * value addressed by this step.
     */
    private fun resolveDeletionTarget(state: ProductionState, view: RoleView): Fact? {
        val order = listOf(
            FactKind.EPHEMERAL,
            FactKind.ONE_OFF,
            FactKind.OUTCOME,
            FactKind.INFERRED,
            FactKind.STABLE,
        )
        val slotBase = AsprEngine.slotBase(view, state)
        val addressed = state.facts.values.filter {
            it.slotId == slotBase || it.slotId.startsWith("$slotBase.")
        }
        order.forEach { kind ->
            addressed.firstOrNull { it.kind == kind }?.let { return it }
        }
        val scopeIds = scopeIdsFrom(view).toSet()
        if (scopeIds.isNotEmpty()) {
            order.forEach { kind ->
                state.facts.values
                    .firstOrNull { fact -> fact.kind == kind && fact.scopeIds.any { it in scopeIds } }
                    ?.let { return it }
            }
        }
        val entity = view.value(Role.TARGET_ENTITY)
        if (entity != null) {
            order.forEach { kind ->
                state.facts.values.firstOrNull { it.kind == kind && it.entityId == entity }
                    ?.let { return it }
            }
        }
        order.forEach { kind ->
            state.facts.values.firstOrNull { it.kind == kind }?.let { return it }
        }
        return null
    }

    // -------------------------------------------------------------- evidence

    private fun stepEvidence(
        evidenceId: String,
        step: ProbeStep,
        state: ProductionState,
        decision: DecisionState,
        selection: Selection,
        openConfirmations: List<String>,
        notes: JSONObject,
    ): EvidenceDoc = EvidenceDoc(
        evidenceId = evidenceId,
        relativePath = "evidence/steps/$evidenceId.json",
        content = JSONObject()
            .put("evidence_id", evidenceId)
            .put("step_ids", JSONArray(listOf(step.stepId)))
            .put("operation", step.operation)
            .put("event_id", step.eventId)
            .put("session_id", state.sessionId)
            .put("session_label", state.sessionLabel)
            .put("virtual_time", state.virtualNow)
            .put("network_state", state.network.name)
            .put("decision_state", decision.name)
            .put("aspr_enabled", state.asprEnabled)
            .put("namespace", state.namespace)
            .put("roles_seen", RoleView(step.roles).toJson())
            .put("open_confirmation_ids", JSONArray(openConfirmations))
            .put("selected_context_ids", JSONArray(selection.selectedFactIds()))
            .put(
                "excluded_context",
                JSONObject().also { out ->
                    selection.excluded.toSortedMap().forEach { (factId, why) ->
                        out.put(factId, why.name)
                    }
                },
            )
            .put("draft", draftJson(state))
            .put("valid_interaction_load", state.resolutions.size)
            .put("notes", notes),
    )

    private fun receiptEvidence(
        receiptId: String,
        step: ProbeStep,
        state: ProductionState,
        action: ActionRecord?,
    ): EvidenceDoc = EvidenceDoc(
        evidenceId = receiptId,
        relativePath = "evidence/receipts/$receiptId.json",
        content = JSONObject()
            .put("receipt_id", receiptId)
            .put("step_ids", JSONArray(listOf(step.stepId)))
            .put("run_id", state.runId)
            .put("process_epoch", state.processEpoch)
            .put("state_after_sha256", state.digest())
            .put("action", action?.toJson() ?: JSONObject.NULL)
            .put("duplicate_commits_prevented", state.duplicateActionAttempts),
    )

    /** Role-aligned evidence documents referenced by `MISSION_ADAPTER.json`. */
    private fun exportEvidence(state: ProductionState): List<EvidenceDoc> {
        val stepIds = state.events.values.sortedBy { it.seq }.map { it.stepId }
        fun doc(path: String, id: String, body: JSONObject) = EvidenceDoc(
            evidenceId = id,
            relativePath = path,
            content = body
                .put("evidence_id", id)
                .put("step_ids", JSONArray(stepIds))
                .put("run_id", state.runId)
                .put("namespace", state.namespace),
        )

        val selection = AsprEngine.select(state)
        return listOf(
            doc(
                "evidence/state/current_goal.json",
                Ids.file("ev", "state", "current_goal"),
                JSONObject()
                    .put("primary_goal", state.goalId ?: JSONObject.NULL)
                    .put("session_id", state.sessionId)
                    .put("virtual_time", state.virtualNow),
            ),
            doc(
                "evidence/state/context_selection.json",
                Ids.file("ev", "state", "context_selection"),
                JSONObject()
                    .put("target_entity_id", state.targetEntityId ?: JSONObject.NULL)
                    .put("distractor_entity_ids", JSONArray(state.distractorEntityIds.toList()))
                    .put("selected_context_ids", JSONArray(selection.selectedFactIds()))
                    .put(
                        "excluded_context",
                        JSONObject().also { out ->
                            selection.excluded.toSortedMap().forEach { (factId, why) ->
                                out.put(factId, why.name)
                            }
                        },
                    ),
            ),
            doc(
                "evidence/state/facts.json",
                Ids.file("ev", "state", "facts"),
                JSONObject().put(
                    "facts",
                    JSONArray().also { array ->
                        state.facts.values.sortedBy { it.factId }.forEach { array.put(it.toJson()) }
                    },
                ),
            ),
            doc(
                "evidence/state/authority.json",
                Ids.file("ev", "state", "authority"),
                JSONObject()
                    .put("authority_counter", state.authorityCounter)
                    .put("authority_versions", JSONObject(state.authorityTokens.toMap()))
                    .put(
                        "rejected_events",
                        JSONArray().also { array ->
                            state.rejectedEvents.forEach { array.put(it.toJson()) }
                        },
                    ),
            ),
            doc(
                "evidence/state/invalidation.json",
                Ids.file("ev", "state", "invalidation"),
                JSONObject()
                    .put("revoked_scope_ids", JSONArray(state.revokedScopes.toList()))
                    .put("active_scope_ids", JSONArray(state.activeScopeIds().toList()))
                    .put("draft", draftJson(state)),
            ),
            doc(
                "evidence/state/recovery.json",
                Ids.file("ev", "state", "recovery"),
                JSONObject()
                    .put("process_epoch", state.processEpoch)
                    .put("kill_checkpoint_seq", state.killCheckpointSeq)
                    .put("kill_checkpoint_digest", state.killCheckpointDigest)
                    .put(
                        "preserved_field_ids",
                        JSONArray(
                            state.fields.values
                                .filter { it.status == FieldStatus.CONFIRMED }
                                .map { it.fieldId }
                                .sorted(),
                        ),
                    )
                    .put(
                        "reconfirm_field_ids",
                        JSONArray(AsprEngine.openConfirmationIds(state)),
                    ),
            ),
            doc(
                "evidence/state/deletion.json",
                Ids.file("ev", "state", "deletion"),
                JSONObject().put(
                    "tombstones",
                    JSONArray().also { array ->
                        state.tombstones.values.sortedBy { it.tombstoneId }
                            .forEach { array.put(it.toJson()) }
                    },
                ),
            ),
            doc(
                "evidence/ledger/outcomes.json",
                Ids.file("ev", "ledger", "outcomes"),
                JSONObject()
                    .put(
                        "applied_outcomes",
                        JSONArray().also { array ->
                            state.appliedOutcomes.values.sortedBy { it.outcomeId }
                                .forEach { array.put(it.toJson()) }
                        },
                    )
                    .put(
                        "pending_outcomes",
                        JSONArray().also { array ->
                            state.pendingOutcomes.forEach { array.put(it.toJson()) }
                        },
                    ),
            ),
            doc(
                "evidence/ledger/actions.json",
                Ids.file("ev", "ledger", "actions"),
                JSONObject()
                    .put(
                        "actions",
                        JSONArray().also { array ->
                            state.actions.values.sortedBy { it.seq }.forEach { array.put(it.toJson()) }
                        },
                    )
                    .put("duplicate_commits_prevented", state.duplicateActionAttempts)
                    .put(
                        "resolutions",
                        JSONArray().also { array ->
                            state.resolutions.forEach { array.put(it.toJson()) }
                        },
                    )
                    .put("valid_interaction_load", state.resolutions.size)
                    .put("decision_requests", state.decisionRequests)
                    .put("model_invocations", state.modelInvocations),
            ),
        ).onEach { if (it.evidenceId !in state.evidence) state.evidence += it.evidenceId }
    }

    private fun draftJson(state: ProductionState): JSONArray = JSONArray().also { array ->
        state.fields.values.sortedBy { it.fieldId }.forEach { array.put(it.toJson()) }
    }

    private companion object {
        /** A synthetic review is short by design; nothing long-form is persisted. */
        const val MAX_REVIEW_TEXT = 300
    }

    /** Operation names fixed by the official contract. */
    object Op {
        const val RESET_AND_START = "RESET_AND_START"
        const val UPSERT_FACT = "UPSERT_FACT"
        const val ADVANCE_SESSION = "ADVANCE_SESSION"
        const val REQUEST_DECISION = "REQUEST_DECISION"
        const val CORRECT_FACT = "CORRECT_FACT"
        const val REVOKE_SCOPE = "REVOKE_SCOPE"
        const val DELETE_FACT = "DELETE_FACT"
        const val SET_NETWORK = "SET_NETWORK"
        const val PROCESS_KILL_RELAUNCH = "PROCESS_KILL_RELAUNCH"
        const val REPLAY_EVENT = "REPLAY_EVENT"
        const val DELIVER_OUT_OF_ORDER = "DELIVER_OUT_OF_ORDER"
        const val ADVANCE_TIME = "ADVANCE_TIME"
        const val EXPORT_AND_END = "EXPORT_AND_END"

        val ALL = listOf(
            RESET_AND_START, UPSERT_FACT, ADVANCE_SESSION, REQUEST_DECISION, CORRECT_FACT,
            REVOKE_SCOPE, DELETE_FACT, SET_NETWORK, PROCESS_KILL_RELAUNCH, REPLAY_EVENT,
            DELIVER_OUT_OF_ORDER, ADVANCE_TIME, EXPORT_AND_END,
        )
    }
}
