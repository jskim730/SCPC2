package com.scpc.deliveryagent.core

import com.scpc.deliveryagent.core.ProductionCore.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paired comparison between the `full` arm and the `claim-off` baseline.
 *
 * Both arms run the same script through the same code, with the same synthetic
 * input and the same shared infrastructure. Only the Signature mechanism differs.
 * The measured quantity is VIL: how many field resolutions the user still has to
 * supply before the draft is valid.
 *
 * A lower VIL only counts if no safety guardrail was broken, so both are asserted
 * together.
 */
class ClaimOffComparisonTest {

    private class ArmResult(
        val harness: ProbeRunHarness,
        val openAfterReuse: Int,
        val reuseDecision: String,
        val openAfterLineChange: Int,
        val answersAtReuse: Int,
        val answersAfterLineChange: Int,
    ) {
        /**
         * VIL: how many field resolutions the user had to supply before the draft
         * was valid, including the one final confirmation each arm needs.
         */
        val validInteractionLoad: Int get() = harness.state().resolutions.size
    }

    /**
     * Answers whatever the agent is still asking about, one field at a time, until
     * it can act. Each answer is one field-resolution event, which is what VIL
     * counts. The loop is bounded so a run that cannot converge fails the test
     * rather than hanging.
     */
    private fun answerUntilValid(
        harness: ProbeRunHarness,
        token: TokenSet,
        session: String,
        goal: Map<String, String>,
        entity: String,
    ): Int {
        val scopes = mapOf(
            AsprEngine.fieldIdFor(Ids.state("slot", token.keepScope)) to token.keepScope,
            AsprEngine.fieldIdFor(Ids.state("slot", token.sideScope)) to token.sideScope,
        )
        var answers = 0
        var decision = harness.last().getString("decision_state")
        var authority = 100
        while (decision == "ASK" && answers < 12) {
            val open = AsprEngine.openConfirmationIds(harness.state())
            val fieldId = open.firstOrNull() ?: break
            val scopeToken = scopes[fieldId] ?: break
            harness.step(
                Op.UPSERT_FACT,
                session,
                mapOf(
                    Role.TARGET_ENTITY to entity,
                    Role.PRESERVED_SCOPE to scopeToken,
                    Role.CURRENT_AUTHORITY to token.authority(authority++),
                    Role.STABLE_VALUE to "answer-$fieldId",
                ),
            )
            answers += 1
            decision = harness.step(Op.REQUEST_DECISION, session, goal).getString("decision_state")
        }
        return answers
    }

    /**
     * Learn a preference in one session, reuse it at another target in the next
     * session, then change one line of the draft. Identical script for both arms.
     */
    private fun runScript(asprEnabled: Boolean): ArmResult {
        val token = TokenSet("cmp")
        val harness = ProbeRunHarness(
            namespace = if (asprEnabled) {
                ProductionState.NAMESPACE_FULL
            } else {
                ProductionState.NAMESPACE_CLAIM_OFF
            },
            asprEnabled = asprEnabled,
        )
        val firstTarget = mapOf(
            Role.PRIMARY_GOAL to token.goal,
            Role.TARGET_ENTITY to token.target,
        )
        val secondTarget = mapOf(
            Role.PRIMARY_GOAL to token.goal,
            Role.TARGET_ENTITY to token.distractor,
        )

        harness.step(Op.RESET_AND_START, "ORDER-1")
        // The user stores two option values and allows them to be applied
        // automatically inside their own scopes.
        harness.step(
            Op.UPSERT_FACT,
            "ORDER-1",
            firstTarget + mapOf(
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
            ),
        )
        harness.step(
            Op.UPSERT_FACT,
            "ORDER-1",
            firstTarget + mapOf(
                Role.PRESERVED_SCOPE to token.sideScope,
                Role.CURRENT_AUTHORITY to token.authority(2),
                Role.STABLE_VALUE to token.side,
            ),
        )
        harness.step(Op.REQUEST_DECISION, "ORDER-1", firstTarget)
        answerUntilValid(harness, token, "ORDER-1", firstTarget, token.target)

        // Next order session at a different restaurant: this is the reuse point.
        harness.step(Op.ADVANCE_SESSION, "ORDER-2", secondTarget)
        val reuse = harness.step(Op.REQUEST_DECISION, "ORDER-2", secondTarget)
        val openAfterReuse = AsprEngine.openConfirmationIds(harness.state()).size
        val answersAtReuse =
            answerUntilValid(harness, token, "ORDER-2", secondTarget, token.distractor)

        // One line changes at a higher version. Everything else is untouched.
        harness.step(
            Op.UPSERT_FACT,
            "ORDER-2",
            mapOf(
                Role.TARGET_ENTITY to token.distractor,
                Role.PRESERVED_SCOPE to token.sideScope,
                Role.CURRENT_AUTHORITY to token.authority(50),
                Role.STABLE_VALUE to token.sideSoldOut,
            ),
        )
        harness.step(Op.REQUEST_DECISION, "ORDER-2", secondTarget)
        val openAfterChange = AsprEngine.openConfirmationIds(harness.state()).size
        val answersAfterChange =
            answerUntilValid(harness, token, "ORDER-2", secondTarget, token.distractor)
        harness.step(Op.EXPORT_AND_END, "ORDER-2")

        return ArmResult(
            harness = harness,
            openAfterReuse = openAfterReuse,
            reuseDecision = reuse.getString("decision_state"),
            openAfterLineChange = openAfterChange,
            answersAtReuse = answersAtReuse,
            answersAfterLineChange = answersAfterChange,
        )
    }

