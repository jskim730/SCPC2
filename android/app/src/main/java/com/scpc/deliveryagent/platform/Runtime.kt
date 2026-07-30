package com.scpc.deliveryagent.platform

import android.content.Context
import com.scpc.deliveryagent.core.EvidenceDoc
import com.scpc.deliveryagent.core.ProductionCore
import com.scpc.deliveryagent.core.ProductionState
import com.scpc.deliveryagent.core.StateStore
import com.scpc.deliveryagent.delivery.ProductSurface
import com.scpc.deliveryagent.delivery.SyntheticCatalog
import java.io.File
import java.util.UUID

/**
 * Which comparison arm this device is running.
 *
 * `full` has the Signature mechanism on, `claim-off` has only that mechanism off
 * in the same APK. The arm is chosen explicitly by a person on the comparison
 * screen and stored; it is never inferred from a probe pack id, a surface nonce
 * or any caller string.
 */
enum class Arm(val namespace: String, val asprEnabled: Boolean) {
    FULL(ProductionState.NAMESPACE_FULL, true),
    CLAIM_OFF(ProductionState.NAMESPACE_CLAIM_OFF, false),
    ;

    companion object {
        fun of(namespace: String?): Arm =
            entries.firstOrNull { it.namespace == namespace } ?: FULL
    }
}

/** Durable store backed by one atomically committed preference entry. */
class AndroidStateStore(context: Context, private val namespace: String) : StateStore {

    private val preferences =
        context.applicationContext.getSharedPreferences("scpc-core-$namespace", Context.MODE_PRIVATE)

    override fun load(): String? = preferences.getString(KEY_STATE, null)

    override fun save(encoded: String) {
        // commit() rather than apply(): the document must be on disk before the
        // step result is returned, so a process kill right after a step cannot
        // lose it.
        check(preferences.edit().putString(KEY_STATE, encoded).commit()) {
            "cannot persist production state for namespace $namespace"
        }
    }

    override fun clear() {
        check(preferences.edit().remove(KEY_STATE).commit()) {
            "cannot clear production state for namespace $namespace"
        }
    }

    private companion object {
        const val KEY_STATE = "production_state_json"
    }
}

/**
 * Builds the production core used by every entry point.
 *
 * Product screens, the public Probe screen and the protected Probe callback all
 * call this factory, so they share one repository, one decision engine and one
 * ledger.
 */
object Production {

    /** New per-process identity; a mismatch on load proves the process restarted. */
    private val PROCESS_MARKER: String = UUID.randomUUID().toString()

    private const val SETTINGS = "scpc-settings"
    private const val KEY_ARM = "selected_arm"
    private const val CATALOG_ASSET = "synthetic/catalog.json"

    fun selectedArm(context: Context): Arm =
        Arm.of(
            context.applicationContext
                .getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
                .getString(KEY_ARM, Arm.FULL.namespace),
        )

    fun selectArm(context: Context, arm: Arm) {
        context.applicationContext
            .getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ARM, arm.namespace)
            .commit()
    }

    @Volatile
    private var catalogCache: SyntheticCatalog? = null

    /**
     * The authored synthetic catalog, parsed once from the shipped asset bytes.
     *
     * Both comparison arms read the same bytes, and [SyntheticCatalog.snapshotDigest]
     * records which bytes they were.
     */
    fun catalog(context: Context): SyntheticCatalog {
        catalogCache?.let { return it }
        return synchronized(this) {
            catalogCache ?: SyntheticCatalog.parse(
                context.applicationContext.assets.open(CATALOG_ASSET)
                    .use { it.readBytes() }
                    .toString(Charsets.UTF_8),
            ).also { catalogCache = it }
        }
    }

    fun surface(context: Context, arm: Arm = selectedArm(context)): ProductSurface =
        ProductSurface(core(context, arm), catalog(context))

    fun core(context: Context, runBinding: String? = null): ProductionCore {
        val arm = selectedArm(context)
        return core(context, arm, runBinding)
    }

    fun core(context: Context, arm: Arm, runBinding: String? = null): ProductionCore =
        ProductionCore(
            store = AndroidStateStore(context, arm.namespace),
            processMarker = PROCESS_MARKER,
            namespace = arm.namespace,
            asprEnabled = arm.asprEnabled,
            runBinding = runBinding,
        )

    fun resetAll(context: Context) {
        Arm.entries.forEach { AndroidStateStore(context, it.namespace).clear() }
        Arm.entries.forEach { arm -> evidenceRoot(context, arm).deleteRecursively() }
    }

    fun evidenceRoot(context: Context, arm: Arm): File =
        File(context.applicationContext.filesDir, "export/${arm.namespace}")
}

/**
 * Writes the structured evidence a step produced.
 *
 * Each arm writes into its own directory, so `full` and `claim-off` artifacts
 * never mix.
 */
class EvidenceWriter(context: Context, arm: Arm) {

    private val root: File = Production.evidenceRoot(context, arm)

    fun write(documents: List<EvidenceDoc>) {
        documents.forEach { document ->
            val target = File(root, document.relativePath)
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeText(document.content.toString(2), Charsets.UTF_8)
            if (target.exists()) target.delete()
            check(temporary.renameTo(target)) { "cannot write evidence ${document.evidenceId}" }
        }
    }

    fun clear() {
        root.deleteRecursively()
    }

    fun root(): File = root
}
