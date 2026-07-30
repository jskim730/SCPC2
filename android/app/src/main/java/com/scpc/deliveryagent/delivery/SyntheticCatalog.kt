package com.scpc.deliveryagent.delivery

import com.scpc.deliveryagent.core.AsprEngine
import com.scpc.deliveryagent.core.Digest
import com.scpc.deliveryagent.core.Ids
import com.scpc.deliveryagent.core.ProductionState
import org.json.JSONObject

/**
 * The synthetic delivery catalog.
 *
 * Every restaurant, menu, option, price, stock flag, estimate and recognised
 * phrase in this app is written by hand into `assets/synthetic/catalog.json` and
 * read from there. It is not generated at runtime and not fetched from anywhere:
 *
 *  * the paired comparison requires both arms to start from the same snapshot
 *    bytes, so a value that varies per run would make the causal claim
 *    unreproducible,
 *  * data kept in an asset stays physically outside the decision path, which is
 *    what lets the generic core be shown to branch on identity rather than on the
 *    spelling of a value,
 *  * one reviewable file ships byte-for-byte in both the APK and the source
 *    bundle.
 *
 * Nothing here is a real restaurant, menu, price, address, contact or account.
 */

/** How a slot's values are formed. */
enum class ValueKind {
    /** A fixed list the catalog spells out. */
    ENUMERATED,

    /** Any well-formed synthetic amount, so a stated budget need not be a preset. */
    AMOUNT,

    /** Any well-formed number of minutes. */
    DURATION_MINUTES,
    ;

    companion object {
        fun of(value: String): ValueKind = when (value) {
            "enumerated" -> ENUMERATED
            "amount" -> AMOUNT
            "duration_minutes" -> DURATION_MINUTES
            else -> error("unknown value kind '$value'")
        }
    }
}

/** What an option slot describes. */
enum class SlotKind {
    /** Part of a restaurant's menu. Only offered where the restaurant offers it. */
    MENU_OPTION,

    /** A condition the user states for this order, such as a budget. */
    USER_CONDITION,

    /** A one-time request the user attaches to this order. */
    USER_REQUEST,
    ;

    companion object {
        fun of(value: String): SlotKind = when (value) {
            "menu_option" -> MENU_OPTION
            "user_condition" -> USER_CONDITION
            "user_request" -> USER_REQUEST
            else -> error("unknown option slot kind '$value'")
        }
    }
}

/** One selectable value of one option slot. */
data class OptionValue(
    val token: String,
    val label: String,
    /** Synthetic amount this value adds to the order, in the synthetic currency. */
    val priceDelta: Int,
    val inStock: Boolean,
    val etaMinutes: Int?,
    val budgetLimit: Int?,
    val etaLimitMinutes: Int?,
    /** Ways a person might say this. Used only to recognise words. */
    val phrases: List<String>,
    /** Value tokens of other slots this item satisfies. Used for ranking. */
    val traits: List<String>,
)

/** One option slot: the addressing scope plus the values it accepts. */
data class SlotDefinition(
    val scopeToken: String,
    val label: String,
    val kind: SlotKind,
    /** True when a value in this slot contributes to the order total. */
    val priced: Boolean,
    /**
     * True when the menu needs a value here before the order is complete. A side
     * or a request note is offered but not demanded.
     */
    val required: Boolean,
    val valueKind: ValueKind,
    val values: List<OptionValue>,
) {
    val slotId: String get() = Ids.state("slot", scopeToken)
    val fieldId: String get() = AsprEngine.fieldIdFor(slotId)

    /** Last segment of the scope token, which prefixes this slot's value tokens. */
    val valuePrefix: String get() = scopeToken.substringAfterLast('.')
}

/** One synthetic restaurant: its option schema and its menu. */
data class RestaurantDefinition(
    val entityToken: String,
    val name: String,
    val slotTokens: List<String>,
    val menu: List<OptionValue>,
) {
    fun slotIds(): List<String> = slotTokens.map { Ids.state("slot", it) }
}

/** A pre-authored catalog change, such as one line going out of stock. */
data class CatalogEvent(
    val eventToken: String,
    val entityToken: String,
    val scopeToken: String,
    val valueToken: String,
    val description: String,
)

