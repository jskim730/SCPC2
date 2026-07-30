package org.scpc.r2.sample

import org.json.JSONArray
import org.json.JSONObject
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.Sha256

interface SampleStateStore {
    fun load(): JSONObject
    fun save(state: JSONObject)
}

class InMemorySampleStateStore : SampleStateStore {
    private var encoded: String? = null

    override fun load(): JSONObject = encoded?.let(::JSONObject) ?: JSONObject()

    override fun save(state: JSONObject) {
        encoded = CanonicalJson.encode(state)
    }
}

class SampleCore(
    private val store: SampleStateStore,
    private val processMarker: String,
) {
    fun execute(step: ProbeInputStep): JSONObject {
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        val before = Sha256.canonicalJson(state)
        val invalidated = linkedSetOf<String>()
        val preserved = linkedSetOf<String>()
        val selected = linkedSetOf<String>()
        var decision = "NO_DECISION"
        var action: JSONObject? = null

        val facts = state.getJSONObject("facts")
        val tombstones = state.getJSONArray("tombstones")
        val actions = state.getJSONObject("actions")
        val target = role(step.roles, "TARGET_ENTITY") ?: "default"
        val factId = "fact-${safeId(target)}"

        when (step.operation) {
            "RESET_AND_START" -> {
                clearObject(facts)
                clearObject(actions)
                clearArray(tombstones)
                clearArray(state.getJSONArray("receipts"))
                clearArray(state.getJSONArray("evidence"))
                state.put("network", "ONLINE")
                state.put("decision_request_count", 0)
            }

            "UPSERT_FACT" -> {
                facts.put(
                    factId,
                    JSONObject()
                        .put("value", role(step.roles, "STABLE_VALUE") ?: target)
                        .put("authority", role(step.roles, "CURRENT_AUTHORITY") ?: "UNSPECIFIED")
                        .put("scope", role(step.roles, "PRESERVED_SCOPE") ?: "DEFAULT"),
                )
                preserved += factId
            }

            "CORRECT_FACT" -> {
                val old = if (facts.has(factId)) factId else null
                if (old != null) invalidated += old
                facts.put(
                    factId,
                    JSONObject()
                        .put("value", role(step.roles, "ONE_OFF_VALUE") ?: target)
                        .put("authority", role(step.roles, "CURRENT_AUTHORITY") ?: "CURRENT")
                        .put("scope", role(step.roles, "PRESERVED_SCOPE") ?: "DEFAULT"),
                )
                preserved += factId
            }

            "REVOKE_SCOPE" -> {
                val revokedScope = role(step.roles, "REVOKED_SCOPE") ?: "DEFAULT"
                val keys = facts.keys().asSequence().toList()
                keys.forEach { key ->
                    val fact = facts.getJSONObject(key)
                    if (fact.optString("scope") == revokedScope) {
                        facts.remove(key)
                        invalidated += key
                    } else {
                        preserved += key
                    }
                }
            }

            "DELETE_FACT" -> {
                if (facts.has(factId)) {
                    facts.remove(factId)
                    invalidated += factId
                }
                appendUnique(tombstones, "tombstone-${safeId(target)}")
            }

            "SET_NETWORK" -> state.put("network", requestedNetwork(step.roles))

            "REQUEST_DECISION" -> {
                state.put(
                    "decision_request_count",
                    state.getInt("decision_request_count") + 1,
                )
                facts.keys().asSequence().sorted().forEach(selected::add)
                decision = when {
                    state.getString("network") != "ONLINE" -> "WAIT"
                    selected.isEmpty() -> "ASK"
                    else -> "ACT"
                }
                if (decision == "ACT") {
                    action = actionFor(step.eventId, actions)
                }
            }

            "REPLAY_EVENT" -> {
                val replayOfEventId = role(step.roles, "REPLAY_OF_EVENT_ID")
                action = replayOfEventId?.let(actions::optJSONObject)
                decision = if (action == null) "ABSTAIN" else "CONFIRMED_COMPLETE"
            }

            "DELIVER_OUT_OF_ORDER" -> decision = "PENDING"
            "PROCESS_KILL_RELAUNCH", "ADVANCE_SESSION", "ADVANCE_TIME" -> {
                decision = if (actions.length() == 0) "NO_DECISION" else "PENDING"
                facts.keys().asSequence().sorted().forEach(preserved::add)
            }

            "EXPORT_AND_END" -> facts.keys().asSequence().sorted().forEach(preserved::add)
        }

        val receiptId = "receipt-${safeId(step.eventId)}"
        val evidenceId = "evidence-${safeId(step.eventId)}"
        appendUnique(state.getJSONArray("receipts"), receiptId)
        appendUnique(state.getJSONArray("evidence"), evidenceId)
        store.save(state)

        return JSONObject()
            .put("step_id", step.stepId)
            .put("event_id", step.eventId)
            .put("operation", step.operation)
            .put("session_id", step.sessionId)
            .put("virtual_time", step.virtualTime)
            .put("process_epoch", state.getInt("process_epoch"))
            .put("network_state", state.getString("network"))
            .put("decision_state", decision)
            .put("selected_context_ids", JSONArray(selected.toList()))
            .put("invalidated_state_ids", JSONArray(invalidated.toList()))
            .put("preserved_state_ids", JSONArray(preserved.toList()))
            .put("state_before_sha256", before)
            .put("state_after_sha256", Sha256.canonicalJson(state))
            .put("action", action ?: JSONObject.NULL)
            .put("tombstone_ids", copyArray(state.getJSONArray("tombstones")))
            .put("receipt_ids", JSONArray().put(receiptId))
            .put("auto_check_evidence_ids", JSONArray().put(evidenceId))
    }

    fun decisionRequestCount(): Int =
        normalized(store.load()).getInt("decision_request_count")

    fun evidenceIds(): List<String> {
        val state = normalized(store.load())
        return strings(state.getJSONArray("evidence")) + strings(state.getJSONArray("receipts"))
    }

    private fun ensureProcessEpoch(state: JSONObject) {
        val previous = state.optString("process_marker", "")
        if (previous != processMarker) {
            if (previous.isNotEmpty()) {
                state.put("process_epoch", state.getInt("process_epoch") + 1)
            }
            state.put("process_marker", processMarker)
        }
    }

    private fun normalized(source: JSONObject): JSONObject = source.apply {
        if (!has("facts")) put("facts", JSONObject())
        if (!has("tombstones")) put("tombstones", JSONArray())
        if (!has("actions")) put("actions", JSONObject())
        if (!has("receipts")) put("receipts", JSONArray())
        if (!has("evidence")) put("evidence", JSONArray())
        if (!has("network")) put("network", "ONLINE")
        if (!has("process_epoch")) put("process_epoch", 0)
        if (!has("process_marker")) put("process_marker", "")
        if (!has("decision_request_count")) {
            put("decision_request_count", optInt("cumulative_invocations", 0))
        }
        remove("cumulative_invocations")
    }

    private fun actionFor(eventId: String, actions: JSONObject): JSONObject {
        val existing = actions.optJSONObject(eventId)
        if (existing != null) return JSONObject(CanonicalJson.encode(existing))
        val suffix = safeId(eventId)
        return JSONObject()
            .put("action_id", "action-$suffix")
            .put("idempotency_key", "idem-$suffix")
            .put("commit_state", "COMMITTED")
            .put("outcome_id", "outcome-$suffix")
            .also { actions.put(eventId, JSONObject(CanonicalJson.encode(it))) }
    }

    private fun requestedNetwork(roles: JSONObject): String {
        val allowed = setOf("ONLINE", "OFFLINE", "DELAYED", "UNKNOWN")
        return roles.keys().asSequence()
            .mapNotNull { roles.opt(it)?.toString()?.uppercase() }
            .firstOrNull { it in allowed }
            ?: "UNKNOWN"
    }

    private fun role(roles: JSONObject, key: String): String? =
        if (roles.has(key) && !roles.isNull(key)) roles.get(key).toString() else null

    private fun safeId(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifEmpty { "value" }

    private fun appendUnique(array: JSONArray, value: String) {
        if (strings(array).none { it == value }) array.put(value)
    }

    private fun strings(array: JSONArray): List<String> =
        (0 until array.length()).map(array::getString)

    private fun copyArray(array: JSONArray): JSONArray = JSONArray(CanonicalJson.encode(array))

    private fun clearObject(value: JSONObject) {
        value.keys().asSequence().toList().forEach(value::remove)
    }

    private fun clearArray(value: JSONArray) {
        while (value.length() > 0) value.remove(value.length() - 1)
    }
}
