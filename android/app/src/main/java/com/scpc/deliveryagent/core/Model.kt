package com.scpc.deliveryagent.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Generic ASPR domain model.
 *
 * The core reasons only about fact identity, opaque role values, authority
 * version, scope identity, lifetime, permission and dependency edges. It never
 * inspects the spelling of a role value, so the same rules apply to product
 * screens, public rehearsal input and official hidden input.
 */

/** What kind of memory a fact is. Drives per-slot precedence. */
enum class FactKind {
    /** Explicitly stored preference intended for reuse in later sessions. */
    STABLE,

    /** Condition that applies to the session it was created in and expires with it. */
    ONE_OFF,

    /** Deletable one-time request used by the current draft. */
    EPHEMERAL,

    /** Satisfaction or catalog result that arrived after a virtual-time delay. */
    OUTCOME,

    /** Weak preference derived from history. Ranking only, never auto-applied. */
    INFERRED,
    ;

    /**
     * Precedence inside one slot: a condition the user stated for the current
     * session outranks a stored stable preference, which outranks a delayed
     * outcome, which outranks an inference.
     */
    val precedence: Int
        get() = when (this) {
            ONE_OFF -> 4
            EPHEMERAL -> 4
            STABLE -> 3
            OUTCOME -> 2
            INFERRED -> 1
        }
}

enum class FactSource { USER_EXPLICIT, USER_CORRECTION, DELAYED_OUTCOME, CATALOG_EVENT, PAST_ACTION, INFERENCE }

enum class Confidence { EXPLICIT, INFERRED }

enum class Permission { AUTO_APPLY, ASK_BEFORE_APPLY, RANK_ONLY }

enum class Lifetime { STABLE, SESSION, CATALOG_VERSION, EPHEMERAL }

/** Observable status of one order-draft field. */
enum class FieldStatus {
    /** Filled from a valid, permitted fact without asking the user. */
    AUTO_APPLIED,

    /** Proposed or blocked; the user still has to resolve it. */
    NEEDS_CONFIRMATION,

    /** The user resolved it in the current run. Current authority. */
    CONFIRMED,

    /** A change removed the value; it is not silently reused. */
    INVALIDATED,
}

/** Dependency edge kinds used for partial invalidation. */
enum class DepKind {
    /** The field value comes from this fact. */
    HARD_VALUE,

    /** The fact only influences ranking and proposal order. */
    RANKING,

    /** Auto-apply of this field depends on this permission scope. */
    PERMISSION,

    /** The field depends on this catalog line version. */
    CATALOG,

    /** The field is a roll-up of priced lines. */
    TOTAL,

    /** The user separately confirmed this field in the current session. */
    CONFIRMATION,
}

enum class NetworkState {
    ONLINE, OFFLINE, DELAYED, UNKNOWN;

    companion object {
        fun parseOrNull(token: String?): NetworkState? = when (token?.uppercase()) {
            "ONLINE" -> ONLINE
            "OFFLINE" -> OFFLINE
            "DELAYED" -> DELAYED
            "UNKNOWN" -> UNKNOWN
            else -> null
        }
    }
}

/** Decision states permitted by the official probe result contract. */
enum class DecisionState {
    ACT, ASK, WAIT, ABSTAIN, PENDING, FAILED, CONFIRMED_COMPLETE, NO_DECISION
}

/** Commit states permitted by the official probe result contract. */
enum class CommitState { NONE, PROPOSED, COMMITTED, CANCELLED, FAILED }