    @Test
    fun `both arms produce a well formed run`() {
        runScript(asprEnabled = true).harness.verifyShape()
        runScript(asprEnabled = false).harness.verifyShape()
    }

    @Test
    fun `the mechanism lowers the interaction load at a new target`() {
        val full = runScript(asprEnabled = true)
        val claimOff = runScript(asprEnabled = false)

        assertEquals(
            "with the mechanism on, valid stored options are reused at the new target",
            "ACT",
            full.reuseDecision,
        )
        assertEquals(0, full.openAfterReuse)
        assertEquals(
            "the baseline cannot tell whether stored values still apply, so it asks",
            "ASK",
            claimOff.reuseDecision,
        )
        assertTrue(
            "the baseline has to ask about the values it could not classify",
            claimOff.openAfterReuse > full.openAfterReuse,
        )
        assertEquals(
            "with the mechanism on the user supplies nothing again at the new target",
            0,
            full.answersAtReuse,
        )
        assertTrue(
            "the baseline needs the user to supply values again",
            claimOff.answersAtReuse > full.answersAtReuse,
        )
        assertTrue(
            "the interaction load to a valid draft is lower with the mechanism on: " +
                "full=${full.validInteractionLoad} claim-off=${claimOff.validInteractionLoad}",
            full.validInteractionLoad < claimOff.validInteractionLoad,
        )
    }

    @Test
    fun `the mechanism confines a line change to that line`() {
        val full = runScript(asprEnabled = true)
        val claimOff = runScript(asprEnabled = false)

        assertTrue(
            "with dependency edges, only the changed line and its roll-up re-open",
            full.openAfterLineChange <= claimOff.openAfterLineChange,
        )
        assertTrue(
            "without dependency edges more of the draft has to be supplied again: " +
                "full=${full.answersAfterLineChange} claim-off=${claimOff.answersAfterLineChange}",
            full.answersAfterLineChange <= claimOff.answersAfterLineChange,
        )
        assertEquals(
            "both arms end on a valid draft, so the loads are comparable",
            "ACT",
            full.harness.results.last { it.getString("operation") == Op.REQUEST_DECISION }
                .getString("decision_state"),
        )
        assertEquals(
            "ACT",
            claimOff.harness.results.last { it.getString("operation") == Op.REQUEST_DECISION }
                .getString("decision_state"),
        )
    }

