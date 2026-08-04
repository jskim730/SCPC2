package com.scpc.deliveryagent.delivery

/**
 * Turns what the user typed into candidate structured updates.
 *
 * Two rules make this safe to put in front of the decision engine:
 *
 *  * the vocabulary is catalog data, not code, so the engine still decides on
 *    identity and this layer only recognises words,
 *  * anything ambiguous, or any reuse scope the user did not state, becomes a
 *    question rather than a value. A shorter conversation that violated the
 *    user's instruction is not the goal.
 *
 * [PreferenceIntake] is the seam a model-backed implementation would replace.
 * The submitted release ships [RuleBasedIntake], which needs no network, no
 * credential and no inference budget, and returns the same answer every run so
 * the paired comparison stays reproducible.
 */
interface PreferenceIntake {

    /** True when this implementation calls a model or a remote backend. */
    val usesModel: Boolean

    /** Model or backend calls made so far. Stays 0 for the deterministic reading. */
    val invocations: Int

    fun read(utterance: String, context: IntakeContext): Utterance

    /**
     * Reads a review of an order that already happened.
     *
     * A review is not an instruction for the current draft, so nothing here is
     * applied. It produces candidates the user is then asked about, because a
     * remark about one bowl of soup is not permission to change every future
     * order.
     */
    fun readReview(review: String, rating: String?): ReviewReading
}

/** Something a review said that could become memory, if the user agrees. */
data class ReviewCandidate(
    val slotToken: String,
    val impliesValue: String,
    val matchedText: String,
    val sentiment: Sentiment,
)

/** The full reading of one review. */
data class ReviewReading(
    val text: String,
    val ratingToken: String?,
    /** Remarks that name an option and a value that would answer them. */
    val candidates: List<ReviewCandidate>,
    /** Remarks that were understood but point at nothing actionable. */
    val observations: List<String>,
    val unrecognised: List<String>,
)

/** What the reader is allowed to know about the current order. */
data class IntakeContext(
    val restaurant: RestaurantDefinition?,
    /** Slots the current menu offers, so a value for an absent option is not invented. */
    val offeredSlots: Set<String>,
)

/** How long a value the user stated should last. */
enum class ValueScope {
    /** Applies to this order only. */
    THIS_ORDER_ONLY,

    /** The user asked for it to be reused later. */
    REMEMBER_FOR_REUSE,

    /** The user did not say, so it has to be asked. */
    UNSTATED,
}

/** A structured update the reader recognised in the utterance. */
data class ReadValue(
    val scopeToken: String,
    val valueToken: String,
    val scope: ValueScope,
    /** The exact words this came from, shown to the user as provenance. */
    val matchedText: String,
)

/** Something the reader could not settle, phrased as a question. */
data class OpenQuestion(
    val about: String,
    val question: String,
    val matchedText: String,
)

/** Actions the user asked for that are not values. */
enum class Intent { RECOMMEND, REVOKE_AUTO_APPLY, DELETE_NOTE, CONFIRM_ORDER, SHOW_MEMORY }

/** The full reading of one utterance. */
data class Utterance(
    val text: String,
    val values: List<ReadValue>,
    val questions: List<OpenQuestion>,
    val intents: List<Intent>,
    /** Slots the user referred to for an action, such as which permission to revoke. */
    val referencedSlots: List<String>,
    val unrecognised: List<String>,
) {
    val understoodSomething: Boolean
        get() = values.isNotEmpty() || intents.isNotEmpty() || questions.isNotEmpty()
}

/**
 * Deterministic reading over the catalog's own phrase lists.
 *
 * Longest phrase first, with each match consuming its span, so "맵지 않게" is read as
 * mild rather than as a mention of heat. Nothing is inferred from a word the
 * catalog does not list.
 */
class RuleBasedIntake(private val catalog: SyntheticCatalog) : PreferenceIntake {

    override val usesModel: Boolean = false
    override val invocations: Int = 0

