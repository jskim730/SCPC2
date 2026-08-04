package com.scpc.deliveryagent.probe

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scpc.deliveryagent.R
import com.scpc.deliveryagent.core.AsprEngine
import com.scpc.deliveryagent.core.Canonical
import com.scpc.deliveryagent.core.Digest
import com.scpc.deliveryagent.core.FieldStatus
import com.scpc.deliveryagent.core.Ids
import com.scpc.deliveryagent.core.ProbeResultShape
import com.scpc.deliveryagent.core.ProbeStep
import com.scpc.deliveryagent.core.ProductionCore
import com.scpc.deliveryagent.core.Role
import com.scpc.deliveryagent.delivery.Slots
import com.scpc.deliveryagent.platform.Arm
import com.scpc.deliveryagent.platform.AndroidStateStore
import com.scpc.deliveryagent.platform.ConversationStore
import com.scpc.deliveryagent.platform.Production
import com.scpc.deliveryagent.platform.ReleaseIdentity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.ProbeRunContext

/**
 * Production parity.
 *
 * Shows on a real device that the public rehearsal entry point and the protected
 * Probe entry point resolve the same candidate adapter, and that the adapter reads
 * and writes the same production state the product screens read and write. There
 * is no probe-only repository and no second decision path.
 */
@RunWith(AndroidJUnit4::class)
class ProbeParityTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun startFromCleanState() {
        Production.selectArm(context, Arm.FULL)
        Production.resetAll(context)
    }

    @Test
    fun publicAndProtectedPathsResolveTheSameAdapter() {
        // This is the same manifest metadata key the starter AAR reads to find the
        // adapter for the protected component.
        val fromManifest = ReleaseIdentity.registeredAdapter(context)
        assertEquals(
            ProductionProbeAdapter::class.java.name,
            fromManifest.javaClass.name,
        )
    }

    @Test
    fun theProbeControlsCarryTheExactContractDescriptions() {
        assertEquals("SCPC_PROBE_IMPORT", context.getString(R.string.probe_import_description))
        assertEquals("SCPC_PROBE_RUN", context.getString(R.string.probe_run_description))
        assertEquals("SCPC_PROBE_EXPORT", context.getString(R.string.probe_export_description))
    }

    @Test
    fun theAdapterWritesTheStateTheProductScreensRead() {
        val adapter = ReleaseIdentity.registeredAdapter(context)
        val run = ProbeRunContext(
            "PARITY-ASSIGNMENT",
            "PARITY-CANDIDATE",
            "PARITY-INSTANCE",
            "PARITY-RUN",
            "PARITY-PACK",
            "PARITY-MISSION",
            "ra-v1:" + "0".repeat(64),
        )
        adapter.onRunStarted(context, run)

        val scope = "parity.scope"
        val value = "parity.value"
        val steps = listOf(
            step(1, ProductionCore.Op.RESET_AND_START, emptyMap()),
            step(
                2,
                ProductionCore.Op.UPSERT_FACT,
                mapOf(
                    Role.PRIMARY_GOAL to "parity.goal",
                    Role.TARGET_ENTITY to "parity.entity",
                    Role.PRESERVED_SCOPE to scope,
                    Role.CURRENT_AUTHORITY to "parity.authority.v1",
                    Role.STABLE_VALUE to value,
                ),
            ),
            step(
                3,
                ProductionCore.Op.REQUEST_DECISION,
                mapOf(Role.PRIMARY_GOAL to "parity.goal", Role.TARGET_ENTITY to "parity.entity"),
            ),
        )
        val results = steps.map { step ->
            ProbeResultShape.require(step, adapter.executeStep(context, run, step.toContractStep()))
        }
        ProbeResultShape.requireRun(steps, results)

        // The product surface reads the same document, with no extra bridging.
        val productState = Production.surface(context).state()
        val fieldId = AsprEngine.fieldIdFor(Ids.state("slot", scope))
        assertEquals(value, productState.fields.getValue(fieldId).value)
        assertEquals(FieldStatus.AUTO_APPLIED, productState.fields.getValue(fieldId).status)
        assertEquals("ACT", results.last().getString("decision_state"))

        val evidenceIds = adapter.onRunFinished(context, run)
        assertTrue(evidenceIds.isNotEmpty())
        val evidenceFiles = Production.runEvidenceRoot(context, Arm.FULL, run.runId)
            .walkTopDown().filter { it.isFile }.toList()
        assertEquals(
            "each returned evidence id has exactly one file in this run",
            evidenceIds.toSet(),
            evidenceFiles.map { it.nameWithoutExtension }.toSet(),
        )
        assertEquals(evidenceIds.size, evidenceFiles.size)
        evidenceFiles.forEach { file ->
            val document = JSONObject(file.readText(Charsets.UTF_8))
            assertTrue(
                "${file.name} embeds the id used as its filename",
                document.optString("evidence_id") == file.nameWithoutExtension ||
                    document.optString("receipt_id") == file.nameWithoutExtension,
            )
        }
    }

    @Test
    fun theProductSurfaceAndTheProbePathShareOneLedger() {
        val catalog = Production.catalog(context)
        val daon = catalog.restaurant("restaurant.daon")!!
        val surface = Production.surface(context)
        surface.startNewOrder(daon)
        surface.remember(
            daon,
            catalog.slot(Slots.SPICINESS),
            "spice.mild",
            stable = true,
        )
        val beforeStepCount = Production.core(context).state().runStepCount

        val adapter = ReleaseIdentity.registeredAdapter(context)
        val run = ProbeRunContext(
            "PARITY-ASSIGNMENT",
            "PARITY-CANDIDATE",
            "PARITY-INSTANCE",
            "PARITY-RUN-2",
            "PARITY-PACK",
            "PARITY-MISSION",
            "ra-v1:" + "0".repeat(64),
        )
        val decision = step(
            9,
            ProductionCore.Op.REQUEST_DECISION,
            mapOf(Role.TARGET_ENTITY to daon.entityToken),
        )
        adapter.executeStep(context, run, decision.toContractStep())

        val after = Production.core(context).state()
        assertTrue(
            "the probe step continued the same run the product screens started",
            after.runStepCount > beforeStepCount,
        )
        assertTrue(
            "the preference stored from the product screen is visible to the probe path",
            after.facts.values.any { it.value == "spice.mild" },
        )
    }

    @Test
    fun theSubmittedReleaseReportsNoModelUse() {
        val adapter = ReleaseIdentity.registeredAdapter(context)
        val runtime = adapter.runtimeState(context)
        assertFalse(runtime.modelConfigured)
        assertTrue(runtime.cumulativeInvocations in 0..60)

        val identity = ReleaseIdentity.runtimeIdentity(
            context,
            adapter,
            "ra-v1:" + "0".repeat(64),
        )
        assertEquals("NOT_USED", identity.getString("model_backend_frozen_id"))
    }

    @Test
    fun theMissionAdapterAssetIsShippedAndDigestible() {
        val digest = ReleaseIdentity.missionAdapterSha256(context)
        assertEquals(64, digest.length)
        val asset = context.assets.open("MISSION_ADAPTER.json").use { it.readBytes() }
        val document = JSONObject(String(asset, Charsets.UTF_8))
        assertEquals("mission_probe_adapter", document.getString("artifact_kind"))
        assertEquals(
            ProductionCore.Op.ALL.toSet(),
            document.getJSONObject("operation_bindings").keys().asSequence().toSet(),
        )
        assertEquals(
            Role.ALL.toSet(),
            document.getJSONObject("role_bindings").keys().asSequence().toSet(),
        )
        assertTrue(document.getJSONArray("source_paths").length() >= 2)
    }

    @Test
    fun theTwoComparisonArmsStayIsolated() {
        Production.selectArm(context, Arm.FULL)
        val catalog = Production.catalog(context)
        val daon = catalog.restaurant("restaurant.daon")!!
        val full = Production.surface(context)
        full.startNewOrder(daon)
        full.remember(
            daon,
            catalog.slot(Slots.SPICINESS),
            "spice.mild",
            stable = true,
        )
        assertTrue(Production.core(context, Arm.FULL).state().facts.isNotEmpty())
        assertTrue(
            "a save in the full arm must not be visible in the claim-off arm",
            Production.core(context, Arm.CLAIM_OFF).state().facts.isEmpty(),
        )

        Production.selectArm(context, Arm.CLAIM_OFF)
        assertNotNull(Production.core(context).state())
        assertEquals(Arm.CLAIM_OFF, Production.selectedArm(context))
    }

    @Test
    fun preparingAComparisonPairClonesTheExactStartingBytes() {
        val catalog = Production.catalog(context)
        val daon = catalog.restaurant("restaurant.daon")!!
        val full = Production.surface(context, Arm.FULL)
        full.startNewOrder(daon)
        full.remember(daon, catalog.slot(Slots.SPICINESS), "spice.mild", stable = true)
        ConversationStore(context, Arm.FULL).save("[{\"speaker\":\"user\",\"text\":\"동일 입력\"}]")

        val pair = Production.prepareComparisonPair(context)
        val fullBytes = AndroidStateStore(context, Arm.FULL.namespace).load()
        val claimOffBytes = AndroidStateStore(context, Arm.CLAIM_OFF.namespace).load()

        assertEquals(fullBytes, claimOffBytes)
        assertEquals(pair.sourceStateSha256, Digest.utf8(fullBytes!!))
        assertEquals(
            ConversationStore(context, Arm.FULL).load(),
            ConversationStore(context, Arm.CLAIM_OFF).load(),
        )
        assertEquals(Arm.FULL, Production.selectedArm(context))
        assertEquals(pair, Production.comparisonPair(context))
    }

    private fun step(ordinal: Int, operation: String, roles: Map<String, String>): ProbeStep =
        ProbeStep(
            index = ordinal,
            stepId = "PARITY-%02d".format(ordinal),
            eventId = "EVENT-PARITY-%02d-%s".format(ordinal, operation),
            operation = operation,
            sessionId = "PARITY-SESSION",
            virtualTime = "2026-03-01T00:%02d:00Z".format(ordinal),
            roles = JSONObject().also { out -> roles.forEach { (k, v) -> out.put(k, v) } },
        )
}

/** Test-only plumbing: build the contract step type the adapter is called with. */
private fun ProbeStep.toContractStep(): ProbeInputStep {
    val canonical = Canonical.encode(
        JSONObject()
            .put("event_id", eventId)
            .put("operation", operation)
            .put("roles", roles)
            .put("session_id", sessionId)
            .put("step_id", stepId)
            .put("virtual_time", virtualTime),
    )
    return ProbeInputStep(
        index,
        stepId,
        operation,
        sessionId,
        virtualTime,
        eventId,
        roles,
        canonical,
        Digest.utf8(canonical),
    )
}
