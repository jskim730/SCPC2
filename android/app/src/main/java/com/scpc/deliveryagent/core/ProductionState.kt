package com.scpc.deliveryagent.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The single production state document.
 *
 * Product screens, the public Probe screen and the protected Probe callback all
 * read and write this one document through [ProductionCore]. There is no test
 * only store and no second decision path.
 *
 * `full` and `claim-off` arms live in separate stores with separate namespaces,
 * so the same class describes both; only [asprEnabled] differs.
 */
class ProductionState {

    var schema: Int = SCHEMA
    var namespace: String = NAMESPACE_FULL

    /** False for the paired `claim-off` arm: the Signature mechanism is off. */
    var asprEnabled: Boolean = true

    var runId: String = ""
    var runTerminal: Boolean = false
    var runStepCount: Long = 0

    /** Random per-process marker; a mismatch on load means the process restarted. */
    var processMarker: String = ""
    var processEpoch: Int = 0

    /** Set when a persisted document could not be read back and was not invented. */
    var recoveryRequired: Boolean = false
    var recoveryReported: Boolean = false

    /**
     * Internal session key. It combines the caller's session label with a
     * monotonic counter so an explicit session advance opens a new session even
     * when the caller reuses the same label.
     */
    var sessionId: String = ""

    /** The session label exactly as the caller declared it. */
    var sessionLabel: String = ""
    var sessionSeq: Long = 0
    var virtualNow: String = ""
    var network: NetworkState = NetworkState.ONLINE

    var goalId: String? = null
    var targetEntityId: String? = null
    val distractorEntityIds: MutableSet<String> = linkedSetOf()

    /**
     * Opaque authority tokens are mapped to a monotonic version on first sight.
     * The core never parses a version out of the token spelling.
     */
    var authorityCounter: Long = 0
    val authorityTokens: MutableMap<String, Long> = linkedMapOf()

    /** Entities whose synthetic catalog has been snapshotted at least once. */
    val cachedCatalogEntities: MutableSet<String> = linkedSetOf()

    /**
     * Option slots the current target's synthetic menu requires a value for.
     *
     * The product surface declares the option schema of the restaurant the user
     * is ordering from; a required slot with no valid, permitted fact becomes a
     * question instead of a guess. Probe input does not declare an option schema,
     * so this set stays empty there and the same rules apply to the facts alone.
     */
    val requiredSlots: MutableSet<String> = linkedSetOf()

    /**
     * Slots whose value the user must choose for each order, however well the
     * memory fits. The final menu, the lines and the total are never applied
     * automatically. Probe input declares no option schema, so this is empty there.
     */
    val neverAutoApplySlots: MutableSet<String> = linkedSetOf()

    /**
     * Opaque value tokens the current synthetic catalog cannot fulfil, such as a
     * line that went out of stock. A field holding one of these is never treated as
     * settled, so the user is asked for a replacement instead of being given
     * something unavailable.
     */
    val unusableValues: MutableSet<String> = linkedSetOf()

    /**
     * Whether the current draft satisfies the constraints the user stated, such as
     * a budget. The amount itself is presentation, so the product surface decides
     * this and the engine only reports it honestly.
     */
    var constraintSatisfied: Boolean = true
    var constraintReason: String = ""

    val facts: MutableMap<String, Fact> = linkedMapOf()
    val revokedScopes: MutableSet<String> = linkedSetOf()
    val fields: MutableMap<String, DraftField> = linkedMapOf()
    val tombstones: MutableMap<String, Tombstone> = linkedMapOf()

    val events: MutableMap<String, EventRecord> = linkedMapOf()

    /** Actions keyed by idempotency key so one commit identity commits once. */
    val actions: MutableMap<String, ActionRecord> = linkedMapOf()
    val actionByEvent: MutableMap<String, String> = linkedMapOf()
    var duplicateActionAttempts: Long = 0

    /** Reviews the user left, newest last. Deletable, like any stored memory. */
    val reviews: MutableList<ReviewRecord> = mutableListOf()

    val pendingOutcomes: MutableList<PendingOutcome> = mutableListOf()
    val appliedOutcomes: MutableMap<String, AppliedOutcome> = linkedMapOf()
    val rejectedEvents: MutableList<RejectedEvent> = mutableListOf()

    val receipts: MutableList<String> = mutableListOf()
    val evidence: MutableList<String> = mutableListOf()
    val resolutions: MutableList<Resolution> = mutableListOf()
    val archive: MutableList<ArchivedRun> = mutableListOf()

    /** IDs expired at the most recent session boundary. */
    val lastExpiredFactIds: MutableList<String> = mutableListOf()

    var seq: Long = 0
    var decisionRequests: Long = 0

    /** Model or remote inference calls. The engine is deterministic, so this stays 0. */
    var modelInvocations: Int = 0

    var killCheckpointSeq: Long = -1
    var killCheckpointDigest: String = ""

    fun nextSeq(): Long = ++seq

    /** Resolves an opaque authority token to its monotonic version. */
    fun authorityVersionOf(token: String?): Long {
        if (token == null) return ++authorityCounter
        authorityTokens[token]?.let { return it }
        val assigned = ++authorityCounter
        authorityTokens[token] = assigned
        return assigned
    }

