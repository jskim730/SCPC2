package com.scpc.deliveryagent.core

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the official public Probe input through the production core.
 *
 * The step file is the one the kit ships — `release_v3/probe/
 * PUBLIC_PROBE_INPUT_13_STEP.json`, byte for byte — and each step is handed to a
 * fresh core over one durable store, exactly as `ProductionProbeAdapter` does on
 * the device. What it cannot cover is the Runner itself: release binding,
 * request nonce, the anti-replay ledger and the collection of artifacts, all of
 * which belong to the AAR and the harness rather than to the decision engine.
 *
 * The expected decision states are those an actual Runner-driven run on a device
 * produced, recorded in `work/PUBLIC_RUN/PROBE_RESULT.json`. Pinning them here
 * means a change to the engine cannot silently move the contract between device
 * days: the emulator on this machine cannot host a Runner run — its telephony
 * service drops the moment `svc data disable` runs, which is the harness's own
 * device step — so this is what keeps the officially observed behaviour honest
 * between them.
 */
class OfficialPublicInputTest {

    /** The kit input copied into the app's test fixtures for JVM replay. */
    private val bytes = File("../test-fixtures/probe/PUBLIC_PROBE_INPUT_13_STEP.json")
        .readBytes()

    private val input = JSONObject(bytes.toString(Charsets.UTF_8))

    @Test
    fun `the replayed input is the kit's file, byte for byte`() {
        val official = File("../../release_v3/probe/PUBLIC_PROBE_INPUT_13_STEP.json")
        assertTrue("the checked-out official Kit input must exist", official.isFile)
        assertArrayEquals(official.readBytes(), bytes)
    }

    /** Exactly what a Runner-driven device run reported for this input. */
    private val observedOnDevice = listOf(
        "PUBLIC-01" to "NO_DECISION",
        "PUBLIC-02" to "PENDING",
        "PUBLIC-03" to "NO_DECISION",
        "PUBLIC-04" to "ACT",
        "PUBLIC-05" to "NO_DECISION",
        "PUBLIC-06" to "NO_DECISION",
        "PUBLIC-07" to "NO_DECISION",
        "PUBLIC-08" to "WAIT",
        "PUBLIC-09" to "NO_DECISION",
        "PUBLIC-10" to "CONFIRMED_COMPLETE",
        "PUBLIC-11" to "ABSTAIN",
        "PUBLIC-12" to "PENDING",
        "PUBLIC-13" to "PENDING",
    )

    private fun replay(namespace: String, asprEnabled: Boolean): List<Pair<String, JSONObject>> {
        val store = InMemoryStateStore()
        var processGeneration = 1
        val steps = input.getJSONArray("steps")
        val results = mutableListOf<Pair<String, JSONObject>>()
        (0 until steps.length()).forEach { index ->
            val step = steps.getJSONObject(index)
            // The harness kills the process for this operation, so the next core
            // has a new identity over the same store — the situation the device
            // is in after a real relaunch.
            if (step.getString("operation") == "PROCESS_KILL_RELAUNCH") processGeneration += 1
            val core = ProductionCore(
                store = store,
                processMarker = "public-input-process-$processGeneration",
                namespace = namespace,
                asprEnabled = asprEnabled,
                runBinding = input.getString("probe_pack_id"),
            )
            val outcome = core.execute(
                ProbeStep(
                    index = index,
                    stepId = step.getString("step_id"),
                    eventId = step.getString("event_id"),
                    operation = step.getString("operation"),
                    sessionId = step.getString("session_id"),
                    virtualTime = step.getString("virtual_time"),
                    roles = step.getJSONObject("roles"),
                ),
            )
            results += step.getString("step_id") to outcome.result
        }
        return results
    }

    @Test
    fun `the official input still decides exactly as the device run reported`() {
        val results = replay(ProductionState.NAMESPACE_FULL, asprEnabled = true)

        assertEquals("the kit declares thirteen steps", 13, results.size)
        assertEquals(
            observedOnDevice,
            results.map { (stepId, result) -> stepId to result.getString("decision_state") },
        )
    }

    @Test
    fun `every step answers once, in order, naming the step it answers`() {
        val results = replay(ProductionState.NAMESPACE_FULL, asprEnabled = true)
        val steps = input.getJSONArray("steps")

        results.forEachIndexed { index, (stepId, result) ->
            val declared = steps.getJSONObject(index)
            assertEquals(stepId, result.getString("step_id"))
            assertEquals(declared.getString("event_id"), result.getString("event_id"))
            assertEquals(declared.getString("operation"), result.getString("operation"))
            assertTrue(
                "$stepId reports a commit state the contract permits",
                result.optString("commit_state", "NONE") in
                    CommitState.entries.map { it.name } + "",
            )
        }
    }

    @Test
    fun `the claim-off arm answers the same input without failing`() {
        // The paired comparison rests on both arms being the same APK with one
        // mechanism off — so the baseline has to complete this input too, or the
        // comparison would be measuring a broken arm rather than a mechanism.
        val results = replay(ProductionState.NAMESPACE_CLAIM_OFF, asprEnabled = false)

        assertEquals(13, results.size)
        assertTrue(
            "no step fails outright",
            results.none { (_, result) -> result.getString("decision_state") == "FAILED" },
        )
    }
}