data class Fact(
    val factId: String,
    val kind: FactKind,
    val slotId: String,
    val value: String,
    val source: FactSource,
    val confidence: Confidence,
    val permission: Permission,
    val lifetime: Lifetime,
    val scopeIds: List<String>,
    val authorityVersion: Long,
    /**
     * Set only when the value belongs exclusively to one target. Such a value is
     * not carried to another restaurant, which is how a distractor is excluded.
     */
    val entityId: String?,
    /**
     * The target whose synthetic catalog priced this value. Set whenever the step
     * named a target, including for values the user allowed to be reused, because
     * the price and stock of a line always belong to the current catalog.
     */
    val catalogEntityId: String?,
    val goalId: String?,
    val boundSessionId: String?,
    val createdAtVirtual: String,
    val expiresAtVirtual: String?,
    val seq: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("factId", factId)
        .put("kind", kind.name)
        .put("slotId", slotId)
        .put("value", value)
        .put("source", source.name)
        .put("confidence", confidence.name)
        .put("permission", permission.name)
        .put("lifetime", lifetime.name)
        .put("scopeIds", JSONArray(scopeIds))
        .put("authorityVersion", authorityVersion)
        .put("entityId", entityId ?: JSONObject.NULL)
        .put("catalogEntityId", catalogEntityId ?: JSONObject.NULL)
        .put("goalId", goalId ?: JSONObject.NULL)
        .put("boundSessionId", boundSessionId ?: JSONObject.NULL)
        .put("createdAtVirtual", createdAtVirtual)
        .put("expiresAtVirtual", expiresAtVirtual ?: JSONObject.NULL)
        .put("seq", seq)

    companion object {
        fun fromJson(json: JSONObject): Fact = Fact(
            factId = json.getString("factId"),
            kind = FactKind.valueOf(json.getString("kind")),
            slotId = json.getString("slotId"),
            value = json.getString("value"),
            source = FactSource.valueOf(json.getString("source")),
            confidence = Confidence.valueOf(json.getString("confidence")),
            permission = Permission.valueOf(json.getString("permission")),
            lifetime = Lifetime.valueOf(json.getString("lifetime")),
            scopeIds = json.getJSONArray("scopeIds").strings(),
            authorityVersion = json.getLong("authorityVersion"),
            entityId = json.optNullableString("entityId"),
            catalogEntityId = json.optNullableString("catalogEntityId"),
            goalId = json.optNullableString("goalId"),
            boundSessionId = json.optNullableString("boundSessionId"),
            createdAtVirtual = json.getString("createdAtVirtual"),
            expiresAtVirtual = json.optNullableString("expiresAtVirtual"),
            seq = json.getLong("seq"),
        )
    }
}

data class Dep(val kind: DepKind, val ref: String) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("ref", ref)

    companion object {
        fun fromJson(json: JSONObject): Dep =
            Dep(DepKind.valueOf(json.getString("kind")), json.getString("ref"))
    }
}

data class DraftField(
    val fieldId: String,
    val slotId: String,
    val value: String?,
    val status: FieldStatus,
    val priced: Boolean,
    val deps: List<Dep>,
    val provenance: String,
    val factId: String?,
    val updatedSeq: Long,
    /**
     * True only when the user answered a question about this field, or explicitly
     * confirmed it, in the current session. A field that merely rode along on a
     * draft commit is not separately confirmed, so a later correction, revocation
     * or deletion may re-open it while a value the user actually chose is kept.
     */
    val userConfirmed: Boolean = false,
) {
    fun dependsOn(ref: String, kind: DepKind? = null): Boolean =
        deps.any { it.ref == ref && (kind == null || it.kind == kind) }

    fun toJson(): JSONObject = JSONObject()
        .put("fieldId", fieldId)
        .put("slotId", slotId)
        .put("value", value ?: JSONObject.NULL)
        .put("status", status.name)
        .put("priced", priced)
        .put("deps", JSONArray().also { array -> deps.forEach { array.put(it.toJson()) } })
        .put("provenance", provenance)
        .put("factId", factId ?: JSONObject.NULL)
        .put("updatedSeq", updatedSeq)
        .put("userConfirmed", userConfirmed)

    companion object {
        fun fromJson(json: JSONObject): DraftField = DraftField(
            fieldId = json.getString("fieldId"),
            slotId = json.getString("slotId"),
            value = json.optNullableString("value"),
            status = FieldStatus.valueOf(json.getString("status")),
            priced = json.getBoolean("priced"),
            deps = json.getJSONArray("deps").objects().map(Dep::fromJson),
            provenance = json.getString("provenance"),
            factId = json.optNullableString("factId"),
            updatedSeq = json.getLong("updatedSeq"),
            userConfirmed = json.optBoolean("userConfirmed", false),
        )
    }
}