    override fun read(utterance: String, context: IntakeContext): Utterance {
        val normalised = normalise(utterance)
        val consumed = BooleanArray(normalised.length)

        val intents = mutableListOf<Intent>()
        val values = mutableListOf<ReadValue>()
        val questions = mutableListOf<OpenQuestion>()
        val referencedSlots = mutableListOf<String>()

        // An action phrase is read first: "자동으로 정하지 마" is a withdrawal, not a
        // preference, and the option it names must not also be stored as a value.
        catalog.intentPhrases.forEach { (intent, phrases) ->
            phrases.sortedByDescending { it.length }.forEach { phrase ->
                val at = indexOfFree(normalised, normalise(phrase), consumed)
                if (at >= 0) {
                    consume(consumed, at, normalise(phrase).length)
                    if (intent !in intents) intents += intent
                }
            }
        }

        val scope = readScope(normalised, consumed)

        // Slot references for actions, matched by the slot's own label.
        catalog.slots.sortedByDescending { it.label.length }.forEach { slot ->
            val at = indexOfFree(normalised, normalise(slot.label), consumed)
            if (at >= 0 && intents.isNotEmpty()) {
                consume(consumed, at, normalise(slot.label).length)
                referencedSlots += slot.scopeToken
            }
        }

        // Amounts and durations before enumerated phrases, so "1만8천원" is not
        // partially eaten by another phrase.
        readAmounts(normalised, consumed, scope, values)

        // Enumerated values, longest phrase first across every slot and menu.
        phraseIndex(context).forEach { (phrase, target) ->
            val span = findGapped(normalised, phrase, consumed, INSTRUCTION_GAP) ?: return@forEach
            if (values.any { it.scopeToken == target.scopeToken }) return@forEach
            consume(consumed, span.start, span.length)
            if (target.scopeToken !in context.offeredSlots) {
                questions += OpenQuestion(
                    about = target.scopeToken,
                    question = "${context.restaurant?.name ?: "이 식당"}에는 " +
                        "${catalog.slot(target.scopeToken).label} 선택이 없습니다. 다른 조건으로 바꿀까요?",
                    matchedText = phrase,
                )
                return@forEach
            }
            values += ReadValue(
                scopeToken = target.scopeToken,
                valueToken = target.valueToken,
                scope = scope,
                matchedText = phrase,
            )

        }

        // Phrases the catalog marks as too vague to act on.
        catalog.unclearPhrases.forEach { unclear ->
            val phrase = normalise(unclear.phrase)
            val at = indexOfFree(normalised, phrase, consumed)
            if (at >= 0) {
                consume(consumed, at, phrase.length)
                questions += OpenQuestion(
                    about = "unclear",
                    question = "'${unclear.phrase}'는 ${unclear.reason} 어떻게 할까요?",
                    matchedText = unclear.phrase,
                )
            }
        }

        // A stored preference needs a stated reuse scope. Guessing it would be the
        // one thing the mission says not to do.
        //
        // The menu slot is exempt. It carries the item being ordered, never a
        // preference to reuse, so asking whether to remember it is meaningless —
        // and the question would leave the slot unresolved, dropping the order
        // line the sentence just asked for.
        if (scope == ValueScope.UNSTATED) {
            values.filter {
                catalog.slot(it.scopeToken).kind == SlotKind.MENU_OPTION &&
                    it.scopeToken != Slots.MAIN
            }
                .forEach { value ->
                    questions += OpenQuestion(
                        about = value.scopeToken,
                        question = "${catalog.slot(value.scopeToken).label} " +
                            "'${catalog.valueLabel(value.valueToken)}'는 이번 주문만 적용할까요, " +
                            "다음 주문에도 기억할까요?",
                        matchedText = value.matchedText,
                    )
                }
        }

        return Utterance(
            text = utterance,
            values = values,
            questions = questions,
            intents = intents,
            referencedSlots = referencedSlots.distinct(),
            unrecognised = leftoverWords(normalised, consumed),
        )
    }

