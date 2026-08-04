package com.scpc.deliveryagent.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shape checks for one step result, taken from `PROBE_RESULT.schema.json`.
 *
 * The starter AAR enforces the same contract on the protected path, but its
 * validator is module private, so the same rules are checked here. This lets the
 * public rehearsal screen and the JVM tests fail fast on a result the official
 * run would reject, and it keeps the checks in candidate source where they can be
 * reviewed.
 *
 * Nothing here computes a score, an expected relation or an anchor.
 */
object ProbeResultShape {

    private val REQUIRED = listOf(
        "step_id", "event_id", "operation", "session_id", "virtual_time", "process_epoch",
        "network_state", "decision_state", "selected_context_ids", "invalidated_state_ids",
        "preserved_state_ids", "state_before_sha256", "state_after_sha256", "action",
        "tombstone_ids", "receipt_ids", "auto_check_evidence_ids",
    )

    private val NETWORK_STATES = NetworkState.entries.map { it.name }.toSet()
    private val DECISION_STATES = DecisionState.entries.map { it.name }.toSet()
    private val COMMIT_STATES = CommitState.entries.map { it.name }.toSet()
    private val ACTION_KEYS = setOf("action_id", "idempotency_key", "commit_state", "outcome_id")

    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val EVIDENCE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    /**
     * Verifies a step result and that it answers exactly the step that was asked.
     * Returns the same object so it can be used inline.
     */
    fun require(step: ProbeStep, result: JSONObject): JSONObject {
        val keys = result.keys().asSequence().toSet()
        val missing = REQUIRED.filter { it !in keys }
        check(missing.isEmpty()) { "step ${step.stepId} result missing $missing" }
        val extra = keys - REQUIRED.toSet()
        check(extra.isEmpty()) { "step ${step.stepId} result has unknown members $extra" }

        check(result.getString("step_id") == step.stepId) { "step_id mismatch on ${step.stepId}" }
        check(result.getString("event_id") == step.eventId) { "event_id mismatch on ${step.stepId}" }
        check(result.getString("operation") == step.operation) {
            "operation mismatch on ${step.stepId}"
        }
        check(result.getString("session_id") == step.sessionId) {
            "session_id mismatch on ${step.stepId}"
        }
        check(result.getString("virtual_time") == step.virtualTime) {
            "virtual_time mismatch on ${step.stepId}"
        }
        check(step.operation in ProductionCore.Op.ALL) { "unknown operation ${step.operation}" }
        check(result.getInt("process_epoch") >= 0) { "negative process_epoch on ${step.stepId}" }
        check(result.getString("network_state") in NETWORK_STATES) {
            "unknown network_state on ${step.stepId}"
        }
        check(result.getString("decision_state") in DECISION_STATES) {
            "unknown decision_state on ${step.stepId}"
        }
        check(SHA256.matches(result.getString("state_before_sha256"))) {
            "state_before_sha256 is not a lowercase sha-256 on ${step.stepId}"
        }
        check(SHA256.matches(result.getString("state_after_sha256"))) {
            "state_after_sha256 is not a lowercase sha-256 on ${step.stepId}"
        }

        listOf("selected_context_ids", "invalidated_state_ids", "preserved_state_ids", "tombstone_ids")
            .forEach { key -> uniqueBoundedIds(step, result.getJSONArray(key), key, 256, null) }
        listOf("receipt_ids", "auto_check_evidence_ids").forEach { key ->
            uniqueBoundedIds(step, result.getJSONArray(key), key, 128, EVIDENCE_ID)
        }

        if (!result.isNull("action")) {
            val action = result.getJSONObject("action")
            val actionKeys = action.keys().asSequence().toSet()
            check(actionKeys == ACTION_KEYS) { "action members $actionKeys on ${step.stepId}" }
            check(action.getString("commit_state") in COMMIT_STATES) {
                "unknown commit_state on ${step.stepId}"
            }
            listOf("action_id", "idempotency_key", "outcome_id").forEach { key ->
                if (!action.isNull(key)) {
                    check(action.getString(key).length <= 128) {
                        "action.$key longer than 128 on ${step.stepId}"
                    }
                }
            }
        }
        return result
    }

    /** Verifies a whole run: one result per input step, same order, exactly once. */
    fun requireRun(steps: List<ProbeStep>, results: List<JSONObject>) {
        check(steps.size == results.size) {
            "expected ${steps.size} step results, found ${results.size}"
        }
        steps.forEachIndexed { index, step -> require(step, results[index]) }
        val stepIds = results.map { it.getString("step_id") }
        check(stepIds.size == stepIds.toSet().size) { "duplicate step_id in results" }
        val eventIds = results.map { it.getString("event_id") }
        check(eventIds.size == eventIds.toSet().size) { "duplicate event_id in results" }
    }

    /**
     * Verifies the evidence closure the official SAMPLE_EXPORT validator enforces:
     * every declared ID is referenced by a step and every step reference is
     * declared exactly once.
     */
    fun requireEvidenceClosure(results: List<JSONObject>, evidenceIds: List<String>) {
        val references = results.flatMap { result ->
            listOf("receipt_ids", "auto_check_evidence_ids").flatMap { key ->
                val array = result.getJSONArray(key)
                (0 until array.length()).map(array::getString)
            }
        }
        check(evidenceIds.size == evidenceIds.toSet().size) {
            "top-level evidence_ids contains duplicates"
        }
        check(references.toSet() == evidenceIds.toSet()) {
            "top-level evidence_ids differs from step references: " +
                "unreferenced=${evidenceIds.toSet() - references.toSet()} " +
                "undeclared=${references.toSet() - evidenceIds.toSet()}"
        }
    }

    private fun uniqueBoundedIds(
        step: ProbeStep,
        array: JSONArray,
        key: String,
        maxLength: Int,
        pattern: Regex?,
    ) {
        val values = (0 until array.length()).map(array::getString)
        check(values.size == values.toSet().size) { "$key has duplicates on ${step.stepId}" }
        values.forEach { value ->
            check(value.isNotEmpty() && value.length <= maxLength) {
                "$key entry '$value' out of bounds on ${step.stepId}"
            }
            if (pattern != null) {
                check(pattern.matches(value)) {
                    "$key entry '$value' is not a valid evidence id on ${step.stepId}"
                }
            }
        }
    }
}