/** Phrases that state how long a value should last. */
data class ScopePhrases(
    val thisOrderOnly: List<String>,
    val rememberForReuse: List<String>,
)

/** A phrase the catalog declares too vague to act on, with why. */
data class UnclearPhrase(val phrase: String, val reason: String)

/** How a person can rate an order that already happened. */
data class RatingValue(
    val token: String,
    val label: String,
    val sentiment: Sentiment,
    /** Where this sits on the scale, so an average is readable. Catalog data. */
    val score: Int,
)

enum class Sentiment {
    POSITIVE, NEUTRAL, NEGATIVE;

    companion object {
        fun of(value: String): Sentiment = when (value) {
            "positive" -> POSITIVE
            "neutral" -> NEUTRAL
            "negative" -> NEGATIVE
            else -> error("unknown sentiment '$value'")
        }
    }
}

/**
 * A phrase that can appear in a review of an order that already happened.
 *
 * [slotToken] is the option the remark was about, when it names one, and
 * [impliesValue] is the value that would answer the remark next time. Both are
 * catalog data: the app proposes, and the user decides whether it becomes memory.
 */
data class ReviewPhrase(
    val phrase: String,
    val slotToken: String?,
    val impliesValue: String?,
    val sentiment: Sentiment,
)

/** Well-known addressing tokens. These are identifiers, not catalog data. */
object Slots {
    const val MAIN = "option.main"
    const val SPICINESS = "option.spiciness"
    const val SALTINESS = "option.saltiness"
    const val RICE = "option.rice"
    const val UTENSIL = "option.utensil"
    const val SIDE = "option.side"
    const val BUDGET = "condition.budget"
    const val WARMTH = "condition.warmth"
    const val ETA = "condition.eta"
    const val NOTE = "request.note"
}

