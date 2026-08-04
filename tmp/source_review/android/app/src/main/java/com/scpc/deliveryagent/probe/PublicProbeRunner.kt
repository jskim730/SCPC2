package com.scpc.deliveryagent.probe

import android.content.Context
import com.scpc.deliveryagent.core.Canonical
import com.scpc.deliveryagent.core.Digest
import com.scpc.deliveryagent.core.ProbeResultShape
import com.scpc.deliveryagent.core.ProbeStep
import com.scpc.deliveryagent.platform.ReleaseIdentity
import org.json.JSONArray
import org.json.JSONObject
import org.scpc.r2.probe.ProbeContract
import org.scpc.r2.probe.ProbeInputDocument
import org.scpc.r2.probe.ProbeInputParser
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.ProbeRunContext

/**
 * Public rehearsal execution behind `SCPC_PROBE_IMPORT` / `SCPC_PROBE_RUN` /
 * `SCPC_PROBE_EXPORT`.
 *
 * The adapter instance is resolved from the manifest metadata key the starter AAR
 * itself reads, so this screen drives exactly the adapter and production core the
 * protected component drives. Input parsing uses the starter AAR's own parser.
 *
 * The submission artifact `PUBLIC_PROBE_RESULT.json` is the one the official
 * Runner writes; this screen's export is the participant-visible record of the
 * same run, assembled from the same step results. It contains no score, expected
 * relation, anchor or Q value.
 */
object PublicProbeRunner {

    const val ARTIFACT_KIND = "scpc_probe_result"
    private const val PUBLIC_ASSIGNMENT = "PUBLIC_REHEARSAL_LOCAL_NOT_OFFICIAL"

    class ImportedInput(
        val document: ProbeInputDocument,
        val inputSha256: String,
        val stepCount: Int,
    )

    /** Parses and validates a public `PROBE_INPUT.json` with the official parser. */
    fun import(bytes: ByteArray): ImportedInput {
        require(bytes.size <= ProbeContract.MAX_INPUT_BYTES) {
            "input larger than the contract limit of ${ProbeContract.MAX_INPUT_BYTES} bytes"
        }
        val document = ProbeInputParser.parsePublic(bytes.toString(Charsets.UTF_8))
        return ImportedInput(document, Digest.bytes(bytes), document.steps.size)
    }

    /**
     * Runs every imported step through the production core and assembles a result
     * with no score in it.
     */
    fun run(context: Context, input: ImportedInput): JSONObject {
        val adapter = ReleaseIdentity.registeredAdapter(context)
        val document = input.document
        val runId = "public-" + input.inputSha256.take(24)
        val attestation = document.releaseAttestationId
        val runContext = ProbeRunContext(
            PUBLIC_ASSIGNMENT,
            document.candidateId,
            document.instanceId,
            runId,
            document.probePackId,
            document.missionId,
            attestation,
        )

        val runtimeStart = ReleaseIdentity.runtimeIdentity(context, adapter, attestation)
        adapter.onRunStarted(context, runContext)

        val stepResults = document.steps.map { step ->
            val result = adapter.executeStep(context, runContext, step)
            // The same shape contract the protected path enforces, so a public run
            // cannot pass with a result the official run would reject.
            ProbeResultShape.require(step.toCoreStep(), result)
        }
        val evidenceIds = adapter.onRunFinished(context, runContext)
        val runtimeEnd = ReleaseIdentity.runtimeIdentity(context, adapter, attestation)

        ProbeResultShape.requireRun(document.steps.map { it.toCoreStep() }, stepResults)
        ProbeResultShape.requireEvidenceClosure(stepResults, evidenceIds)

        val result = JSONObject()
            .put("schema_version", ProbeContract.CONTRACT_VERSION)
            .put("artifact_kind", ARTIFACT_KIND)
            .put("probe_contract_profile", ProbeContract.PROFILE)
            .put("probe_pack_id", document.probePackId)
            .put("instance_id", document.instanceId)
            .put("request_nonce", document.requestNonce)
            .put("candidate_id", document.candidateId)
            .put("mission_id", document.missionId)
            .put("operator_run_token", document.operatorRunToken)
            .put("run_id", runId)
            .put("probe_input_sha256", input.inputSha256)
            .put("mission_adapter_sha256", ReleaseIdentity.missionAdapterSha256(context))
            .put("release_binding", ReleaseIdentity.releaseBinding(context, attestation))
            .put("runtime_identity_start", runtimeStart)
            .put("runtime_identity_end", runtimeEnd)
            .put("step_results", JSONArray(stepResults))
            .put("evidence_ids", JSONArray(evidenceIds))

        return result.put(
            "result_digest",
            Digest.utf8(Canonical.encode(result)),
        )
    }
}

/** Translation only: contract step type to the core's own step type. */
internal fun ProbeInputStep.toCoreStep(): ProbeStep = ProbeStep(
    index = index,
    stepId = stepId,
    eventId = eventId,
    operation = operation,
    sessionId = sessionId,
    virtualTime = virtualTime,
    roles = roles,
)