    @Test
    fun `neither arm breaks a safety guardrail`() {
        listOf(true, false).forEach { aspr ->
            val token = TokenSet("guard")
            val harness = ProbeRunHarness(
                namespace = if (aspr) {
                    ProductionState.NAMESPACE_FULL
                } else {
                    ProductionState.NAMESPACE_CLAIM_OFF
                },
                asprEnabled = aspr,
            )
            val goal = mapOf(Role.PRIMARY_GOAL to token.goal, Role.TARGET_ENTITY to token.target)
            harness.step(Op.RESET_AND_START, "S1")
            harness.step(
                Op.UPSERT_FACT,
                "S1",
                goal + mapOf(
                    Role.PRESERVED_SCOPE to token.keepScope,
                    Role.CURRENT_AUTHORITY to token.authority(1),
                    Role.STABLE_VALUE to token.stable,
                    Role.EPHEMERAL_VALUE to token.ephemeral,
                ),
            )
            harness.step(Op.REQUEST_DECISION, "S1", goal)
            harness.step(Op.REVOKE_SCOPE, "S1", mapOf(Role.REVOKED_SCOPE to token.keepScope))
            harness.step(
                Op.DELETE_FACT,
                "S1",
                mapOf(Role.PRESERVED_SCOPE to token.keepScope),
            )
            harness.relaunch()
            harness.step(Op.REQUEST_DECISION, "S1", goal)
            val state = harness.state()

            assertFalse(
                "arm aspr=$aspr must not resurrect a deleted original",
                state.encode().contains(token.ephemeral),
            )
            assertTrue(
                "arm aspr=$aspr must keep a deletion marker",
                state.tombstones.isNotEmpty(),
            )
            state.fields.values.forEach { field ->
                if (field.status == FieldStatus.AUTO_APPLIED) {
                    val revokedRef = field.deps.firstOrNull { dep ->
                        dep.kind == DepKind.PERMISSION && dep.ref in state.revokedScopes
                    }
                    assertEquals(
                        "arm aspr=$aspr auto-applied a value from a revoked scope",
                        null,
                        revokedRef,
                    )
                }
            }
            assertEquals(
                "arm aspr=$aspr must never commit the same action twice",
                state.actions.values.map { it.idempotencyKey }.toSet().size,
                state.actions.size,
            )
        }
    }

    @Test
    fun `the baseline asks about a condition from an earlier session instead of reusing it`() {
        val token = TokenSet("oneoff")
        val harness = ProbeRunHarness(
            namespace = ProductionState.NAMESPACE_CLAIM_OFF,
            asprEnabled = false,
        )
        val goal = mapOf(Role.PRIMARY_GOAL to token.goal, Role.TARGET_ENTITY to token.target)
        harness.step(Op.RESET_AND_START, "S1")
        harness.step(
            Op.UPSERT_FACT,
            "S1",
            goal + mapOf(
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.ONE_OFF_VALUE to token.oneOff,
            ),
        )
        harness.step(Op.ADVANCE_SESSION, "S2", goal)
        val decision = harness.step(Op.REQUEST_DECISION, "S2", goal)

        assertEquals(
            "the baseline does not silently carry a previous order's condition forward",
            "ASK",
            decision.getString("decision_state"),
        )
        val fieldId = AsprEngine.fieldIdFor(Ids.state("slot", token.keepScope))
        assertEquals(
            FieldStatus.NEEDS_CONFIRMATION,
            harness.state().fields.getValue(fieldId).status,
        )
    }

    @Test
    fun `the two arms keep completely separate state`() {
        val token = TokenSet("iso")
        val full = ProbeRunHarness(ProductionState.NAMESPACE_FULL, asprEnabled = true)
        val claimOff = ProbeRunHarness(ProductionState.NAMESPACE_CLAIM_OFF, asprEnabled = false)

        full.step(Op.RESET_AND_START, "S1")
        full.step(
            Op.UPSERT_FACT,
            "S1",
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
            ),
        )
        claimOff.step(Op.RESET_AND_START, "S1")

        assertTrue(full.state().facts.isNotEmpty())
        assertTrue(
            "a save in one arm must not be visible in the other",
            claimOff.state().facts.isEmpty(),
        )
        assertNotEquals(full.state().namespace, claimOff.state().namespace)
        assertNotEquals(full.state().digest(), claimOff.state().digest())
    }
}