data class ActionRecord(
    val actionId: String,
    val idempotencyKey: String,
    val commitState: CommitState,
    val outcomeId: String?,
    val originEventId: String,
    val draftDigest: String,
    val seq: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("actionId", actionId)
        .put("idempotencyKey", idempotencyKey)
        .put("commitState", commitState.name)
        .put("outcomeId", outcomeId ?: JSONObject.NULL)
        .put("originEventId", originEventId)
        .put("draftDigest", draftDigest)
        .put("seq", seq)

    /** Shape required by `PROBE_RESULT.schema.json` for the `action` member. */
    fun toResultJson(): JSONObject = JSONObject()
        .put("action_id", actionId)
        .put("idempotency_key", idempotencyKey)
        .put("commit_state", commitState.name)
        .put("outcome_id", outcomeId ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): ActionRecord = ActionRecord(
            actionId = json.getString("actionId"),
            idempotencyKey = json.getString("idempotencyKey"),
            commitState = CommitState.valueOf(json.getString("commitState")),
            outcomeId = json.optNullableString("outcomeId"),
            originEventId = json.getString("originEventId"),
            draftDigest = json.getString("draftDigest"),
            seq = json.getLong("seq"),
        )
    }
}

/**
 * Minimal deletion marker. Holds only what is needed to keep the deleted
 * original from reappearing; never the original value.
 */
data class Tombstone(
    val tombstoneId: String,
    val slotId: String,
    val scopeIds: List<String>,
    val authorityVersion: Long,
    val deletedAtVirtual: String,
    val kind: FactKind,
    val seq: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("tombstoneId", tombstoneId)
        .put("slotId", slotId)
        .put("scopeIds", JSONArray(scopeIds))
        .put("authorityVersion", authorityVersion)
        .put("deletedAtVirtual", deletedAtVirtual)
        .put("kind", kind.name)
        .put("seq", seq)

    companion object {
        fun fromJson(json: JSONObject): Tombstone = Tombstone(
            tombstoneId = json.getString("tombstoneId"),
            slotId = json.getString("slotId"),
            scopeIds = json.getJSONArray("scopeIds").strings(),
            authorityVersion = json.getLong("authorityVersion"),
            deletedAtVirtual = json.getString("deletedAtVirtual"),
            kind = FactKind.valueOf(json.getString("kind")),
            seq = json.getLong("seq"),
        )
    }
}

/** Outcome scheduled by virtual time and not yet materialised. */
data class PendingOutcome(
    val outcomeId: String,
    val value: String,
    val slotId: String,
    val scopeIds: List<String>,
    val entityId: String?,
    val scheduledAtVirtual: String,
    val seq: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("outcomeId", outcomeId)
        .put("value", value)
        .put("slotId", slotId)
        .put("scopeIds", JSONArray(scopeIds))
        .put("entityId", entityId ?: JSONObject.NULL)
        .put("scheduledAtVirtual", scheduledAtVirtual)
        .put("seq", seq)

    companion object {
        fun fromJson(json: JSONObject): PendingOutcome = PendingOutcome(
            outcomeId = json.getString("outcomeId"),
            value = json.getString("value"),
            slotId = json.getString("slotId"),
            scopeIds = json.getJSONArray("scopeIds").strings(),
            entityId = json.optNullableString("entityId"),
            scheduledAtVirtual = json.getString("scheduledAtVirtual"),
            seq = json.getLong("seq"),
        )
    }
}

/**
 * App-local record of truth that a delayed outcome was folded into the state
 * exactly once. Independent of any optional permission surface.
 */
data class AppliedOutcome(
    val outcomeId: String,
    val slotId: String,
    val appliedAtVirtual: String,
    val seq: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("outcomeId", outcomeId)
        .put("slotId", slotId)
        .put("appliedAtVirtual", appliedAtVirtual)
        .put("seq", seq)

    companion object {
        fun fromJson(json: JSONObject): AppliedOutcome = AppliedOutcome(
            outcomeId = json.getString("outcomeId"),
            slotId = json.getString("slotId"),
            appliedAtVirtual = json.getString("appliedAtVirtual"),
            seq = json.getLong("seq"),
        )
    }
}

