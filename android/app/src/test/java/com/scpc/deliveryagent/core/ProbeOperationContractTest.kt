package com.scpc.deliveryagent.core

import com.scpc.deliveryagent.core.ProductionCore.Op
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract behaviour of the thirteen operations on the production core.
 *
 * These run on the JVM against the same core the adapter calls, so the rules can
 * be checked without a device.
 */
class ProbeOperationContractTest {

    private fun run(): Pair<ProbeRunHarness, TokenSet> {
        val harness = ProbeRunHarness()
        val token = TokenSet("ref")
        ReferenceRuns.thirteenOperations(harness, token)
        return harness to token
    }

    @Test
    fun `every input step gets exactly one result in the same order`() {
        val (harness, _) = run()
        harness.verifyShape()
        assertEquals(13, harness.results.size)
        assertEquals(
            harness.steps.map { it.stepId },
            harness.results.map { it.getString("step_id") },
        )
        assertEquals(
            harness.steps.map { it.operation },
            harness.results.map { it.getString("operation") },
        )
    }

    @Test
    fun `all thirteen operation kinds are supported`() {
        val (harness, _) = run()
        assertEquals(
            Op.ALL.toSet(),
            harness.results.map { it.getString("operation") }.toSet(),
        )
    }

    @Test
    fun `process epoch increases only after the process really restarts`() {
        val (harness, _) = run()
        val killIndex = harness.steps.indexOfFirst { it.operation == Op.PROCESS_KILL_RELAUNCH }
        val epochs = harness.epochs()
        assertEquals(
            "no epoch change before the kill",
            1,
            epochs.take(killIndex + 1).toSet().size,
        )
        assertTrue(
            "epoch must increase in the next process",
            epochs[killIndex + 1] > epochs[killIndex],
        )
        assertEquals(
            "epoch is stable inside one process",
            1,
            epochs.drop(killIndex + 1).toSet().size,
        )
    }

    @Test
    fun `state digests chain across steps`() {
        val (harness, _) = run()
        harness.results.zipWithNext { earlier, later ->
            assertEquals(
                "state_before of a step must equal state_after of the previous step",
                earlier.getString("state_after_sha256"),
                later.getString("state_before_sha256"),
            )
        }
    }

