package com.scpc.deliveryagent.core

import com.scpc.deliveryagent.core.ProductionCore.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Metamorphic checks.
 *
 * The official run uses values, wording and ordering the public reference run does
 * not. These tests transform an input in ways that must not change the relations
 * the core reports, and check the relations rather than the identifiers.
 */
class MetamorphicProbeTest {

    // ------------------------------------------------------- V1 token rewrite

    @Test
    fun `rewriting every role token leaves the relations unchanged`() {
        val first = ProbeRunHarness()
        ReferenceRuns.thirteenOperations(first, TokenSet("alpha"))
        first.verifyShape()

        val second = ProbeRunHarness()
        ReferenceRuns.thirteenOperations(second, TokenSet("zzz-9x-omega"))
        second.verifyShape()

        assertEquals(
            "the structure of the run must not depend on how tokens are spelled",
            first.relationSignature(),
            second.relationSignature(),
        )
        assertEquals(first.decisions(), second.decisions())
        assertEquals(first.epochs(), second.epochs())
    }

    @Test
    fun `identifiers follow the tokens they were derived from`() {
        val token = TokenSet("derive")
        val harness = ProbeRunHarness()
        ReferenceRuns.thirteenOperations(harness, token)

        val encoded = harness.state().encode()
        assertTrue(
            "state must be addressable by the caller's own scope token",
            encoded.contains(Ids.segment(token.keepScope)),
        )
        // Nothing in the run may depend on the wording of the published reference
        // input or on a menu name.
        listOf("PUBLIC_", "PUBLIC-", "REHEARSAL", "닭곰탕", "수제비", "떡볶이").forEach { probe ->
            assertFalse(
                "state must not mention $probe",
                encoded.contains(probe),
            )
        }
    }

    @Test
    fun `the same run over a different session labelling keeps its relations`() {
        val token = TokenSet("relabel")
        val plain = ProbeRunHarness()
        ReferenceRuns.thirteenOperations(plain, token)

        // Same operations, same relations, entirely different session labels.
        val relabelled = ProbeRunHarness()
        val a = "phase-one"
        val b = "phase-two"
        val c = "phase-three"
        relabelled.step(Op.RESET_AND_START, a)
        relabelled.step(
            Op.UPSERT_FACT,
            a,
            mapOf(
                Role.PRIMARY_GOAL to token.goal,
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
                Role.ONE_OFF_VALUE to token.oneOff,
            ),
        )
        relabelled.step(
            Op.ADVANCE_SESSION,
            b,
            mapOf(
                Role.PRIMARY_GOAL to token.goal,
                Role.TARGET_ENTITY to token.target,
                Role.DISTRACTOR_ENTITY to token.distractor,
            ),
        )
        relabelled.step(
            Op.REQUEST_DECISION,
            b,
            mapOf(Role.PRIMARY_GOAL to token.goal, Role.TARGET_ENTITY to token.target),
        )
        assertEquals(
            plain.decisions().take(4),
            relabelled.decisions().take(4),
        )
    }

    // ---------------------------------------------------- V2 entity/goal swap