class SyntheticCatalog private constructor(
    val currency: String,
    val deliveryAlias: String,
    val provenance: String,
    val slots: List<SlotDefinition>,
    val restaurants: List<RestaurantDefinition>,
    val events: List<CatalogEvent>,
    val scopePhrases: ScopePhrases,
    val intentPhrases: Map<Intent, List<String>>,
    val unclearPhrases: List<UnclearPhrase>,
    val ratingValues: List<RatingValue>,
    val ratingScaleMax: Int,
    val reviewPhrases: List<ReviewPhrase>,
    /**
     * Digest of the authored bytes. Recorded in comparison evidence so the two
     * arms can be shown to have started from the same catalog snapshot.
     */
    val snapshotDigest: String,
) {

    private val slotsByToken = slots.associateBy { it.scopeToken }
    private val valuesByToken: Map<String, OptionValue> =
        (slots.flatMap { it.values } + restaurants.flatMap { it.menu }).associateBy { it.token }
    private val slotTokenOfValue: Map<String, String> = buildMap {
        slots.forEach { slot -> slot.values.forEach { put(it.token, slot.scopeToken) } }
        restaurants.forEach { restaurant -> restaurant.menu.forEach { put(it.token, Slots.MAIN) } }
    }
    private val numericSlotsByPrefix: Map<String, SlotDefinition> = slots
        .filter { it.valueKind != ValueKind.ENUMERATED }
        .associateBy { it.valuePrefix }

    fun restaurant(entityToken: String?): RestaurantDefinition? =
        restaurants.firstOrNull { it.entityToken == entityToken }

    fun slot(scopeToken: String): SlotDefinition =
        slotsByToken[scopeToken] ?: error("unknown option slot $scopeToken")

    fun slotsOf(restaurant: RestaurantDefinition): List<SlotDefinition> =
        restaurant.slotTokens.map(::slot)

    /** Values offerable in a slot at a restaurant. The menu belongs to the restaurant. */
    fun valuesFor(restaurant: RestaurantDefinition, scopeToken: String): List<OptionValue> =
        if (scopeToken == Slots.MAIN) restaurant.menu else slot(scopeToken).values

    /**
     * Resolves a value token.
     *
     * An amount or duration slot accepts any well-formed number, so a budget the
     * user stated that the catalog never listed still resolves.
     */
    fun value(token: String?): OptionValue? {
        if (token == null) return null
        valuesByToken[token]?.let { return it }
        return synthesiseNumeric(token)
    }

    /** True when this token is a value the given slot can legitimately hold. */
    fun accepts(restaurant: RestaurantDefinition, scopeToken: String, token: String): Boolean {
        if (slot(scopeToken).valueKind != ValueKind.ENUMERATED) {
            return synthesiseNumeric(token) != null
        }
        return valuesFor(restaurant, scopeToken).any { it.token == token }
    }

    fun event(eventToken: String): CatalogEvent =
        events.firstOrNull { it.eventToken == eventToken } ?: error("unknown event $eventToken")

    /** Label for an opaque value token, falling back to the token itself. */
    fun valueLabel(token: String?): String = when {
        token == null -> "미정"
        else -> value(token)?.let { entry ->
            if (entry.inStock) entry.label else "${entry.label} (품절)"
        } ?: token
    }

    /** Label for a slot identifier produced by the core. */
    fun slotLabel(slotId: String): String {
        if (slotId == AsprEngine.SLOT_TOTAL) return "총액"
        slots.forEach { slot ->
            if (slotId == slot.slotId || slotId.startsWith("${slot.slotId}.")) {
                return when {
                    slotId.endsWith(".outcome") -> "${slot.label} (지난 평가)"
                    else -> slot.label
                }
            }
        }
        return slotId
    }

    fun rating(token: String?): RatingValue? =
        ratingValues.firstOrNull { it.token == token }

    fun scopeTokenOfValue(token: String): String? =
        slotTokenOfValue[token] ?: numericSlotsByPrefix[token.substringBefore('.')]?.scopeToken

    /** Value tokens the current catalog cannot fulfil, such as an out-of-stock line. */
    fun unusableValueTokens(): Set<String> =
        (slots.flatMap { it.values } + restaurants.flatMap { it.menu })
            .filter { !it.inStock }
            .map { it.token }
            .toSet()

    /** Resolves the slot a core field identifier belongs to. */
    fun slotOfFieldSlotId(slotId: String): SlotDefinition? = slots.firstOrNull {
        slotId == it.slotId || slotId.startsWith("${it.slotId}.")
    }

    private fun synthesiseNumeric(token: String): OptionValue? {
        val slot = numericSlotsByPrefix[token.substringBefore('.')] ?: return null
        val amount = token.substringAfter('.', "").toIntOrNull() ?: return null
        if (amount <= 0) return null
        return when (slot.valueKind) {
            ValueKind.AMOUNT -> OptionValue(
                token = token,
                label = "%,d원 이하".format(amount),
                priceDelta = 0,
                inStock = true,
                etaMinutes = null,
                budgetLimit = amount,
                etaLimitMinutes = null,
                phrases = emptyList(),
                traits = emptyList(),
            )

            ValueKind.DURATION_MINUTES -> OptionValue(
                token = token,
                label = "${amount}분 이내",
                priceDelta = 0,
                inStock = true,
                etaMinutes = null,
                budgetLimit = null,
                etaLimitMinutes = amount,
                phrases = emptyList(),
                traits = emptyList(),
            )

            ValueKind.ENUMERATED -> null
        }
    }

    companion object {

        private const val EXPECTED_SCHEMA = 2
        private const val EXPECTED_KIND = "synthetic_delivery_catalog"

        /** A restaurant name must carry this marker so nobody mistakes it for a real one. */
        private const val SYNTHETIC_MARKER = "실험"

        private val PHONE_LIKE = Regex("""\d{2,4}-\d{3,4}-\d{4}""")
        private val TOKEN = Regex("^[a-z][a-z0-9._]{2,63}$")
        private val WHITESPACE = Regex("\\s+")

        /**
         * Parses and validates the authored catalog.
         *
         * The checks are part of the product, not test-only scaffolding: shipping a
         * catalog that carries something resembling personal data, that references a
         * slot which does not exist, or in which one phrase means two different
         * things, is a defect that should stop the app rather than surface later as a
         * wrong draft.
         */
        fun parse(text: String): SyntheticCatalog {
            val root = JSONObject(text)
            require(root.getInt("schema_version") == EXPECTED_SCHEMA) {
                "unsupported catalog schema_version"
            }
            require(root.getString("artifact_kind") == EXPECTED_KIND) {
                "not a synthetic delivery catalog"
            }

            val slots = root.getJSONArray("option_slots").let { array ->
                (0 until array.length()).map { index -> slot(array.getJSONObject(index)) }
            }
            val restaurants = root.getJSONArray("restaurants").let { array ->
                (0 until array.length()).map { index -> restaurant(array.getJSONObject(index)) }
            }
            val events = root.optJSONArray("catalog_events")?.let { array ->
                (0 until array.length()).map { index -> event(array.getJSONObject(index)) }
            }.orEmpty()

            requireUnique(slots.map { it.scopeToken }, "option slot")
            requireUnique(restaurants.map { it.entityToken }, "restaurant")
            requireUnique(
                slots.flatMap { it.values }.map { it.token } +
                    restaurants.flatMap { it.menu }.map { it.token },
                "value token",
            )
            require(restaurants.isNotEmpty()) { "catalog has no restaurants" }

            val slotTokens = slots.map { it.scopeToken }.toSet()
            val valueTokens = (slots.flatMap { it.values } + restaurants.flatMap { it.menu })
                .map { it.token }.toSet()
            restaurants.forEach { restaurant ->
                require(restaurant.menu.isNotEmpty()) { "${restaurant.entityToken} has no menu" }
                val unknown = restaurant.slotTokens - slotTokens
                require(unknown.isEmpty()) {
                    "${restaurant.entityToken} references unknown option slots $unknown"
                }
                require(restaurant.slotTokens.contains(Slots.MAIN)) {
                    "${restaurant.entityToken} must offer the ${Slots.MAIN} slot"
                }
                restaurant.menu.forEach { item ->
                    val unknownTraits = item.traits - valueTokens
                    require(unknownTraits.isEmpty()) {
                        "${item.token} claims unknown traits $unknownTraits"
                    }
                }
            }
            events.forEach { catalogEvent ->
                require(restaurants.any { it.entityToken == catalogEvent.entityToken }) {
                    "${catalogEvent.eventToken} references an unknown restaurant"
                }
                require(catalogEvent.scopeToken in slotTokens) {
                    "${catalogEvent.eventToken} references an unknown option slot"
                }
            }
            requireUnambiguousPhrases(slots, restaurants)

            val scopePhrases = root.getJSONObject("scope_phrases").let { json ->
                ScopePhrases(
                    thisOrderOnly = stringList(json, "this_order_only"),
                    rememberForReuse = stringList(json, "remember_for_reuse"),
                )
            }
            val intents = root.getJSONObject("intent_phrases").let { json ->
                buildMap {
                    json.keys().forEach { key ->
                        put(intentOf(key) ?: error("unknown intent '$key'"), stringList(json, key))
                    }
                }
            }
            val unclear = root.getJSONArray("unclear_phrases").let { array ->
                (0 until array.length()).map { index ->
                    val json = array.getJSONObject(index)
                    UnclearPhrase(
                        phrase = label(json.getString("phrase")),
                        reason = label(json.getString("reason")),
                    )
                }
            }

            val ratings = root.getJSONArray("rating_values").let { array ->
                (0 until array.length()).map { index ->
                    val json = array.getJSONObject(index)
                    RatingValue(
                        token = token(json.getString("token")),
                        label = label(json.getString("label")),
                        sentiment = Sentiment.of(json.getString("sentiment")),
                        score = json.getInt("score"),
                    )
                }
            }
            val reviewPhrases = root.getJSONArray("review_phrases").let { array ->
                (0 until array.length()).map { index ->
                    val json = array.getJSONObject(index)
                    val slotToken = json.optNullable("slot")?.let(::token)
                    val implies = json.optNullable("implies_value")?.let(::token)
                    require(slotToken == null || slotToken in slotTokens) {
                        "review phrase '${json.getString("phrase")}' names unknown slot $slotToken"
                    }
                    require(implies == null || implies in valueTokens) {
                        "review phrase '${json.getString("phrase")}' implies unknown value $implies"
                    }
                    require(implies == null || slotToken != null) {
                        "review phrase '${json.getString("phrase")}' implies a value without a slot"
                    }
                    ReviewPhrase(
                        phrase = label(json.getString("phrase")),
                        slotToken = slotToken,
                        impliesValue = implies,
                        sentiment = Sentiment.of(json.getString("sentiment")),
                    )
                }
            }
            requireUnique(ratings.map { it.token }, "rating")
            val scaleMax = root.getInt("rating_scale_max")
            require(ratings.isNotEmpty()) { "catalog has no rating values" }
            require(ratings.all { it.score in 1..scaleMax }) {
                "a rating score must sit on the declared 1..$scaleMax scale"
            }
            requireUnique(reviewPhrases.map { it.phrase.replace(WHITESPACE, "") }, "review phrase")

            return SyntheticCatalog(
                currency = root.getString("currency"),
                deliveryAlias = root.getString("delivery_alias"),
                provenance = root.getString("provenance"),
                slots = slots,
                restaurants = restaurants,
                events = events,
                scopePhrases = scopePhrases,
                intentPhrases = intents,
                unclearPhrases = unclear,
                ratingValues = ratings,
                ratingScaleMax = scaleMax,
                reviewPhrases = reviewPhrases,
                snapshotDigest = Digest.utf8(text),
            )
        }

        /** One phrase must not mean two different things, or reading it is a guess. */
        private fun requireUnambiguousPhrases(
            slots: List<SlotDefinition>,
            restaurants: List<RestaurantDefinition>,
        ) {
            val owners = mutableMapOf<String, String>()
            val entries = slots.flatMap { slot -> slot.values.map { slot.scopeToken to it } } +
                restaurants.flatMap { r -> r.menu.map { Slots.MAIN to it } }
            entries.forEach { (scopeToken, value) ->
                value.phrases.forEach { phrase ->
                    val key = phrase.replace(WHITESPACE, "")
                    val owner = "$scopeToken/${value.token}"
                    val existing = owners.put(key, owner)
                    require(existing == null || existing == owner) {
                        "phrase '$phrase' is claimed by both $existing and $owner"
                    }
                }
            }
        }

        private fun intentOf(key: String): Intent? = when (key) {
            "recommend" -> Intent.RECOMMEND
            "revoke_auto_apply" -> Intent.REVOKE_AUTO_APPLY
            "delete_note" -> Intent.DELETE_NOTE
            "confirm_order" -> Intent.CONFIRM_ORDER
            "show_memory" -> Intent.SHOW_MEMORY
            else -> null
        }

        private fun slot(json: JSONObject): SlotDefinition {
            val values = json.getJSONArray("values").let { array ->
                (0 until array.length()).map { index -> value(array.getJSONObject(index)) }
            }
            return SlotDefinition(
                scopeToken = token(json.getString("scope_token")),
                label = label(json.getString("label")),
                kind = SlotKind.of(json.getString("kind")),
                priced = json.optBoolean("priced", false),
                required = json.optBoolean("required", true),
                valueKind = ValueKind.of(json.optString("value_kind", "enumerated")),
                values = values,
            )
        }

        private fun restaurant(json: JSONObject): RestaurantDefinition {
            val name = label(json.getString("name"))
            require(name.contains(SYNTHETIC_MARKER)) {
                "restaurant name '$name' must carry the synthetic marker '$SYNTHETIC_MARKER'"
            }
            val slotTokens = json.getJSONArray("option_slots").let { array ->
                (0 until array.length()).map { index -> token(array.getString(index)) }
            }
            val menu = json.getJSONArray("menu").let { array ->
                (0 until array.length()).map { index -> value(array.getJSONObject(index)) }
            }
            require(menu.all { it.etaMinutes != null && it.etaMinutes > 0 }) {
                "every menu item needs a positive synthetic estimate"
            }
            require(menu.all { it.priceDelta > 0 }) {
                "every menu item needs a positive synthetic price"
            }
            return RestaurantDefinition(
                entityToken = token(json.getString("entity_token")),
                name = name,
                slotTokens = slotTokens,
                menu = menu,
            )
        }

        private fun value(json: JSONObject): OptionValue {
            val priceDelta = json.optInt("price_delta", 0)
            require(priceDelta >= 0) { "a synthetic price cannot be negative" }
            return OptionValue(
                token = token(json.getString("token")),
                label = label(json.getString("label")),
                priceDelta = priceDelta,
                inStock = json.optBoolean("in_stock", true),
                etaMinutes = if (json.has("eta_minutes")) json.getInt("eta_minutes") else null,
                budgetLimit = if (json.has("budget_limit")) json.getInt("budget_limit") else null,
                etaLimitMinutes =
                if (json.has("eta_limit_minutes")) json.getInt("eta_limit_minutes") else null,
                phrases = json.optJSONArray("phrases")?.let { array ->
                    (0 until array.length()).map { index -> label(array.getString(index)) }
                }.orEmpty(),
                traits = json.optJSONArray("traits")?.let { array ->
                    (0 until array.length()).map { index -> token(array.getString(index)) }
                }.orEmpty(),
            )
        }

        private fun event(json: JSONObject): CatalogEvent = CatalogEvent(
            eventToken = token(json.getString("event_token")),
            entityToken = token(json.getString("entity_token")),
            scopeToken = token(json.getString("scope_token")),
            valueToken = token(json.getString("value_token")),
            description = json.getString("description"),
        )

        private fun stringList(json: JSONObject, key: String): List<String> =
            json.getJSONArray(key).let { array ->
                (0 until array.length()).map { index -> label(array.getString(index)) }
            }

        private fun token(value: String): String {
            require(TOKEN.matches(value)) { "'$value' is not a valid catalog token" }
            return value
        }

        /**
         * A visible label must not look like personal data. Synthetic data is the
         * point of the exercise, so this is enforced rather than assumed.
         */
        private fun label(value: String): String {
            require(value.isNotBlank()) { "a catalog label cannot be blank" }
            require(!value.contains("@")) { "'$value' looks like an address or account" }
            require(!PHONE_LIKE.containsMatchIn(value)) { "'$value' looks like a phone number" }
            return value
        }

        private fun JSONObject.optNullable(key: String): String? =
            if (!has(key) || isNull(key)) null else getString(key)

        private fun requireUnique(values: List<String>, what: String) {
            val duplicates = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            require(duplicates.isEmpty()) { "duplicate $what: $duplicates" }
        }
    }
}

