package com.scpc.deliveryagent.core

import com.scpc.deliveryagent.core.ProductionCore.Op

/**
 * Run shapes used by the tests.
 *
 * These are written from the thirteen operation kinds, not copied from the
 * published rehearsal file, and every value is a locally generated opaque token.
 * The point of the variants is to show that the core answers relations, not a
 * remembered script.
 */
object ReferenceRuns {

    /**
     * One reference run that touches all thirteen operation kinds once, in the
     * order the public reference run uses.
     */
    fun thirteenOperations(harness: ProbeRunHarness, token: TokenSet) {
        val a = "SESSION-ALPHA"
        val b = "SESSION-BETA"
        val c = "SESSION-GAMMA"

        harness.step(Op.RESET_AND_START, a)
        harness.step(
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
        harness.step(
            Op.ADVANCE_SESSION,
            b,
            mapOf(
                Role.PRIMARY_GOAL to token.goal,
                Role.TARGET_ENTITY to token.target,
                Role.DISTRACTOR_ENTITY to token.distractor,
            ),
        )
        harness.step(
            Op.REQUEST_DECISION,
            b,
            mapOf(Role.PRIMARY_GOAL to token.goal, Role.TARGET_ENTITY to token.target),
        )
        harness.step(
            Op.CORRECT_FACT,
            b,
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(2),
                Role.ONE_OFF_VALUE to token.corrected,
            ),
        )
        harness.step(
            Op.REVOKE_SCOPE,
            b,
            mapOf(Role.PRESERVED_SCOPE to token.keepScope, Role.REVOKED_SCOPE to token.revokeScope),
        )
        harness.step(Op.DELETE_FACT, b, mapOf(Role.TARGET_ENTITY to token.target))
        harness.step(Op.SET_NETWORK, b, mapOf("NETWORK_STATE" to "OFFLINE"))
        harness.step(Op.PROCESS_KILL_RELAUNCH, c)
        harness.relaunch()
        harness.step(
            Op.REPLAY_EVENT,
            c,
            mapOf("REPLAY_OF_EVENT_ID" to harness.steps[3].eventId),
        )
        harness.step(
            Op.DELIVER_OUT_OF_ORDER,
            c,
            mapOf(
                Role.CURRENT_AUTHORITY to token.authority(2),
                "OLDER_EVENT_ID" to harness.steps[1].eventId,
            ),
        )
        harness.step(Op.ADVANCE_TIME, c, mapOf(Role.DELAYED_OUTCOME to token.outcome))
        harness.step(Op.EXPORT_AND_END, c)
    }

    /**
     * V3: valid order variation with repeated operations, a mid-run reset and
     * network transitions. Eighteen steps.
     */
    fun orderVariation(harness: ProbeRunHarness, token: TokenSet, second: TokenSet) {
        val a = "SESSION-1"
        val b = "SESSION-2"
        val c = "SESSION-3"
        val goal = mapOf(Role.PRIMARY_GOAL to token.goal, Role.TARGET_ENTITY to token.target)

        harness.step(Op.RESET_AND_START, a)
        harness.step(
            Op.UPSERT_FACT,
            a,
            goal + mapOf(
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
            ),
        )
        harness.step(Op.REQUEST_DECISION, a, goal)
        harness.step(Op.REQUEST_DECISION, a, goal)
        harness.step(Op.SET_NETWORK, a, mapOf("NETWORK_STATE" to "OFFLINE"))
        harness.step(Op.REQUEST_DECISION, a, goal)
        harness.step(Op.SET_NETWORK, a, mapOf("NETWORK_STATE" to "ONLINE"))
        harness.step(Op.REQUEST_DECISION, a, goal)
        harness.step(Op.ADVANCE_SESSION, b, goal)
        harness.step(
            Op.CORRECT_FACT,
            b,
            goal + mapOf(
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(2),
                Role.STABLE_VALUE to token.corrected,
            ),
        )
        harness.step(Op.REQUEST_DECISION, b, goal)
        harness.step(Op.REVOKE_SCOPE, b, mapOf(Role.REVOKED_SCOPE to token.keepScope))
        harness.step(Op.REQUEST_DECISION, b, goal)
        harness.step(Op.RESET_AND_START, c)
        harness.step(
            Op.UPSERT_FACT,
            c,
            mapOf(
                Role.PRIMARY_GOAL to second.goal,
                Role.TARGET_ENTITY to second.target,
                Role.PRESERVED_SCOPE to second.keepScope,
                Role.CURRENT_AUTHORITY to second.authority(1),
                Role.STABLE_VALUE to second.stable,
            ),
        )
        harness.step(
            Op.REQUEST_DECISION,
            c,
            mapOf(Role.PRIMARY_GOAL to second.goal, Role.TARGET_ENTITY to second.target),
        )
        harness.step(Op.PROCESS_KILL_RELAUNCH, c)
        harness.relaunch()
        harness.step(Op.EXPORT_AND_END, c)
    }