    override fun readReview(review: String, rating: String?): ReviewReading {
        val normalised = normalise(review)
        val consumed = BooleanArray(normalised.length)
        val candidates = mutableListOf<ReviewCandidate>()
        val observations = mutableListOf<String>()

        // Longest first, so "국물은 괜찮았" is not read as the shorter "좋았".
        catalog.reviewPhrases
            .sortedByDescending { normalise(it.phrase).length }
            .forEach { entry ->
                val span = findGapped(normalised, entry.phrase, consumed, REVIEW_GAP)
                    ?: return@forEach
                consume(consumed, span.start, span.length)
                val slot = entry.slotToken
                val implies = entry.impliesValue
                if (slot != null && implies != null) {
                    if (candidates.any { it.slotToken == slot }) return@forEach
                    candidates += ReviewCandidate(
                        slotToken = slot,
                        impliesValue = implies,
                        matchedText = entry.phrase,
                        sentiment = entry.sentiment,
                    )
                } else {
                    observations += entry.phrase
                }
            }

        return ReviewReading(
            text = review,
            ratingToken = rating?.takeIf { token -> catalog.rating(token) != null },
            candidates = candidates,
            observations = observations,
            unrecognised = leftoverWords(normalised, consumed),
        )
    }

    // ------------------------------------------------------------- internals

    private data class PhraseTarget(val scopeToken: String, val valueToken: String)

    /** Every phrase the catalog knows, longest first, including this menu's items. */
    private fun phraseIndex(context: IntakeContext): List<Pair<String, PhraseTarget>> {
        val entries = mutableListOf<Pair<String, PhraseTarget>>()
        catalog.slots.forEach { slot ->
            slot.values.forEach { value ->
                value.phrases.forEach { phrase ->
                    entries += phrase to PhraseTarget(slot.scopeToken, value.token)
                }
            }
        }
        context.restaurant?.menu?.forEach { item ->
            item.phrases.forEach { phrase ->
                entries += phrase to PhraseTarget(Slots.MAIN, item.token)
            }
        }
        return entries.filter { it.first.isNotBlank() }
            .sortedByDescending { normalise(it.first).length }
    }

    private fun readScope(text: String, consumed: BooleanArray): ValueScope {
        catalog.scopePhrases.rememberForReuse.sortedByDescending { it.length }.forEach { phrase ->
            val at = indexOfFree(text, normalise(phrase), consumed)
            if (at >= 0) {
                consume(consumed, at, normalise(phrase).length)
                return ValueScope.REMEMBER_FOR_REUSE
            }
        }
        catalog.scopePhrases.thisOrderOnly.sortedByDescending { it.length }.forEach { phrase ->
            val at = indexOfFree(text, normalise(phrase), consumed)
            if (at >= 0) {
                consume(consumed, at, normalise(phrase).length)
                return ValueScope.THIS_ORDER_ONLY
            }
        }
        return ValueScope.UNSTATED
    }

    /**
     * Reads a synthetic amount and a synthetic duration.
     *
     * Only digit-led forms are read. A word-only amount is left unrecognised so it
     * becomes a question instead of a guess.
     */
    private fun readAmounts(
        text: String,
        consumed: BooleanArray,
        scope: ValueScope,
        into: MutableList<ReadValue>,
    ) {
        catalog.slots.forEach { slot ->
            when (slot.valueKind) {
                ValueKind.AMOUNT -> {
                    val found = AMOUNT.findAll(text).firstOrNull { isFree(consumed, it.range) }
                        ?: return@forEach
                    val amount = amountOf(found) ?: return@forEach
                    consume(consumed, found.range.first, found.value.length)
                    into += ReadValue(
                        scopeToken = slot.scopeToken,
                        // Amount slots accept any well-formed amount, so the token is
                        // derived from the number the user actually said.
                        valueToken = "${slot.scopeToken.substringAfterLast('.')}.$amount",
                        scope = scope,
                        matchedText = found.value.trim(),
                    )
                }

                ValueKind.DURATION_MINUTES -> {
                    val found = DURATION.findAll(text).firstOrNull { isFree(consumed, it.range) }
                        ?: return@forEach
                    val minutes = minutesOf(found) ?: return@forEach
                    consume(consumed, found.range.first, found.value.length)
                    into += ReadValue(
                        scopeToken = slot.scopeToken,
                        valueToken = "${slot.scopeToken.substringAfterLast('.')}.$minutes",
                        scope = scope,
                        matchedText = found.value.trim(),
                    )
                }

                ValueKind.ENUMERATED -> Unit
            }
        }
    }