    @Test
    fun `a more recent memory bound to another restaurant is not reused`() {
        val harness = ProbeRunHarness()
        val shared = TokenSet("shared")
        val other = TokenSet("other")

        harness.step(Op.RESET_AND_START, "S1")
        // Option semantics the user allowed to be reused: named preserved scope,
        // not bound to one restaurant.
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.PRIMARY_GOAL to shared.goal,
                Role.TARGET_ENTITY to shared.target,
                Role.PRESERVED_SCOPE to shared.keepScope,
                Role.CURRENT_AUTHORITY to shared.authority(1),
                Role.STABLE_VALUE to shared.stable,
            ),
        )
        // A restaurant specific value, stored later and therefore more recent.
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.PRIMARY_GOAL to other.goal,
                Role.TARGET_ENTITY to other.distractor,
                Role.CURRENT_AUTHORITY to other.authority(9),
                Role.STABLE_VALUE to other.stable,
            ),
        )

        // Move to a new session, a new target and a new goal.
        harness.step(
            Op.ADVANCE_SESSION,
            "S2",
            mapOf(
                Role.PRIMARY_GOAL to other.goal,
                Role.TARGET_ENTITY to other.target,
                Role.DISTRACTOR_ENTITY to other.distractor,
            ),
        )
        val decision = harness.step(
            Op.REQUEST_DECISION,
            "S2",
            mapOf(Role.PRIMARY_GOAL to other.goal, Role.TARGET_ENTITY to other.target),
        )

        val state = harness.state()
        val selection = AsprEngine.select(state)
        val selected = selection.selectedFactIds()
        val recentOtherEntityFact = state.facts.values
            .first { it.value == other.stable }

        assertFalse(
            "recency must not beat relevance",
            selected.contains(recentOtherEntityFact.factId),
        )
        assertEquals(
            Relevance.DISTRACTOR,
            selection.excluded.getValue(recentOtherEntityFact.factId),
        )
        assertTrue(
            "the shared option semantics are still reused at the new target",
            selected.any { state.facts.getValue(it).value == shared.stable },
        )
        assertTrue(
            "the excluded memory is reported, not silently ignored",
            decision.getJSONArray("selected_context_ids").toStringList()
                .none { it == recentOtherEntityFact.factId },
        )
    }

    @Test
    fun `swapping which token is the target swaps which memory applies`() {
        fun runWith(target: String, distractor: String): Set<String> {
            val harness = ProbeRunHarness()
            val left = TokenSet("left")
            val right = TokenSet("right")
            harness.step(Op.RESET_AND_START, "S1")
            harness.step(
                Op.UPSERT_FACT,
                "S1",
                mapOf(
                    Role.TARGET_ENTITY to left.target,
                    Role.CURRENT_AUTHORITY to left.authority(1),
                    Role.STABLE_VALUE to left.stable,
                ),
            )
            harness.step(
                Op.UPSERT_FACT,
                "S1",
                mapOf(
                    Role.TARGET_ENTITY to right.target,
                    Role.CURRENT_AUTHORITY to right.authority(2),
                    Role.STABLE_VALUE to right.stable,
                ),
            )
            harness.step(
                Op.ADVANCE_SESSION,
                "S2",
                mapOf(Role.TARGET_ENTITY to target, Role.DISTRACTOR_ENTITY to distractor),
            )
            harness.step(Op.REQUEST_DECISION, "S2", mapOf(Role.TARGET_ENTITY to target))
            val state = harness.state()
            return AsprEngine.select(state).selectedFactIds()
                .map { state.facts.getValue(it).value }
                .toSet()
        }

        val left = TokenSet("left")
        val right = TokenSet("right")
        val withLeftTarget = runWith(left.target, right.target)
        val withRightTarget = runWith(right.target, left.target)

        assertTrue(withLeftTarget.contains(left.stable))
        assertFalse(withLeftTarget.contains(right.stable))
        assertTrue(withRightTarget.contains(right.stable))
        assertFalse(withRightTarget.contains(left.stable))
    }

    // --------------------------------------- V3 order variation and repetition

    @Test
    fun `repeated operations, a mid-run reset and network changes are handled`() {
        val harness = ProbeRunHarness()
        ReferenceRuns.orderVariation(harness, TokenSet("v3a"), TokenSet("v3b"))
        harness.verifyShape()
        assertEquals(18, harness.results.size)

        val decisions = harness.decisions()
        // Steps three and four are the same decision request with no new facts.
        assertEquals(decisions[2], decisions[3])
        // Step six is the same request while offline.
        assertEquals("WAIT", decisions[5])
        // Step eight is the same request after the network came back.
        assertEquals(decisions[2], decisions[7])

        val state = harness.state()
        assertTrue(
            "the first run is kept for audit after the mid-run reset",
            state.archive.isNotEmpty(),
        )
        assertTrue(
            "the second run's own state is present",
            state.facts.values.any { it.value == TokenSet("v3b").stable },
        )
        assertFalse(
            "the first run's active state is gone",
            state.facts.values.any { it.value == TokenSet("v3a").stable },
        )
        assertTrue(
            "the epoch increases after the kill at the end",
            harness.epochs().last() > harness.epochs().first(),
        )
    }

    @Test
    fun `a revocation and a correction behave the same wherever they appear`() {
        fun signatureFor(revokeFirst: Boolean): List<String> {
            val harness = ProbeRunHarness()
            val token = TokenSet("order")
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
            val correction = {
                harness.step(
                    Op.CORRECT_FACT,
                    "S1",
                    mapOf(
                        Role.PRESERVED_SCOPE to token.keepScope,
                        Role.CURRENT_AUTHORITY to token.authority(2),
                        Role.STABLE_VALUE to token.corrected,
                    ),
                )
            }
            val revocation = {
                harness.step(Op.REVOKE_SCOPE, "S1", mapOf(Role.REVOKED_SCOPE to token.keepScope))
            }
            if (revokeFirst) {
                revocation()
                correction()
            } else {
                correction()
                revocation()
            }
            harness.step(Op.REQUEST_DECISION, "S1", goal)
            val state = harness.state()
            val fieldId = AsprEngine.fieldIdFor(Ids.state("slot", token.keepScope))
            val field = state.fields.getValue(fieldId)
            return listOf(
                field.status.name,
                field.value ?: "null",
                state.revokedScopes.size.toString(),
                state.facts.getValue(field.factId!!).authorityVersion.toString(),
            )
        }

        assertEquals(
            "the outcome of a revocation plus a correction does not depend on their order",
            signatureFor(revokeFirst = true),
            signatureFor(revokeFirst = false),
        )
    }

    // ------------------------------------------------- V4 long horizon linkage

    @Test
    fun `a long run links reuse, revocation, stock change, restart and late delivery`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("v4")
        ReferenceRuns.extendedHorizon(harness, token)
        harness.verifyShape()
        assertEquals(26, harness.results.size)

        val state = harness.state()
        val decisions = harness.decisions()

        assertEquals(
            "a repeated decision with no new facts returns the same relation",
            decisions[2],
            decisions[3],
        )
        // A question can always be asked, even with no network, so an open
        // confirmation still yields ASK. What must never happen is acting or
        // committing while the current catalog cannot be trusted.
        listOf(11, 13).forEach { index ->
            assertFalse(
                "step ${index + 1} must not act while the network is degraded",
                decisions[index] == "ACT",
            )
            assertTrue(
                "step ${index + 1} must not commit while the network is degraded",
                harness.results[index].isNull("action"),
            )
        }
        assertTrue(
            "the epoch increases after the real kill",
            harness.epochs().last() > harness.epochs().first(),
        )

        val replayIndex = harness.steps.indexOfFirst { it.operation == Op.REPLAY_EVENT }
        val actionsBeforeReplay = harness.results.take(replayIndex)
            .mapNotNull { if (it.isNull("action")) null else it.getJSONObject("action").getString("idempotency_key") }
            .toSet()
        val replayAction = harness.results[replayIndex]
        if (!replayAction.isNull("action")) {
            assertTrue(
                "a replay reuses an existing commit identity",
                replayAction.getJSONObject("action").getString("idempotency_key") in actionsBeforeReplay,
            )
        }

        val lateIndex = harness.steps.indexOfFirst { it.operation == Op.DELIVER_OUT_OF_ORDER }
        assertEquals(
            "a late event carrying an older instruction is refused",
            "ABSTAIN",
            harness.results[lateIndex].getString("decision_state"),
        )
        assertTrue(
            "the current value survives the late event",
            state.facts.values.none { it.value == token.stable && it.kind == FactKind.STABLE } ||
                state.facts.values.any { it.value == token.corrected },
        )

        assertEquals(
            "the delayed result was folded in exactly once",
            1,
            state.appliedOutcomes.size,
        )
        assertFalse(
            "the deleted one-time request is gone from state",
            state.encode().contains(token.ephemeral),
        )
        assertTrue("a deletion marker remains", state.tombstones.isNotEmpty())
        assertTrue(
            "the run ends in a terminal state",
            state.runTerminal,
        )
    }

    @Test
    fun `a higher version on one line invalidates only that line and the total`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("stock")
        val goal = mapOf(Role.PRIMARY_GOAL to token.goal, Role.TARGET_ENTITY to token.target)
        val mainField = AsprEngine.fieldIdFor(Ids.state("slot", token.target))
        val sideField = AsprEngine.fieldIdFor(Ids.state("slot", token.sideScope))
        val totalField = AsprEngine.fieldIdFor(AsprEngine.SLOT_TOTAL)

        harness.step(Op.RESET_AND_START, "S1")
        // A restaurant specific line: no reuse scope is named, so it is proposed
        // rather than auto-applied.
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
            ),
        )
        val asked = harness.step(Op.REQUEST_DECISION, "S1", goal)
        assertEquals("ASK", asked.getString("decision_state"))
        assertTrue(
            "the proposed line is the open question",
            asked.getJSONArray("selected_context_ids").length() > 0,
        )
        // The user answers that question, which makes the main line their own choice.
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.CURRENT_AUTHORITY to token.authority(2),
                Role.STABLE_VALUE to token.corrected,
            ),
        )
        assertEquals(
            FieldStatus.CONFIRMED,
            harness.state().fields.getValue(mainField).status,
        )
        val mainBefore = harness.state().fields.getValue(mainField).value

        // A second line is added, then goes out of stock at a higher version.
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.sideScope,
                Role.CURRENT_AUTHORITY to token.authority(3),
                Role.STABLE_VALUE to token.side,
            ),
        )
        harness.step(Op.REQUEST_DECISION, "S1", goal)
        val totalBefore = harness.state().fields.getValue(totalField).value
        val change = harness.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.sideScope,
                Role.CURRENT_AUTHORITY to token.authority(4),
                Role.STABLE_VALUE to token.sideSoldOut,
            ),
        )
        val after = harness.state()

        assertEquals(
            "the untouched main line keeps the value the user chose",
            mainBefore,
            after.fields.getValue(mainField).value,
        )
        assertEquals(
            FieldStatus.CONFIRMED,
            after.fields.getValue(mainField).status,
        )
        assertEquals(
            "the changed line takes the newer value",
            token.sideSoldOut,
            after.fields.getValue(sideField).value,
        )
        assertTrue(
            "the changed line is reported as invalidated",
            change.getJSONArray("invalidated_state_ids").toStringList().contains(sideField),
        )
        assertTrue(
            "the main line is reported as preserved",
            change.getJSONArray("preserved_state_ids").toStringList().contains(mainField),
        )
        assertTrue(
            "the total is recomputed",
            after.fields.getValue(totalField).value != totalBefore,
        )
    }

    @Test
    fun `state survives a restart and never reports uncommitted work as complete`() {
        val harness = ProbeRunHarness()
        val token = TokenSet("restart")
        val goal = mapOf(Role.PRIMARY_GOAL to token.goal, Role.TARGET_ENTITY to token.target)
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            goal + mapOf(
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
                Role.DELAYED_OUTCOME to token.outcome,
            ),
        )
        harness.step(Op.ADVANCE_TIME, "S1", mapOf(Role.PRESERVED_SCOPE to token.sideScope))
        val kill = harness.step(Op.PROCESS_KILL_RELAUNCH, "S1")
        assertEquals(
            "an unconfirmed proposal is pending, not complete",
            "PENDING",
            kill.getString("decision_state"),
        )

        val digestBefore = harness.state().digest()
        harness.relaunch()
        val afterRestart = harness.step(Op.REQUEST_DECISION, "S1", goal)

        assertTrue(
            "the stored preference survived the restart",
            harness.state().facts.values.any { it.value == token.stable },
        )
        assertEquals(
            "the late result is not applied a second time after the restart",
            1,
            harness.state().appliedOutcomes.size,
        )
        assertEquals(
            "the proposal still needs the user after the restart",
            "ASK",
            afterRestart.getString("decision_state"),
        )
        assertTrue(digestBefore.length == 64)
    }
}