    /** Reads an already known authority version without minting a new one. */
    fun knownAuthorityVersionOf(token: String?): Long? =
        if (token == null) null else authorityTokens[token]

    fun activeScopeIds(): Set<String> =
        facts.values.flatMap { it.scopeIds }.toSortedSet()

    fun clearActiveRunState() {
        facts.clear()
        revokedScopes.clear()
        fields.clear()
        tombstones.clear()
        events.clear()
        actions.clear()
        actionByEvent.clear()
        reviews.clear()
        pendingOutcomes.clear()
        appliedOutcomes.clear()
        rejectedEvents.clear()
        receipts.clear()
        evidence.clear()
        resolutions.clear()
        lastExpiredFactIds.clear()
        distractorEntityIds.clear()
        cachedCatalogEntities.clear()
        requiredSlots.clear()
        neverAutoApplySlots.clear()
        unusableValues.clear()
        constraintSatisfied = true
        constraintReason = ""
        authorityTokens.clear()
        authorityCounter = 0
        duplicateActionAttempts = 0
        decisionRequests = 0
        modelInvocations = 0
        goalId = null
        targetEntityId = null
        network = NetworkState.ONLINE
        runTerminal = false
        runStepCount = 0
        killCheckpointSeq = -1
        killCheckpointDigest = ""
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schema", schema)
        .put("namespace", namespace)
        .put("asprEnabled", asprEnabled)
        .put("runId", runId)
        .put("runTerminal", runTerminal)
        .put("runStepCount", runStepCount)
        .put("processMarker", processMarker)
        .put("processEpoch", processEpoch)
        .put("recoveryRequired", recoveryRequired)
        .put("recoveryReported", recoveryReported)
        .put("sessionId", sessionId)
        .put("sessionLabel", sessionLabel)
        .put("sessionSeq", sessionSeq)
        .put("virtualNow", virtualNow)
        .put("network", network.name)
        .put("goalId", goalId ?: JSONObject.NULL)
        .put("targetEntityId", targetEntityId ?: JSONObject.NULL)
        .put("distractorEntityIds", JSONArray(distractorEntityIds.toList()))
        .put("authorityCounter", authorityCounter)
        .put("authorityTokens", JSONObject(authorityTokens.toMap()))
        .put("cachedCatalogEntities", JSONArray(cachedCatalogEntities.toList()))
        .put("requiredSlots", JSONArray(requiredSlots.toList()))
        .put("neverAutoApplySlots", JSONArray(neverAutoApplySlots.toList()))
        .put("unusableValues", JSONArray(unusableValues.toList()))
        .put("constraintSatisfied", constraintSatisfied)
        .put("constraintReason", constraintReason)
        .put("facts", mapJson(facts) { it.toJson() })
        .put("revokedScopes", JSONArray(revokedScopes.toList()))
        .put("fields", mapJson(fields) { it.toJson() })
        .put("tombstones", mapJson(tombstones) { it.toJson() })
        .put("events", mapJson(events) { it.toJson() })
        .put("actions", mapJson(actions) { it.toJson() })
        .put("actionByEvent", JSONObject(actionByEvent.toMap()))
        .put("duplicateActionAttempts", duplicateActionAttempts)
        .put("reviews", listJson(reviews) { it.toJson() })
        .put("pendingOutcomes", listJson(pendingOutcomes) { it.toJson() })
        .put("appliedOutcomes", mapJson(appliedOutcomes) { it.toJson() })
        .put("rejectedEvents", listJson(rejectedEvents) { it.toJson() })
        .put("receipts", JSONArray(receipts.toList()))
        .put("evidence", JSONArray(evidence.toList()))
        .put("resolutions", listJson(resolutions) { it.toJson() })
        .put("archive", listJson(archive) { it.toJson() })
        .put("lastExpiredFactIds", JSONArray(lastExpiredFactIds.toList()))
        .put("seq", seq)
        .put("decisionRequests", decisionRequests)
        .put("modelInvocations", modelInvocations)
        .put("killCheckpointSeq", killCheckpointSeq)
        .put("killCheckpointDigest", killCheckpointDigest)

    fun digest(): String = Digest.canonical(toJson())

    fun encode(): String = Canonical.encode(toJson())

    companion object {
        const val SCHEMA = 1
        const val NAMESPACE_FULL = "full"
        const val NAMESPACE_CLAIM_OFF = "claim-off"

        fun fresh(namespace: String, asprEnabled: Boolean): ProductionState =
            ProductionState().also {
                it.namespace = namespace
                it.asprEnabled = asprEnabled
            }

        fun fromJson(json: JSONObject): ProductionState {
            val state = ProductionState()
            state.schema = json.optInt("schema", SCHEMA)
            state.namespace = json.optString("namespace", NAMESPACE_FULL)
            state.asprEnabled = json.optBoolean("asprEnabled", true)
            state.runId = json.optString("runId", "")
            state.runTerminal = json.optBoolean("runTerminal", false)
            state.runStepCount = json.optLong("runStepCount", 0)
            state.processMarker = json.optString("processMarker", "")
            state.processEpoch = json.optInt("processEpoch", 0)
            state.recoveryRequired = json.optBoolean("recoveryRequired", false)
            state.recoveryReported = json.optBoolean("recoveryReported", false)
            state.sessionId = json.optString("sessionId", "")
            state.sessionLabel = json.optString("sessionLabel", "")
            state.sessionSeq = json.optLong("sessionSeq", 0)
            state.virtualNow = json.optString("virtualNow", "")
            state.network = NetworkState.parseOrNull(json.optString("network")) ?: NetworkState.ONLINE
            state.goalId = json.optNullableString("goalId")
            state.targetEntityId = json.optNullableString("targetEntityId")
            json.optJSONArray("distractorEntityIds")?.strings()
                ?.let(state.distractorEntityIds::addAll)
            state.authorityCounter = json.optLong("authorityCounter", 0)
            json.optJSONObject("authorityTokens")?.let { tokens ->
                tokens.keys().forEach { key -> state.authorityTokens[key] = tokens.getLong(key) }
            }
            json.optJSONArray("cachedCatalogEntities")?.strings()
                ?.let(state.cachedCatalogEntities::addAll)
            json.optJSONArray("requiredSlots")?.strings()?.let(state.requiredSlots::addAll)
            json.optJSONArray("neverAutoApplySlots")?.strings()
                ?.let(state.neverAutoApplySlots::addAll)
            json.optJSONArray("unusableValues")?.strings()?.let(state.unusableValues::addAll)
            state.constraintSatisfied = json.optBoolean("constraintSatisfied", true)
            state.constraintReason = json.optString("constraintReason", "")
            json.optJSONObject("facts")?.let { facts ->
                facts.keys().forEach { key ->
                    state.facts[key] = Fact.fromJson(facts.getJSONObject(key))
                }
            }
            json.optJSONArray("revokedScopes")?.strings()?.let(state.revokedScopes::addAll)
            json.optJSONObject("fields")?.let { fields ->
                fields.keys().forEach { key ->
                    state.fields[key] = DraftField.fromJson(fields.getJSONObject(key))
                }
            }
            json.optJSONObject("tombstones")?.let { tombstones ->
                tombstones.keys().forEach { key ->
                    state.tombstones[key] = Tombstone.fromJson(tombstones.getJSONObject(key))
                }
            }
            json.optJSONObject("events")?.let { events ->
                events.keys().forEach { key ->
                    state.events[key] = EventRecord.fromJson(events.getJSONObject(key))
                }
            }
            json.optJSONObject("actions")?.let { actions ->
                actions.keys().forEach { key ->
                    state.actions[key] = ActionRecord.fromJson(actions.getJSONObject(key))
                }
            }
            json.optJSONObject("actionByEvent")?.let { byEvent ->
                byEvent.keys().forEach { key -> state.actionByEvent[key] = byEvent.getString(key) }
            }
            state.duplicateActionAttempts = json.optLong("duplicateActionAttempts", 0)
            json.optJSONArray("reviews")?.objects()?.map(ReviewRecord::fromJson)
                ?.let(state.reviews::addAll)
            json.optJSONArray("pendingOutcomes")?.objects()?.map(PendingOutcome::fromJson)
                ?.let(state.pendingOutcomes::addAll)
            json.optJSONObject("appliedOutcomes")?.let { applied ->
                applied.keys().forEach { key ->
                    state.appliedOutcomes[key] = AppliedOutcome.fromJson(applied.getJSONObject(key))
                }
            }
            json.optJSONArray("rejectedEvents")?.objects()?.map(RejectedEvent::fromJson)
                ?.let(state.rejectedEvents::addAll)
            json.optJSONArray("receipts")?.strings()?.let(state.receipts::addAll)
            json.optJSONArray("evidence")?.strings()?.let(state.evidence::addAll)
            json.optJSONArray("resolutions")?.objects()?.map(Resolution::fromJson)
                ?.let(state.resolutions::addAll)
            json.optJSONArray("archive")?.objects()?.map(ArchivedRun::fromJson)
                ?.let(state.archive::addAll)
            json.optJSONArray("lastExpiredFactIds")?.strings()
                ?.let(state.lastExpiredFactIds::addAll)
            state.seq = json.optLong("seq", 0)
            state.decisionRequests = json.optLong("decisionRequests", 0)
            state.modelInvocations = json.optInt("modelInvocations", 0)
            state.killCheckpointSeq = json.optLong("killCheckpointSeq", -1)
            state.killCheckpointDigest = json.optString("killCheckpointDigest", "")
            return state
        }

        private fun <T> mapJson(source: Map<String, T>, encode: (T) -> JSONObject): JSONObject =
            JSONObject().also { out ->
                source.keys.sorted().forEach { key -> out.put(key, encode(source.getValue(key))) }
            }

        private fun <T> listJson(source: List<T>, encode: (T) -> JSONObject): JSONArray =
            JSONArray().also { out -> source.forEach { out.put(encode(it)) } }
    }
}
