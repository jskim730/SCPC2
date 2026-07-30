package org.scpc.r2.sample

import android.content.Context
import org.json.JSONObject
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeAdapter
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.ProbeRunContext
import org.scpc.r2.probe.ProbeRuntimeState
import java.util.UUID

class SampleProbeAdapter : ProbeAdapter {
    override fun runtimeState(context: Context): ProbeRuntimeState =
        ProbeRuntimeState(
            modelConfigured = false,
            // The sample is deterministic and does not call an AI model or
            // remote inference backend. Decision requests are tracked
            // separately in product state and must not be reported as model
            // invocations.
            cumulativeInvocations = 0,
        )

    override fun onRunStarted(context: Context, run: ProbeRunContext) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString("active_run_id", run.runId)
            .commit()
    }

    override fun executeStep(
        context: Context,
        run: ProbeRunContext,
        step: ProbeInputStep,
    ): JSONObject = core(context).execute(step)

    override fun onRunFinished(context: Context, run: ProbeRunContext): List<String> =
        core(context).evidenceIds()

    override fun onRunAborted(context: Context, run: ProbeRunContext, reason: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString("last_abort_reason", reason)
            .remove("active_run_id")
            .commit()
    }

    private fun core(context: Context): SampleCore = SampleCore(
        AndroidSampleStateStore(context),
        PROCESS_MARKER,
    )

    companion object {
        private const val PREFERENCES = "scpc-sample-core"
        private val PROCESS_MARKER = UUID.randomUUID().toString()
    }
}

private class AndroidSampleStateStore(context: Context) : SampleStateStore {
    private val preferences =
        context.getSharedPreferences("scpc-sample-core", Context.MODE_PRIVATE)

    override fun load(): JSONObject =
        preferences.getString("production_state_json", null)?.let(::JSONObject) ?: JSONObject()

    override fun save(state: JSONObject) {
        check(
            preferences.edit()
                .putString("production_state_json", CanonicalJson.encode(state))
                .commit(),
        ) { "cannot persist sample production state" }
    }
}