/**
 * Prices the current draft.
 *
 * The core keeps a structural roll-up of the priced lines so a change to one line
 * is observable in the probe result. The amount a person reads is computed here,
 * from the same value tokens, because money is presentation, not decision logic.
 */
class DraftPricing(private val catalog: SyntheticCatalog) {

    data class Line(
        val fieldId: String,
        val label: String,
        val valueLabel: String,
        val amount: Int,
        val inStock: Boolean,
    )

    /**
     * The lines that carry an amount.
     *
     * The core marks every value priced against the current restaurant's catalog;
     * which of those actually costs money is catalog data, so it is decided here.
     */
    fun lines(state: ProductionState): List<Line> =
        state.fields.values
            .filter { field ->
                field.priced &&
                    field.slotId != AsprEngine.SLOT_TOTAL &&
                    catalog.slotOfFieldSlotId(field.slotId)?.priced == true
            }
            .sortedBy { it.fieldId }
            .map { field ->
                val entry = catalog.value(field.value)
                Line(
                    fieldId = field.fieldId,
                    label = catalog.slotLabel(field.slotId),
                    valueLabel = catalog.valueLabel(field.value),
                    amount = entry?.priceDelta ?: 0,
                    inStock = entry?.inStock ?: true,
                )
            }

    /** Total of the lines that are still valid. A sold-out line adds nothing. */
    fun total(state: ProductionState): Int =
        lines(state).filter { it.inStock }.sumOf { it.amount }

    /** Longest synthetic estimate among the chosen lines. */
    fun estimateMinutes(state: ProductionState): Int? =
        state.fields.values.mapNotNull { catalog.value(it.value)?.etaMinutes }.maxOrNull()

    /** Budget the user stated for this order, if any. */
    fun budgetLimit(state: ProductionState): Int? =
        state.fields.values.mapNotNull { catalog.value(it.value)?.budgetLimit }.minOrNull()

    /** Time limit the user stated for this order, if any. */
    fun etaLimitMinutes(state: ProductionState): Int? =
        state.fields.values.mapNotNull { catalog.value(it.value)?.etaLimitMinutes }.minOrNull()

    fun formatAmount(amount: Int): String = "%,d원".format(amount)
}
