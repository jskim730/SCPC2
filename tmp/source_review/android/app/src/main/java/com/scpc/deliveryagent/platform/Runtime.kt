package com.scpc.deliveryagent.platform

import android.content.Context
import com.scpc.deliveryagent.core.EvidenceDoc
import com.scpc.deliveryagent.core.Digest
import com.scpc.deliveryagent.core.Ids
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
 * The conversation thread, kept across process death.
 *
 * The order, the draft, what is remembered and every ledger entry live in the
 * core's own store. This is the screen's record of what was said — not a fact
 * the engine reasons about, but the thing the person is actually looking at.
 * Keeping the draft across a kill while the conversation that produced it
 * vanished would leave the user staring at values with no account of where they
 * came from, which is the opposite of the continuity this app promises.
 *
 * `commit()` for the same reason the state store uses it: `이 process 종료` kills
 * the process outright, and an asynchronous write may never reach disk.
 *
 * One record per arm, so the two comparison arms never show each other's words.
 */
class ConversationStore(context: Context, arm: Arm) {

    private val preferences = context.applicationContext
        .getSharedPreferences("scpc-conversation-${arm.namespace}", Context.MODE_PRIVATE)

    fun load(): String? = preferences.getString(KEY_THREAD, null)

    fun save(encoded: String) {
        preferences.edit().putString(KEY_THREAD, encoded).commit()
    }

    fun clear() {
        preferences.edit().remove(KEY_THREAD).commit()
    }

    private companion object {
        const val KEY_THREAD = "thread_json"
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
    private const val COMPARISON = "scpc-comparison-pair"

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

    /** Immutable binding shown beside a manually prepared paired comparison. */
    data class ComparisonPair(
        val pairId: String,
        val sourceStateSha256: String,
        val conversationSha256: String?,
        val catalogSha256: String,
    )

    /** Last explicitly prepared pair, or null before the operator prepares one. */
    fun comparisonPair(context: Context): ComparisonPair? {
        val preferences = context.applicationContext
            .getSharedPreferences(COMPARISON, Context.MODE_PRIVATE)
        val pairId = preferences.getString("pair_id", null) ?: return null
        return ComparisonPair(
            pairId = pairId,
            sourceStateSha256 = preferences.getString("source_state_sha256", null) ?: return null,
            conversationSha256 = preferences.getString("conversation_sha256", null),
            catalogSha256 = preferences.getString("catalog_sha256", null) ?: return null,
        )
    }

    /**
     * Clones one exact starting snapshot into both isolated arm stores.
     *
     * The full arm is the source because it is where the product flow is normally
     * prepared. Both stores receive identical bytes; when loaded, the core
     * overrides only namespace and the one mechanism flag. From the first arm
     * operation onward the stores, conversations and evidence diverge again.
     */
    fun prepareComparisonPair(context: Context): ComparisonPair {
        val app = context.applicationContext
        val sourceState = AndroidStateStore(app, Arm.FULL.namespace).load()
            ?: ProductionState.fresh(Arm.FULL.namespace, Arm.FULL.asprEnabled).encode()
        val sourceConversation = ConversationStore(app, Arm.FULL).load()
        val stateSha = Digest.utf8(sourceState)
        val conversationSha = sourceConversation?.let(Digest::utf8)
        val catalogSha = catalog(app).snapshotDigest
        val bindingSha = Digest.utf8(
            listOf(stateSha, conversationSha ?: "none", catalogSha).joinToString("|"),
        )
        val pair = ComparisonPair(
            pairId = "cmp.${bindingSha.take(24)}",
            sourceStateSha256 = stateSha,
            conversationSha256 = conversationSha,
            catalogSha256 = catalogSha,
        )

        Arm.entries.forEach { arm ->
            AndroidStateStore(app, arm.namespace).save(sourceState)
            if (sourceConversation == null) {
                ConversationStore(app, arm).clear()
            } else {
                ConversationStore(app, arm).save(sourceConversation)
            }
            evidenceRoot(app, arm).deleteRecursively()
        }
        check(
            app.getSharedPreferences(COMPARISON, Context.MODE_PRIVATE)
                .edit()
                .putString("pair_id", pair.pairId)
                .putString("source_state_sha256", pair.sourceStateSha256)
                .putString("conversation_sha256", pair.conversationSha256)
                .putString("catalog_sha256", pair.catalogSha256)
                .commit(),
        ) { "cannot persist comparison pair binding" }
        selectArm(app, Arm.FULL)
        return pair
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
        Arm.entries.forEach { ConversationStore(context, it).clear() }
        Arm.entries.forEach { arm -> evidenceRoot(context, arm).deleteRecursively() }
        context.applicationContext.getSharedPreferences(COMPARISON, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    fun evidenceRoot(context: Context, arm: Arm): File =
        File(context.applicationContext.filesDir, "export/${arm.namespace}")

    /** Evidence of one run, isolated so a rehearsal can never leak into another. */
    fun runEvidenceRoot(context: Context, arm: Arm, runId: String): File =
        File(evidenceRoot(context, arm), Ids.segment(runId))
}

/**
 * Writes the structured evidence a step produced.
 *
 * Each arm writes into its own directory, so `full` and `claim-off` artifacts
 * never mix.
 */
class EvidenceWriter(context: Context, arm: Arm, runId: String) {

    private val root: File = Production.runEvidenceRoot(context, arm, runId)

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
