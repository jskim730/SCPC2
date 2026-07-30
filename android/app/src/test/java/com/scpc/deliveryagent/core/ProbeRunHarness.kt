package com.scpc.deliveryagent.core

import org.json.JSONObject

/**
 * Drives the production core the way the adapter does: one core instance per
 * step, over one durable store.
 *
 * [relaunch] changes the process marker without touching the store, which is the
 * same situation the app is in after the harness really kills the process.
 */
class ProbeRunHarness(
    private val namespace: String = ProductionState.NAMESPACE_FULL,
    private val asprEnabled: Boolean = true,
    private val runBinding: String = "RUN-UNDER-TEST",
) {

    val store = InMemoryStateStore()
    val steps = mutableListOf<ProbeStep>()
    val results = mutableListOf<JSONObject>()
    val outcomes = mutableListOf<StepOutcome>()

    private var processGeneration = 1
    private var ordinal = 0

    /** Simulates a real process death: same store, new process identity. */
    fun relaunch() {
        processGeneration += 1
    }

    fun state(): ProductionState = core().state()

    fun core(): ProductionCore = ProductionCore(
        store = store,
        processMarker = "test-process-$processGeneration",
        namespace = namespace,
        asprEnabled = asprEnabled,
        runBinding = runBinding,
    )

    fun step(
        operation: String,
        session: String,
        roles: Map<String, String> = emptyMap(),
        stepId: String? = null,
        eventId: String? = null,
        virtualTime: String? = null,
    ): JSONObject {
        ordinal += 1
        val step = ProbeStep(
            index = ordinal,
            stepId = stepId ?: "S%02d".format(ordinal),
            eventId = eventId ?: "E-%02d-%s".format(ordinal, operation),
            operation = operation,
            sessionId = session,
            virtualTime = virtualTime ?: minute(ordinal),
            roles = JSONObject().also { out -> roles.forEach { (k, v) -> out.put(k, v) } },
        )
        val outcome = core().execute(step)
        steps += step
        outcomes += outcome
        results += outcome.result
        return outcome.result
    }

    fun last(): JSONObject = results.last()

    fun decisions(): List<String> = results.map { it.getString("decision_state") }

    fun networks(): List<String> = results.map { it.getString("network_state") }

    fun epochs(): List<Int> = results.map { it.getInt("process_epoch") }

    fun actionIds(): List<String?> = results.map { result ->
        if (result.isNull("action")) null else result.getJSONObject("action").getString("action_id")
    }

    /**
     * Structural signature of the run: what happened at each step, without any
     * identifier that is derived from a role token. Two runs that differ only in
     * token spelling must produce the same signature.
     */
    fun relationSignature(): List<String> = results.map { result ->
        listOf(
            result.getString("operation"),
            result.getString("decision_state"),
            result.getString("network_state"),
            result.getInt("process_epoch").toString(),
            result.getJSONArray("selected_context_ids").length().toString(),
            result.getJSONArray("invalidated_state_ids").length().toString(),
            result.getJSONArray("preserved_state_ids").length().toString(),
            result.getJSONArray("tombstone_ids").length().toString(),
            if (result.isNull("action")) {
                "no-action"
            } else {
                result.getJSONObject("action").getString("commit_state")
            },
        ).joinToString("|")
    }

    /** Every evidence document the run produced, keyed by evidence id. */
    fun evidence(): Map<String, JSONObject> = outcomes
        .flatMap { it.evidence }
        .associate { it.evidenceId to it.content }

    fun verifyShape() = ProbeResultShape.requireRun(steps, results)

    private fun minute(index: Int): String {
        val minutes = index * 7
        val day = 1 + minutes / (60 * 24)
        val minuteOfDay = minutes % (60 * 24)
        return "2026-02-%02dT%02d:%02d:00Z".format(day, minuteOfDay / 60, minuteOfDay % 60)
    }
}

/**
 * Opaque synthetic tokens for tests.
 *
 * Values deliberately do not resemble the published rehearsal strings or any menu
 * name: the core must work from token identity alone.
 */
class TokenSet(private val salt: String) {
    val goal = "goal-$salt-01"
    val target = "entity-$salt-target"
    val distractor = "entity-$salt-other"
    val keepScope = "scope-$salt-keep"
    val revokeScope = "scope-$salt-drop"
    val sideScope = "scope-$salt-line"
    val stable = "value-$salt-stable"
    val oneOff = "value-$salt-once"
    val corrected = "value-$salt-corrected"
    val ephemeral = "value-$salt-note"
    val outcome = "value-$salt-outcome"
    val side = "value-$salt-side"
    val sideSoldOut = "value-$salt-side-gone"
    fun authority(version: Int) = "authority-$salt-v$version"
}