    private fun amountOf(match: MatchResult): Int? {
        val digits = match.groupValues[1].replace(",", "").toIntOrNull() ?: return null
        val tenThousands = match.groupValues[2]
        val thousands = match.groupValues[3].replace(",", "").toIntOrNull() ?: 0
        return when {
            tenThousands.isNotEmpty() -> digits * 10_000 + thousands * 1_000
            digits >= 1_000 -> digits
            else -> null
        }?.takeIf { it in 1_000..1_000_000 }
    }

    private fun minutesOf(match: MatchResult): Int? {
        val value = match.groupValues[1].toIntOrNull() ?: return null
        val hours = match.groupValues[2].isNotEmpty()
        val minutes = if (hours) value * 60 else value
        return minutes.takeIf { it in 5..300 }
    }

    private fun normalise(value: String): String = value.replace(WHITESPACE, "").lowercase()

    /** A matched region of the utterance. */
    private data class Span(val start: Int, val endExclusive: Int) {
        val length: Int get() = endExclusive - start
    }

    /**
     * Finds a phrase whose words appear in order, allowing a few characters
     * between them.
     *
     * Korean puts particles and adverbs between the words a phrase is written
     * with: the catalog says "간이 셌" and a person types "간이 좀 셌어". Matching
     * word by word with a small gap covers that without the phrase list having to
     * enumerate every filler.
     */
    private fun findGapped(
        text: String,
        phrase: String,
        consumed: BooleanArray,
        maxGap: Int,
    ): Span? {
        val parts = phrase.trim().split(WHITESPACE)
            .map(::normalise)
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var searchFrom = 0
        while (searchFrom <= text.length) {
            val first = text.indexOf(parts[0], searchFrom)
            if (first < 0) return null
            var cursor = first + parts[0].length
            var matched = true
            for (index in 1 until parts.size) {
                val at = text.indexOf(parts[index], cursor)
                if (at < 0 || at - cursor > maxGap) {
                    matched = false
                    break
                }
                cursor = at + parts[index].length
            }
            if (matched && isFree(consumed, first until cursor)) return Span(first, cursor)
            searchFrom = first + 1
        }
        return null
    }

    private fun indexOfFree(text: String, needle: String, consumed: BooleanArray): Int {
        if (needle.isEmpty()) return -1
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return -1
            if (isFree(consumed, at until at + needle.length)) return at
            from = at + 1
        }
    }

    private fun isFree(consumed: BooleanArray, range: IntRange): Boolean =
        range.all { it < consumed.size && !consumed[it] }

    private fun consume(consumed: BooleanArray, at: Int, length: Int) {
        for (index in at until minOf(at + length, consumed.size)) consumed[index] = true
    }

    /** Runs of text no phrase claimed, reported so nothing is silently dropped. */
    private fun leftoverWords(text: String, consumed: BooleanArray): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        text.forEachIndexed { index, ch ->
            if (!consumed[index] && !ch.isWhitespace() && ch !in PUNCTUATION) {
                current.append(ch)
            } else if (current.isNotEmpty()) {
                out += current.toString()
                current.clear()
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out.filter { it.length >= MIN_LEFTOVER }
    }

    private companion object {
        const val MIN_LEFTOVER = 2

        /** Characters tolerated between the words of an instruction phrase. */
        const val INSTRUCTION_GAP = 2

        /** Reviews are freer prose, so a wider gap is allowed there. */
        const val REVIEW_GAP = 4
        val WHITESPACE = Regex("\\s+")
        val PUNCTUATION = setOf('.', ',', '!', '?', '~', '·')

        /** `18000원`, `1만8천원`, `2만원`, `18,000원`. */
        val AMOUNT = Regex("""([0-9][0-9,]*)(만)?(?:([0-9][0-9,]*)천)?원?""")

        /** `30분`, `1시간`. */
        val DURATION = Regex("""([0-9]{1,3})\s*(시간)?분?""")
    }
}