    /**
     * V4: long horizon. Repeated decisions, a delayed outcome, a higher catalog
     * version on one line, revocation, deletion, kill, duplicate and late
     * delivery. Twenty-six steps.
     */
    fun extendedHorizon(harness: ProbeRunHarness, token: TokenSet) {
        val a = "SESSION-A"
        val b = "SESSION-B"
        val goalA = mapOf(Role.PRIMARY_GOAL to token.goal, Role.TARGET_ENTITY to token.target)

        harness.step(Op.RESET_AND_START, a)
        harness.step(
            Op.UPSERT_FACT,
            a,
            goalA + mapOf(
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
                Role.ONE_OFF_VALUE to token.oneOff,
                Role.EPHEMERAL_VALUE to token.ephemeral,
                Role.DELAYED_OUTCOME to token.outcome,
            ),
        )
        harness.step(Op.REQUEST_DECISION, a, goalA)
        harness.step(Op.REQUEST_DECISION, a, goalA)
        harness.step(Op.ADVANCE_SESSION, b, goalA + mapOf(Role.DISTRACTOR_ENTITY to token.distractor))
        harness.step(Op.REQUEST_DECISION, b, goalA)
        harness.step(
            Op.CORRECT_FACT,
            b,
            goalA + mapOf(
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(2),
                Role.STABLE_VALUE to token.corrected,
            ),
        )
        harness.step(Op.REQUEST_DECISION, b, goalA)
        harness.step(Op.REVOKE_SCOPE, b, mapOf(Role.REVOKED_SCOPE to token.keepScope))
        harness.step(Op.REQUEST_DECISION, b, goalA)
        harness.step(Op.SET_NETWORK, b, mapOf("NETWORK_STATE" to "DELAYED"))
        harness.step(Op.REQUEST_DECISION, b, goalA)
        harness.step(Op.SET_NETWORK, b, mapOf("NETWORK_STATE" to "OFFLINE"))
        harness.step(Op.REQUEST_DECISION, b, goalA)
        harness.step(Op.SET_NETWORK, b, mapOf("NETWORK_STATE" to "ONLINE"))
        harness.step(Op.REQUEST_DECISION, b, goalA)
        harness.step(
            Op.UPSERT_FACT,
            b,
            mapOf(
                Role.TARGET_ENTITY to token.target,
                Role.PRESERVED_SCOPE to token.sideScope,
                Role.CURRENT_AUTHORITY to token.authority(3),
                Role.STABLE_VALUE to token.side,
            ),
        )
        harness.step(Op.REQUEST_DECISION, b, goalA)
        harness.step(Op.ADVANCE_TIME, b, mapOf(Role.PRESERVED_SCOPE to token.keepScope))
        harness.step(Op.REQUEST_DECISION, b, goalA)
        harness.step(Op.PROCESS_KILL_RELAUNCH, b)
        harness.relaunch()
        harness.step(Op.REQUEST_DECISION, b, goalA)
        harness.step(Op.REPLAY_EVENT, b, mapOf("REPLAY_OF_EVENT_ID" to harness.steps[2].eventId))
        harness.step(
            Op.DELIVER_OUT_OF_ORDER,
            b,
            mapOf(
                Role.PRESERVED_SCOPE to token.keepScope,
                Role.CURRENT_AUTHORITY to token.authority(1),
                Role.STABLE_VALUE to token.stable,
            ),
        )
        harness.step(
            Op.DELETE_FACT,
            b,
            mapOf(Role.PRESERVED_SCOPE to token.keepScope, Role.TARGET_ENTITY to token.target),
        )
        harness.step(Op.EXPORT_AND_END, b)
    }
}
