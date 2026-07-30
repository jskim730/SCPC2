package org.scpc.r2.sample

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.Sha256

class SampleCoreTest {
    @Test
    fun factDecisionAndDeleteUseOnePersistentState() {
        val store = InMemorySampleStateStore()
        val core = SampleCore(store, "process-a")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(
            step(
                1,
                "UPSERT_FACT",
                JSONObject()
                    .put("TARGET_ENTITY", "ENTITY-A")
                    .put("STABLE_VALUE", "BLUE")
                    .put("CURRENT_AUTHORITY", "AUTH-1"),
            ),
        )
        val decision = core.execute(
            step(2, "REQUEST_DECISION", JSONObject().put("TARGET_ENTITY", "ENTITY-A")),
        )
        assertEquals("ACT", decision.getString("decision_state"))
        assertEquals("COMMITTED", decision.getJSONObject("action").getString("commit_state"))

        val deleted = core.execute(
            step(3, "DELETE_FACT", JSONObject().put("TARGET_ENTITY", "ENTITY-A")),
        )
        assertTrue(deleted.getJSONArray("tombstone_ids").length() == 1)
        assertEquals(1, core.decisionRequestCount())
    }

    @Test
    fun newProcessMarkerIncrementsDurableEpoch() {
        val store = InMemorySampleStateStore()
        val first = SampleCore(store, "process-a")
        val firstResult = first.execute(step(0, "RESET_AND_START"))
        val second = SampleCore(store, "process-b")
        val secondResult = second.execute(step(1, "PROCESS_KILL_RELAUNCH"))
        assertEquals(0, firstResult.getInt("process_epoch"))
        assertEquals(1, secondResult.getInt("process_epoch"))
        assertNotEquals(
            firstResult.getString("state_after_sha256"),
            secondResult.getString("state_after_sha256"),
        )
    }

    @Test
    fun offlineDecisionWaitsWithoutAction() {
        val core = SampleCore(InMemorySampleStateStore(), "process-a")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(step(1, "SET_NETWORK", JSONObject().put("ONE_OFF_VALUE", "OFFLINE")))
        val result = core.execute(step(2, "REQUEST_DECISION"))
        assertEquals("WAIT", result.getString("decision_state"))
        assertTrue(result.isNull("action"))
    }

    @Test
    fun replayUsesTheReferencedOriginalEventAndDoesNotCreateANewAction() {
        val core = SampleCore(InMemorySampleStateStore(), "process-a")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(
            step(
                1,
                "UPSERT_FACT",
                JSONObject().put("TARGET_ENTITY", "ENTITY-A"),
            ),
        )
        val original = core.execute(
            step(2, "REQUEST_DECISION"),
        ).getJSONObject("action")
        val replay = core.execute(
            step(
                3,
                "REPLAY_EVENT",
                JSONObject().put("REPLAY_OF_EVENT_ID", "EVENT-2"),
            ),
        )

        assertEquals("CONFIRMED_COMPLETE", replay.getString("decision_state"))
        assertEquals(
            original.getString("idempotency_key"),
            replay.getJSONObject("action").getString("idempotency_key"),
        )
        assertEquals(
            original.getString("outcome_id"),
            replay.getJSONObject("action").getString("outcome_id"),
        )
    }

    @Test
    fun legacyDecisionCountIsNotReportedAsModelUsage() {
        val store = InMemorySampleStateStore()
        store.save(JSONObject().put("cumulative_invocations", 2))
        val core = SampleCore(store, "process-a")

        assertEquals(2, core.decisionRequestCount())
    }

    private fun step(
        index: Int,
        operation: String,
        roles: JSONObject = JSONObject(),
    ): ProbeInputStep {
        val body = JSONObject()
            .put("step_id", "STEP-$index")
            .put("operation", operation)
            .put("session_id", "SESSION-1")
            .put("virtual_time", "2026-01-01T00:0${index}:00Z")
            .put("event_id", "EVENT-$index")
            .put("roles", roles)
        val canonical = CanonicalJson.encode(body)
        return ProbeInputStep(
            index = index,
            stepId = "STEP-$index",
            operation = operation,
            sessionId = "SESSION-1",
            virtualTime = "2026-01-01T00:0${index}:00Z",
            eventId = "EVENT-$index",
            roles = roles,
            canonicalJson = canonical,
            digest = Sha256.utf8(canonical),
        )
    }
}