/** Event delivered late or twice, recorded so refusal is observable. */
data class RejectedEvent(
    val eventId: String,
    val reason: String,
    val incomingAuthorityVersion: Long,
    val currentAuthorityVersion: Long,
    val seq: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("eventId", eventId)
        .put("reason", reason)
        .put("incomingAuthorityVersion", incomingAuthorityVersion)
        .put("currentAuthorityVersion", currentAuthorityVersion)
        .put("seq", seq)

    companion object {
        fun fromJson(json: JSONObject): RejectedEvent = RejectedEvent(
            eventId = json.getString("eventId"),
            reason = json.getString("reason"),
            incomingAuthorityVersion = json.getLong("incomingAuthorityVersion"),
            currentAuthorityVersion = json.getLong("currentAuthorityVersion"),
            seq = json.getLong("seq"),
        )
    }
}

data class EventRecord(
    val eventId: String,
    val stepId: String,
    val operation: String,
    val sessionId: String,
    val seq: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("eventId", eventId)
        .put("stepId", stepId)
        .put("operation", operation)
        .put("sessionId", sessionId)
        .put("seq", seq)

    companion object {
        fun fromJson(json: JSONObject): EventRecord = EventRecord(
            eventId = json.getString("eventId"),
            stepId = json.getString("stepId"),
            operation = json.getString("operation"),
            sessionId = json.getString("sessionId"),
            seq = json.getLong("seq"),
        )
    }
}

/**
 * One field-resolution event. This ledger is the source of the paired
 * comparison metric VIL (Valid Interaction Load).
 */
data class Resolution(
    val fieldId: String,
    val kind: String,
    val stepId: String,
    val seq: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("fieldId", fieldId)
        .put("kind", kind)
        .put("stepId", stepId)
        .put("seq", seq)

    companion object {
        fun fromJson(json: JSONObject): Resolution = Resolution(
            fieldId = json.getString("fieldId"),
            kind = json.getString("kind"),
            stepId = json.getString("stepId"),
            seq = json.getLong("seq"),
        )
    }
}

/**
 * A review the user left about an order that already happened.
 *
 * The text is kept so the user can see and delete what they wrote, and
 * [derivedValueTokens] records exactly which memory the review produced, so
 * deleting the review also removes what was learned from it.
 *
 * [lineValueToken] is the line the reviewed order actually contained. A rating is
 * raw history about that one line at that one target, so keeping it here rather
 * than as a separate tally means the rating is always a pure function of the
 * reviews that still exist: deleting a review removes its weight with no second
 * place to keep in sync.
 */
data class ReviewRecord(
    val reviewId: String,
    val ratingToken: String?,
    val entityId: String?,
    val lineValueToken: String?,
    val actionId: String?,
    val text: String,
    val atVirtual: String,
    val derivedValueTokens: List<String>,
    val seq: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("reviewId", reviewId)
        .put("ratingToken", ratingToken ?: JSONObject.NULL)
        .put("entityId", entityId ?: JSONObject.NULL)
        .put("lineValueToken", lineValueToken ?: JSONObject.NULL)
        .put("actionId", actionId ?: JSONObject.NULL)
        .put("text", text)
        .put("atVirtual", atVirtual)
        .put("derivedValueTokens", JSONArray(derivedValueTokens))
        .put("seq", seq)

    companion object {
        fun fromJson(json: JSONObject): ReviewRecord = ReviewRecord(
            reviewId = json.getString("reviewId"),
            ratingToken = json.optNullableString("ratingToken"),
            entityId = json.optNullableString("entityId"),
            lineValueToken = json.optNullableString("lineValueToken"),
            actionId = json.optNullableString("actionId"),
            text = json.getString("text"),
            atVirtual = json.getString("atVirtual"),
            derivedValueTokens = json.getJSONArray("derivedValueTokens").strings(),
            seq = json.getLong("seq"),
        )
    }
}

/** Terminal snapshot of a previous run, kept for audit after a reset. */
data class ArchivedRun(
    val runId: String,
    val terminalDigest: String,
    val endedAtVirtual: String,
    val stepCount: Long,
    val terminal: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("runId", runId)
        .put("terminalDigest", terminalDigest)
        .put("endedAtVirtual", endedAtVirtual)
        .put("stepCount", stepCount)
        .put("terminal", terminal)

    companion object {
        fun fromJson(json: JSONObject): ArchivedRun = ArchivedRun(
            runId = json.getString("runId"),
            terminalDigest = json.getString("terminalDigest"),
            endedAtVirtual = json.getString("endedAtVirtual"),
            stepCount = json.getLong("stepCount"),
            terminal = json.getBoolean("terminal"),
        )
    }
}

internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

internal fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)
