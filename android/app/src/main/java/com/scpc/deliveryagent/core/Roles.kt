package com.scpc.deliveryagent.core

import org.json.JSONObject

/** The ten semantic roles fixed by the official Probe contract. */
object Role {
    const val PRIMARY_GOAL = "PRIMARY_GOAL"
    const val TARGET_ENTITY = "TARGET_ENTITY"
    const val DISTRACTOR_ENTITY = "DISTRACTOR_ENTITY"
    const val STABLE_VALUE = "STABLE_VALUE"
    const val ONE_OFF_VALUE = "ONE_OFF_VALUE"
    const val CURRENT_AUTHORITY = "CURRENT_AUTHORITY"
    const val REVOKED_SCOPE = "REVOKED_SCOPE"
    const val PRESERVED_SCOPE = "PRESERVED_SCOPE"
    const val DELAYED_OUTCOME = "DELAYED_OUTCOME"
    const val EPHEMERAL_VALUE = "EPHEMERAL_VALUE"

    val ALL = listOf(
        PRIMARY_GOAL, TARGET_ENTITY, DISTRACTOR_ENTITY, STABLE_VALUE, ONE_OFF_VALUE,
        CURRENT_AUTHORITY, REVOKED_SCOPE, PRESERVED_SCOPE, DELAYED_OUTCOME, EPHEMERAL_VALUE,
    )
}

/**
 * Addressing keys the product surface adds beyond the ten contract roles.
 *
 * The contract tolerates caller-defined addressing keys, and official probe
 * input never sends these; a step without them stores an unscoped fact exactly
 * as before. They carry the structural scope of a preference — which base slot
 * it competes for and how narrowly it applies — so the engine gets scope as
 * data instead of parsing it out of a token spelling.
 */
object RoleExt {
    /** Base option slot token a scoped preference competes for. */
    const val BASE_SCOPE = "PRODUCT_BASE_SCOPE"

    /** "0" global, "1" menu-type, "2" exact restaurant-menu override. */
    const val SPECIFICITY = "PRODUCT_SPECIFICITY"

    /** Catalog-authored menu type token bound to a specificity-1 value. */
    const val MENU_TYPE_ID = "PRODUCT_MENU_TYPE_ID"

    /** Exact menu token bound to a specificity-2 value. */
    const val SCOPE_MENU_ID = "PRODUCT_SCOPE_MENU_ID"

    /**
     * Virtual time after which a scheduled delayed outcome stops applying, for
     * a passing notice such as a request to rate the finished order.
     */
    const val EXPIRES_AT = "PRODUCT_EXPIRES_AT"
}

/**
 * Read-only view over one step's `roles` object.
 *
 * Role values are opaque synthetic tokens. Nothing here interprets their
 * spelling: values become identifiers, are compared for equality with values
 * already in production state, or are matched against the closed contract enum
 * for synthetic network state. Extra keys beyond the ten semantic roles are
 * tolerated because a run may carry caller-defined addressing keys.
 */
class RoleView(private val roles: JSONObject) {

    /** Present, non-null role values keyed by role name, in sorted key order. */
    val entries: List<Pair<String, String>> =
        roles.keys().asSequence().sorted()
            .mapNotNull { key ->
                if (roles.isNull(key)) null else key to roles.get(key).toString()
            }
            .toList()

    fun value(role: String): String? =
        entries.firstOrNull { it.first == role }?.second?.takeIf { it.isNotEmpty() }

    fun firstValue(vararg candidates: String): String? =
        candidates.firstNotNullOfOrNull { value(it) }

    /** All values, sorted by key, for generic scans that must not assume a key name. */
    fun values(): List<String> = entries.map { it.second }

    /**
     * Synthetic network state. The contract does not fix the key name, so every
     * value is checked against the closed enum, exactly as the official sample
     * adapter does.
     */
    fun networkState(): NetworkState? = values().firstNotNullOfOrNull(NetworkState::parseOrNull)

    /**
     * Resolves a role value that names an event this run already processed.
     * Matching against our own ledger keeps replay and late delivery generic
     * instead of depending on a particular caller key name.
     */
    fun referencedEventId(known: Set<String>, exclude: String): String? =
        entries.map { it.second }.firstOrNull { it != exclude && it in known }

    fun toJson(): JSONObject = JSONObject().also { out ->
        entries.forEach { (key, value) -> out.put(key, value) }
    }
}