    @Test
    fun `a one-off condition expires at the next session and the stable value stays`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("expiry")
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.PRIMARY_GOAL to token.goal,
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
                Role.ONE_OFF_VALUE to token.oneOff,
            ),
        )
        val slot = AsprEngine.slotFor(
            FactKind.STABLE,
            RoleView(JSONObject().put(Role.PRESERVED_SCOPE, token.keepScope)),
            harness.state(),
        )
        val fieldId = AsprEngine.fieldIdFor(slot)

        assertEquals(
            "the condition stated in this session wins its slot",
            token.oneOff,
            harness.state().fields.getValue(fieldId).value,
        )

        harness.step(Op.ADVANCE_SESSION, "S2", mapOf(Role.TARGET_ENTITY to token.target))
        val after = harness.state()
        assertEquals(
            "the stored preference resurfaces once the one-off expires",
            token.stable,
            after.fields.getValue(fieldId).value,
        )
        assertTrue(
            "the expired condition is reported, not silently dropped",
            after.lastExpiredFactIds.any { it.contains(FactKind.ONE_OFF.name) },
        )
        assertTrue(
            "the stored preference itself is untouched",
            after.facts.values.any { it.kind == FactKind.STABLE && it.value == token.stable },
        )
    }

    @Test
    fun `virtual time alone does not expire a session condition`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("clock")
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.ONE_OFF_VALUE to token.oneOff,
            ),
        )
        harness.step(Op.ADVANCE_TIME, "S1", virtualTime = "2026-06-01T00:00:00Z")
        assertTrue(
            "advancing the clock inside one session keeps its conditions",
            harness.state().facts.values.any { it.kind == FactKind.ONE_OFF }.also {
                assertTrue(harness.state().lastExpiredFactIds.isEmpty())
            },
        )
    }

    @Test
    fun `revoking a permission keeps the stored value and asks again`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("revoke")
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.PRIMARY_GOAL to token.goal,
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
            ),
        )
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.sideScope,
                Role.CURRENT_AUTHORITY to token.authority(2),
                Role.STABLE_VALUE to token.side,
            ),
        )
        val keepField = AsprEngine.fieldIdFor(Ids.state("slot", token.keepScope))
        val sideField = AsprEngine.fieldIdFor(Ids.state("slot", token.sideScope))
        assertEquals(
            FieldStatus.AUTO_APPLIED,
            harness.state().fields.getValue(keepField).status,
        )

        val revoke = harness.step(
            Op.REVOKE_SCOPE,
            "S1",
            mapOf(Role.REVOKED_SCOPE to token.keepScope),
        )
        val after = harness.state()

        assertEquals(
            "the revoked field has to be confirmed again",
            FieldStatus.NEEDS_CONFIRMATION,
            after.fields.getValue(keepField).status,
        )
        assertEquals(
            "another scope is untouched",
            FieldStatus.AUTO_APPLIED,
            after.fields.getValue(sideField).status,
        )
        assertTrue(
            "the stored preference text survives the revocation",
            after.facts.values.any { it.value == token.stable },
        )
        assertTrue(
            "the revoked field is reported as invalidated",
            revoke.getJSONArray("invalidated_state_ids").toStringList().contains(keepField),
        )
        assertTrue(
            "the preserved scope is reported as preserved",
            revoke.getJSONArray("preserved_state_ids").toStringList().contains(sideField),
        )
    }

    @Test
    fun `correcting a value raises authority and only touches its own descendants`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("correct")
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.PRIMARY_GOAL to token.goal,
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
            ),
        )
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.sideScope,
                Role.CURRENT_AUTHORITY to token.authority(2),
                Role.STABLE_VALUE to token.side,
            ),
        )
        val keepField = AsprEngine.fieldIdFor(Ids.state("slot", token.keepScope))
        val sideField = AsprEngine.fieldIdFor(Ids.state("slot", token.sideScope))
        val before = harness.state().facts.values.first { it.value == token.stable }

        val correction = harness.step(
            Op.CORRECT_FACT,
            "S1",
            mapOf(
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(3),
                Role.STABLE_VALUE to token.corrected,
            ),
        )
        val after = harness.state()

        assertEquals(token.corrected, after.fields.getValue(keepField).value)
        assertTrue(
            "authority version increases",
            after.facts.getValue(before.factId).authorityVersion > before.authorityVersion,
        )
        assertEquals(
            "an independent field keeps its value",
            token.side,
            after.fields.getValue(sideField).value,
        )
        assertTrue(
            "the corrected field is reported as invalidated",
            correction.getJSONArray("invalidated_state_ids").toStringList().contains(keepField),
        )
        assertTrue(
            "the independent field is reported as preserved",
            correction.getJSONArray("preserved_state_ids").toStringList().contains(sideField),
        )
    }

    @Test
    fun `a correction that is not more current is refused`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("stale")
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(5),
                Role.STABLE_VALUE to token.stable,
            ),
        )
        val refused = harness.step(
            Op.CORRECT_FACT,
            "S1",
            mapOf(
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(5),
                Role.STABLE_VALUE to token.corrected,
            ),
        )
        assertEquals("ABSTAIN", refused.getString("decision_state"))
        assertTrue(
            harness.state().facts.values.any { it.value == token.stable },
        )
        assertFalse(
            harness.state().facts.values.any { it.value == token.corrected },
        )
    }

    @Test
    fun `deleting a value leaves a marker and no restorable original`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("delete")
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.EPHEMERAL_VALUE to token.ephemeral,
                Role.STABLE_VALUE to token.stable,
            ),
        )
        val deletion = harness.step(
            Op.DELETE_FACT,
            "S1",
            mapOf(Role.PRESERVED_SCOPE to token.keepScope),
        )
        val after = harness.state()

        assertTrue(
            "a tombstone is recorded",
            deletion.getJSONArray("tombstone_ids").length() == 1,
        )
        assertFalse(
            "the deleted original is gone from state",
            after.encode().contains(token.ephemeral),
        )
        assertTrue(
            "the independent stored preference survives",
            after.facts.values.any { it.value == token.stable },
        )
        after.tombstones.values.forEach { tombstone ->
            assertFalse(
                "a marker must not carry the deleted value",
                tombstone.toJson().toString().contains(token.ephemeral),
            )
        }
    }

    @Test
    fun `a deleted value is not resurrected by a late redelivery but a newer instruction is accepted`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("resurrect")
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.EPHEMERAL_VALUE to token.ephemeral,
            ),
        )
        harness.step(Op.DELETE_FACT, "S1", mapOf(Role.PRESERVED_SCOPE to token.keepScope))

        val stale = harness.step(
            Op.DELIVER_OUT_OF_ORDER,
            "S1",
            mapOf(
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.EPHEMERAL_VALUE to token.ephemeral,
            ),
        )
        assertEquals("ABSTAIN", stale.getString("decision_state"))
        assertFalse(harness.state().encode().contains(token.ephemeral))

        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(9),
                Role.EPHEMERAL_VALUE to token.corrected,
            ),
        )
        assertTrue(
            "a strictly more current instruction is a new fact, not a restore",
            harness.state().facts.values.any { it.value == token.corrected },
        )
        assertFalse(harness.state().encode().contains(token.ephemeral))
    }

    @Test
    fun `repeating a decision request neither advances nor commits twice`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("idem")
        val goal = mapOf(Role.PRIMARY_GOAL to token.goal, Role.TARGET_ENTITY to token.target)
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            goal + mapOf(
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
            ),
        )
        val first = harness.step(Op.REQUEST_DECISION, "S1", goal)
        val second = harness.step(Op.REQUEST_DECISION, "S1", goal)

        assertEquals(first.getString("decision_state"), second.getString("decision_state"))
        assertEquals(
            "the same draft commits once",
            first.getJSONObject("action").getString("idempotency_key"),
            second.getJSONObject("action").getString("idempotency_key"),
        )
        assertEquals(1, harness.state().actions.size)
    }

    @Test
    fun `replaying an event returns the original action and commits nothing new`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("replay")
        val goal = mapOf(Role.PRIMARY_GOAL to token.goal, Role.TARGET_ENTITY to token.target)
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            goal + mapOf(
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
            ),
        )
        val decision = harness.step(Op.REQUEST_DECISION, "S1", goal)
        val actionCount = harness.state().actions.size

        val replay = harness.step(
            Op.REPLAY_EVENT,
            "S1",
            mapOf("REPLAY_OF_EVENT_ID" to harness.steps[2].eventId),
        )
        assertEquals("CONFIRMED_COMPLETE", replay.getString("decision_state"))
        assertEquals(
            decision.getJSONObject("action").getString("action_id"),
            replay.getJSONObject("action").getString("action_id"),
        )
        assertEquals(actionCount, harness.state().actions.size)
    }

    @Test
    fun `replaying an unknown event does not invent an action`() {
        val harness = ProbeRunHarness()
        harness.step(Op.RESET_AND_START, "S1")
        val replay = harness.step(
            Op.REPLAY_EVENT,
            "S1",
            mapOf("REPLAY_OF_EVENT_ID" to "EVENT-THAT-NEVER-HAPPENED"),
        )
        assertEquals("ABSTAIN", replay.getString("decision_state"))
        assertTrue(replay.isNull("action"))
    }

    @Test
    fun `network degradation holds the commit without damaging the draft`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("network")
        val goal = mapOf(Role.PRIMARY_GOAL to token.goal, Role.TARGET_ENTITY to token.target)
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            goal + mapOf(
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
            ),
        )
        val fieldId = AsprEngine.fieldIdFor(Ids.state("slot", token.keepScope))

        harness.step(Op.SET_NETWORK, "S1", mapOf("NETWORK_STATE" to "DELAYED"))
        val delayed = harness.step(Op.REQUEST_DECISION, "S1", goal)
        assertEquals("WAIT", delayed.getString("decision_state"))
        assertEquals(token.stable, harness.state().fields.getValue(fieldId).value)
        assertTrue("nothing is committed while waiting", harness.state().actions.isEmpty())

        harness.step(Op.SET_NETWORK, "S1", mapOf("NETWORK_STATE" to "ONLINE"))
        val online = harness.step(Op.REQUEST_DECISION, "S1", goal)
        assertEquals("ACT", online.getString("decision_state"))
        assertEquals(token.stable, harness.state().fields.getValue(fieldId).value)
    }

    @Test
    fun `an unreadable network value is reported as unknown rather than online`() {
        val harness = ProbeRunHarness()
        harness.step(Op.RESET_AND_START, "S1")
        val result = harness.step(Op.SET_NETWORK, "S1", mapOf("SOMETHING_ELSE" to "not-a-state"))
        assertEquals("UNKNOWN", result.getString("network_state"))
    }

    @Test
    fun `the network state is read from any role key`() {
        val harness = ProbeRunHarness()
        harness.step(Op.RESET_AND_START, "S1")
        val result = harness.step(
            Op.SET_NETWORK,
            "S1",
            mapOf("CONNECTIVITY_FOR_THIS_RUN" to "offline"),
        )
        assertEquals("OFFLINE", result.getString("network_state"))
    }

    @Test
    fun `offline with no cached catalog for the target stops instead of guessing`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("abstain")
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(Op.SET_NETWORK, "S1", mapOf("NETWORK_STATE" to "OFFLINE"))
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
            ),
        )
        val decision = harness.step(
            Op.REQUEST_DECISION,
            "S1",
            mapOf(Role.PRIMARY_GOAL to token.goal, Role.TARGET_ENTITY to token.target),
        )
        assertEquals("ABSTAIN", decision.getString("decision_state"))
        assertTrue(harness.state().actions.isEmpty())
    }

    @Test
    fun `a decision with nothing valid known asks instead of acting`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("empty")
        harness.step(Op.RESET_AND_START, "S1")
        val decision = harness.step(
            Op.REQUEST_DECISION,
            "S1",
            mapOf(Role.PRIMARY_GOAL to token.goal, Role.TARGET_ENTITY to token.target),
        )
        assertEquals("ASK", decision.getString("decision_state"))
        assertTrue(decision.isNull("action"))
    }

    @Test
    fun `a delayed outcome is folded in exactly once across time and restart`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("outcome")
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.DELAYED_OUTCOME to token.outcome,
            ),
        )
        assertTrue(
            "an outcome that has not arrived yet is only scheduled",
            harness.state().appliedOutcomes.isEmpty(),
        )

        harness.step(Op.ADVANCE_TIME, "S1", mapOf(Role.PRESERVED_SCOPE to token.keepScope))
        assertEquals(1, harness.state().appliedOutcomes.size)

        harness.relaunch()
        harness.step(Op.ADVANCE_TIME, "S1", mapOf(Role.DELAYED_OUTCOME to token.outcome))
        assertEquals(
            "the same outcome is never folded in twice",
            1,
            harness.state().appliedOutcomes.size,
        )
    }

    @Test
    fun `a delayed outcome changes what the next decision selects`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("late")
        val goal = mapOf(Role.PRIMARY_GOAL to token.goal, Role.TARGET_ENTITY to token.target)
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            goal + mapOf(
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
            ),
        )
        val beforeOutcome = harness.step(Op.REQUEST_DECISION, "S1", goal)
        harness.step(
            Op.ADVANCE_TIME,
            "S1",
            mapOf(Role.PRESERVED_SCOPE to token.sideScope, Role.DELAYED_OUTCOME to token.outcome),
        )
        val afterOutcome = harness.step(Op.REQUEST_DECISION, "S1", goal)

        assertTrue(
            "the late result adds context the earlier decision did not have",
            afterOutcome.getJSONArray("selected_context_ids").length() >
                beforeOutcome.getJSONArray("selected_context_ids").length(),
        )
        assertEquals(
            "a late proposal is asked about, never auto-applied",
            "ASK",
            afterOutcome.getString("decision_state"),
        )
    }

    @Test
    fun `a redelivered step is applied once`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("dupstep")
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
            ),
            stepId = "DUP",
            eventId = "EVENT-DUP",
        )
        val digestAfterFirst = harness.state().digest()
        val repeat = harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.corrected,
            ),
            stepId = "DUP2",
            eventId = "EVENT-DUP",
        )
        assertFalse(
            "a redelivered event never applies a second time",
            harness.state().facts.values.any { it.value == token.corrected },
        )
        assertNotNull(repeat.getString("decision_state"))
        assertTrue(harness.state().rejectedEvents.any { it.reason.contains("DUPLICATE") })
        assertTrue(digestAfterFirst.isNotEmpty())
    }

    @Test
    fun `a reset keeps the previous run for audit and starts clean`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("reset")
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
            ),
        )
        val firstRunId = harness.state().runId
        harness.step(Op.RESET_AND_START, "S2")
        val after = harness.state()

        assertTrue("active state is clean", after.facts.isEmpty())
        assertEquals(0, after.decisionRequests)
        assertEquals(0, after.modelInvocations)
        assertTrue(
            "the previous run is kept for audit",
            after.archive.any { it.runId == firstRunId },
        )
        assertTrue(after.archive.all { it.terminalDigest.length == 64 })
    }

    @Test
    fun `an export reports open work honestly`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("export")
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.DELAYED_OUTCOME to token.outcome,
            ),
        )
        harness.step(Op.ADVANCE_TIME, "S1", mapOf(Role.PRESERVED_SCOPE to token.keepScope))
        val export = harness.step(Op.EXPORT_AND_END, "S1")
        assertEquals(
            "an unconfirmed proposal is not reported as complete",
            "PENDING",
            export.getString("decision_state"),
        )
        assertTrue(harness.state().runTerminal)
    }

    @Test
    fun `the run reports no model invocations because the engine is deterministic`() {
        val (harness, _) = run()
        assertEquals(0, harness.state().modelInvocations)
        assertEquals(0, harness.core().cumulativeInvocations())
    }

    @Test
    fun `evidence documents are linked to real steps and carry no verdict`() {
        val (harness, _) = run()
        val evidence = harness.evidence()
        assertTrue(evidence.isNotEmpty())
        val stepIds = harness.steps.map { it.stepId }.toSet()
        evidence.forEach { (id, content) ->
            assertTrue("$id must match the submission id format", EVIDENCE_ID.matches(id))
            val linked = content.getJSONArray("step_ids").toStringList()
            assertTrue("$id must link to at least one step", linked.isNotEmpty())
            assertTrue("$id links unknown steps", linked.all { it in stepIds })
            val text = content.toString()
            FORBIDDEN_IN_OUTPUT.forEach { forbidden ->
                assertFalse("$id must not contain $forbidden", text.contains(forbidden))
            }
        }
    }

    @Test
    fun `step results carry no verdict fields`() {
        val (harness, _) = run()
        harness.results.forEach { result ->
            val text = result.toString()
            FORBIDDEN_IN_OUTPUT.forEach { forbidden ->
                assertFalse("result must not contain $forbidden", text.contains(forbidden))
            }
        }
    }

    @Test
    fun `a corrupt stored document reports recovery rather than a clean state`() {
        val store = InMemoryStateStore()
        store.save("{not json")
        val core = ProductionCore(store, "test-process-1", ProductionState.NAMESPACE_FULL, true, "R")
        val result = core.execute(
            ProbeStep(
                index = 1,
                stepId = "S01",
                eventId = "E-01",
                operation = Op.REQUEST_DECISION,
                sessionId = "S1",
                virtualTime = "2026-02-01T00:00:00Z",
                roles = JSONObject(),
            ),
        ).result
        assertEquals("FAILED", result.getString("decision_state"))
        assertNull(result.opt("action").takeIf { it != JSONObject.NULL })
    }

    private companion object {
        val EVIDENCE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

        /** Verdict vocabulary the app must never produce. */
        val FORBIDDEN_IN_OUTPUT = listOf(
            "PASS", "FAIL", "anchor", "CORE-1", "CORE-2", "CORE-3", "CORE-4", "CORE-5", "CORE-6",
            "q_total", "expected_relation", "qualification", "pool_cut",
        )
    }
}

internal fun org.json.JSONArray.toStringList(): List<String> =
    (0 until length()).map { getString(it) }
