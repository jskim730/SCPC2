package com.scpc.deliveryagent.probe

import android.content.Context
import com.scpc.deliveryagent.core.ProbeStep
import com.scpc.deliveryagent.core.ProductionCore
import com.scpc.deliveryagent.platform.EvidenceWriter
import com.scpc.deliveryagent.platform.Production
import org.json.JSONObject
import org.scpc.r2.probe.ProbeAdapter
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.ProbeRunContext
import org.scpc.r2.probe.ProbeRuntimeState

/**
 * The candidate adapter.
 *
 * It only translates the contract types into the core's own step type and hands
 * every operation to the same production repository, decision engine and ledger
 * the product screens use. There is no probe-only state machine, no mock store
 * and no branch on pack id, surface nonce or role spelling. Release binding,
 * runtime identity and the mission adapter digest are left to the starter AAR,
 * which reads them from the installed APK.
 */
class ProductionProbeAdapter : ProbeAdapter {

    override fun runtimeState(context: Context): ProbeRuntimeState = ProbeRuntimeState(
        // The decision engine is deterministic: no model and no remote inference
        // backend is configured in the submitted release.
        modelConfigured = false,
        cumulativeInvocations = core(context).cumulativeInvocations(),
    )

    override fun onRunStarted(context: Context, run: ProbeRunContext) {
        EvidenceWriter(context, Production.selectedArm(context), run.runId).clear()
        ProbeRunLog.begin(context, run)
    }

    override fun executeStep(
        context: Context,
        run: ProbeRunContext,
        step: ProbeInputStep,
    ): JSONObject {
        val outcome = core(context, run.runId).execute(step.toCoreStep())
        EvidenceWriter(context, Production.selectedArm(context), run.runId).write(outcome.evidence)
        return outcome.result
    }

    override fun onRunFinished(context: Context, run: ProbeRunContext): List<String> =
        core(context, run.runId).evidenceIds()

    override fun onRunAborted(context: Context, run: ProbeRunContext, reason: String) {
        // An aborted run changes no score and invents no result. It is recorded so
        // the next launch can show honestly that the run did not finish.
        ProbeRunLog.abort(context, run, reason)
    }

    private fun core(context: Context, runBinding: String? = null): ProductionCore =
        Production.core(context, runBinding)
}

/** Minimal run bookkeeping shown on the public Probe screen. */
object ProbeRunLog {

    private const val PREFERENCES = "scpc-probe-runs"
    private const val KEY_ACTIVE = "active_run_id"
    private const val KEY_LAST_ABORT = "last_abort_reason"

    fun begin(context: Context, run: ProbeRunContext) {
        preferences(context).edit()
            .putString(KEY_ACTIVE, run.runId)
            .remove(KEY_LAST_ABORT)
            .commit()
    }

    fun abort(context: Context, run: ProbeRunContext, reason: String) {
        preferences(context).edit()
            .putString(KEY_LAST_ABORT, "${run.runId}: $reason")
            .remove(KEY_ACTIVE)
            .commit()
    }

    fun activeRunId(context: Context): String? =
        preferences(context).getString(KEY_ACTIVE, null)

    fun lastAbortReason(context: Context): String? =
        preferences(context).getString(KEY_LAST_ABORT, null)

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
