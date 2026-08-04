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
    /** Kinds of food the sentence named — "마라", "분식" — rather than one dish. */
    val menuTypes: List<String> = emptyList(),
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
        val menuTypes = mutableListOf<String>()
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
            val kind = target.menuTypeToken
            if (kind != null) {
                // A kind is not an order line, so it never occupies the menu slot
                // and never competes with a dish the same sentence named. It only
                // says which dishes to show first.
                if (kind in menuTypes) return@forEach
                val span = findGapped(normalised, phrase, consumed, INSTRUCTION_GAP)
                    ?: return@forEach
                consume(consumed, span.start, span.length)
                menuTypes += kind
                return@forEach
            }
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

        // A sentence that says nothing about reuse is not an open question here.
        //
        // Reading language and deciding what to keep are different jobs, and only
        // the second one knows what is already remembered for the slot — which is
        // what makes the difference between a first answer worth keeping and a
        // change worth asking about. `ProductSurface.say` holds that decision;
        // this layer reports the scope the sentence actually stated and stops
        // there. [ValueScope.UNSTATED] is a reading, not a refusal.

        return Utterance(
            text = utterance,
            values = values,
            questions = questions,
            intents = intents,
            referencedSlots = referencedSlots.distinct(),
            unrecognised = leftoverWords(normalised, consumed),
            menuTypes = menuTypes.distinct(),
        )
    }

    override fun readReview(review: String, rating: String?): ReviewReading {
        val normalised = normalise(review)
        val consumed = BooleanArray(normalised.length)
        val candidates = mutableListOf<ReviewCandidate>()
        val observations = mutableListOf<String>()
        val conflictingSlots = mutableSetOf<String>()

        // A review often contains an explicit next-order request: "다음에는
        // 고수 빼 주세요" or "앞으로 국물 넉넉히". Reuse the option vocabulary
        // the product UI already accepts instead of maintaining a second review-
        // only copy. A future/desire marker is mandatory, so a historical phrase
        // such as "치즈 추가해서 먹었다" is not silently promoted to memory.
        directReviewPreferences(normalised, consumed)
            .groupBy { it.slotToken }
            .forEach { (slot, rawMatches) ->
                // Catalog phrases intentionally include prefixes ("수저 필요"
                // and "수저 필요 없어"). Within one overlapping surface the
                // longer phrase is the actual reading; spatially separate
                // opposing phrases remain a real conflict.
                val matches = rawMatches.filterNot { candidate ->
                    rawMatches.any { other ->
                            other.valueToken != candidate.valueToken &&
                            other.span.start <= candidate.span.start &&
                            other.span.endExclusive >= candidate.span.endExclusive &&
                            (other.span.length > candidate.span.length || other.score > candidate.score)
                    }
                }
                val existing = candidates.firstOrNull { it.slotToken == slot }
                val implied = (matches.map { it.valueToken } +
                    listOfNotNull(existing?.impliesValue)).distinct()
                if (slot in conflictingSlots || implied.size != 1) {
                    if (existing != null) {
                        candidates.remove(existing)
                        observations += existing.matchedText
                    }
                    conflictingSlots += slot
                    matches.forEach { consume(consumed, it.span.start, it.span.length) }
                    observations += matches.map { originalSlice(review, it.span) }
                    return@forEach
                }
                if (existing != null) {
                    // A second wording can reinforce the same direction. It is
                    // not another candidate, but it is still understood text.
                    matches.forEach { match ->
                        consume(consumed, match.span.start, match.span.length)
                    }
                    return@forEach
                }
                val best = matches.maxWithOrNull(
                    compareBy<DirectReviewMatch> { it.score }
                        .thenByDescending { it.span.length },
                ) ?: return@forEach
                consume(consumed, best.span.start, best.span.length)
                candidates += ReviewCandidate(
                    slotToken = slot,
                    impliesValue = best.valueToken,
                    matchedText = originalSlice(review, best.span),
                    sentiment = Sentiment.NEUTRAL,
                )
            }

        // Whole-phrase examples above remain the most specific readings. The
        // second pass composes an authored subject with an authored opinion cue:
        // "간이 꽤 강했어요", "염도가 너무 셌다", and "짠맛이 과했어"
        // therefore share one safe rule instead of three hand-written sentences.
        // Rules are still deterministic catalog data; no fuzzy string or model is
        // allowed to invent a value. If two rules for one slot imply different
        // values, the text is understood as conflicting but no memory candidate is
        // produced.
        val ruleMatches = catalog.reviewRules.mapNotNull { rule ->
            bestRuleMatch(normalised, consumed, rule)
        }
        ruleMatches
            .filter { it.rule.impliesValue != null && it.rule.slotToken != null }
            .groupBy { it.rule.slotToken!! }
            .forEach { (slot, matches) ->
                val existing = candidates.firstOrNull { it.slotToken == slot }
                val implied = (matches.mapNotNull { it.rule.impliesValue } +
                    listOfNotNull(existing?.impliesValue)).distinct()
                if (slot in conflictingSlots || implied.size != 1) {
                    if (existing != null) {
                        candidates.remove(existing)
                        observations += existing.matchedText
                    }
                    conflictingSlots += slot
                    matches.forEach { match ->
                        consume(consumed, match.span.start, match.span.length)
                    }
                    observations += matches.map { originalSlice(review, it.span) }
                    return@forEach
                }
                if (existing != null) {
                    matches.forEach { match ->
                        consume(consumed, match.span.start, match.span.length)
                    }
                    return@forEach
                }
                val best = matches.maxWithOrNull(
                    compareBy<ReviewRuleMatch> { it.score }
                        .thenByDescending { it.span.length },
                ) ?: return@forEach
                consume(consumed, best.span.start, best.span.length)
                candidates += ReviewCandidate(
                    slotToken = slot,
                    impliesValue = implied.single(),
                    matchedText = originalSlice(review, best.span),
                    sentiment = best.rule.sentiment,
                )
            }

        ruleMatches
            .filter { it.rule.impliesValue == null }
            .sortedByDescending { it.score }
            .forEach { match ->
                if (!isFree(consumed, match.span.start until match.span.endExclusive)) return@forEach
                consume(consumed, match.span.start, match.span.length)
                observations += originalSlice(review, match.span)
            }

        // Exact examples are a fallback after the more informative authored
        // rules. A short exact stem such as "너무 맵" must not consume the core
        // of the longer "맵찔이가 먹기에는 너무 맵" reading first.
        catalog.reviewPhrases
            .filter { it.slotToken != null && it.impliesValue != null }
            .sortedByDescending { normalise(it.phrase).length }
            .forEach { entry ->
                val span = findGapped(normalised, entry.phrase, consumed, REVIEW_GAP)
                    ?: return@forEach
                consume(consumed, span.start, span.length)
                val slot = entry.slotToken
                val implies = entry.impliesValue
                if (slot != null && implies != null) {
                    val existing = candidates.firstOrNull { it.slotToken == slot }
                    if (existing != null && existing.impliesValue != implies) {
                        candidates.remove(existing)
                        conflictingSlots += slot
                        observations += existing.matchedText
                        observations += entry.phrase
                    } else if (existing == null && slot !in conflictingSlots) {
                        candidates += ReviewCandidate(
                            slotToken = slot,
                            impliesValue = implies,
                            matchedText = entry.phrase,
                            sentiment = entry.sentiment,
                        )
                    }
                }
            }

        // Specific and generic authored observations claim whatever no safe
        // preference rule used. Longest-first still settles overlaps such as
        // "국물은 괜찮았" versus "좋았".
        catalog.reviewPhrases
            .filter { it.slotToken == null || it.impliesValue == null }
            .sortedByDescending { normalise(it.phrase).length }
            .forEach { entry ->
                val span = findGapped(normalised, entry.phrase, consumed, REVIEW_GAP)
                    ?: return@forEach
                consume(consumed, span.start, span.length)
                observations += entry.phrase
            }

        return ReviewReading(
            text = review,
            ratingToken = rating?.takeIf { token -> catalog.rating(token) != null },
            candidates = candidates,
            observations = observations,
            // Review prose is not an instruction grammar. Report only meaningful
            // unmatched words, not particles and endings left around a correctly
            // matched stem ("간이 ... 셌어요" used to report "이 어요").
            unrecognised = leftoverReviewWords(review, consumed),
        )
    }

    // ------------------------------------------------------------- internals

    /**
     * What one phrase points at: a slot value or a dish (both carry a
     * [valueToken]), or a whole kind of food (which carries [menuTypeToken]).
     */
    private data class PhraseTarget(
        val scopeToken: String,
        val valueToken: String,
        val menuTypeToken: String? = null,
    )

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
        // Before a restaurant is picked, every restaurant's menu is in scope: the
        // declared product answers "마라탕" with the restaurants that serve it, and
        // indexing only the current restaurant's menu meant a dish named on the
        // opening screen matched nothing at all. `requireUnambiguousPhrases` already
        // holds a menu phrase to one menu across every restaurant, so widening the
        // index here cannot make a phrase ambiguous.
        val menus = context.restaurant?.menu ?: catalog.restaurants.flatMap { it.menu }
        menus.forEach { item ->
            item.phrases.forEach { phrase ->
                entries += phrase to PhraseTarget(Slots.MAIN, item.token)
            }
        }
        // Kinds of food share this index rather than getting a pass of their own,
        // so the longest-first order settles "마라탕" against "마라" the same way it
        // settles every other overlap: the more specific phrase wins.
        catalog.menuTypes.forEach { type ->
            type.phrases.forEach { phrase ->
                entries += phrase to PhraseTarget(
                    scopeToken = Slots.MAIN,
                    valueToken = "",
                    menuTypeToken = type.token,
                )
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
                    // A quantity can precede the amount — "2인분 1만5천원 이하로". The
                    // pattern also matches a bare number, so taking only the first free
                    // match and abandoning the slot when it does not parse dropped the
                    // amount the user actually stated. A dropped budget is a dropped
                    // constraint, and it disappears silently whenever some other slot in
                    // the same sentence did resolve.
                    val (found, amount) = AMOUNT.findAll(text)
                        .filter { isFree(consumed, it.range) }
                        .firstNotNullOfOrNull { match -> amountOf(match)?.let { match to it } }
                        ?: return@forEach
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
                    // Same reason as AMOUNT above.
                    val (found, minutes) = DURATION.findAll(text)
                        .filter { isFree(consumed, it.range) }
                        .firstNotNullOfOrNull { match -> minutesOf(match)?.let { match to it } }
                        ?: return@forEach
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

    private data class ReviewRuleMatch(
        val rule: ReviewRule,
        val span: Span,
        val score: Int,
    )

    private data class DirectReviewMatch(
        val slotToken: String,
        val valueToken: String,
        val span: Span,
        val score: Int,
    )

    /** Explicit future/desire statements mapped through the normal option lexicon. */
    private fun directReviewPreferences(
        text: String,
        consumed: BooleanArray,
    ): List<DirectReviewMatch> {
        val markers = REVIEW_FUTURE_MARKERS.flatMap { marker ->
            val compact = normalise(marker)
            occurrences(text, compact, consumed).map { at -> Span(at, at + compact.length) }
        }
        if (markers.isEmpty()) return emptyList()

        val matches = mutableListOf<DirectReviewMatch>()
        catalog.slots
            .filter { slot ->
                slot.scopeToken.startsWith("option.") &&
                    slot.scopeToken != Slots.MAIN &&
                    slot.valueKind == ValueKind.ENUMERATED
            }
            .forEach { slot ->
                slot.values.forEach { value ->
                    value.phrases.sortedByDescending { normalise(it).length }.forEach { phrase ->
                        val valueSpan = findGapped(text, phrase, consumed, REVIEW_GAP)
                            ?: return@forEach
                        val nearbyMarkers = markers.filter {
                            spanDistance(it, valueSpan) <= REVIEW_FUTURE_MARGIN
                        }
                        if (nearbyMarkers.isEmpty()) return@forEach
                        val distance = nearbyMarkers.minOf { spanDistance(it, valueSpan) }
                        val span = Span(
                            minOf(valueSpan.start, nearbyMarkers.minOf { it.start }),
                            maxOf(valueSpan.endExclusive, nearbyMarkers.maxOf { it.endExclusive }),
                        )
                        matches += DirectReviewMatch(
                            slotToken = slot.scopeToken,
                            valueToken = value.token,
                            span = span,
                            score = normalise(phrase).length - distance +
                                if (normalise(phrase) in text.substring(
                                        valueSpan.start,
                                        valueSpan.endExclusive,
                                    )
                                ) {
                                    REVIEW_EXACT_VALUE_BONUS
                                } else {
                                    0
                                },
                        )
                    }
                }
            }
        return matches
    }

    private fun spanDistance(left: Span, right: Span): Int = when {
        left.endExclusive < right.start -> right.start - left.endExclusive
        right.endExclusive < left.start -> left.start - right.endExclusive
        else -> 0
    }

    /** Best nearby subject/cue pair for one compositional review rule. */
    private fun bestRuleMatch(
        text: String,
        consumed: BooleanArray,
        rule: ReviewRule,
    ): ReviewRuleMatch? {
        var best: ReviewRuleMatch? = null
        for (rawSubject in rule.subjects) {
            val subject = normalise(rawSubject)
            for (subjectAt in occurrences(text, subject, consumed)) {
                for (rawCue in rule.cues) {
                    val cue = normalise(rawCue)
                    for (cueAt in occurrences(text, cue, consumed)) {
                        val subjectEnd = subjectAt + subject.length
                        val cueEnd = cueAt + cue.length
                        // A word contained inside the cue is not a subject/cue
                        // pair. Without this guard, "싱거" could satisfy both
                        // sides of one rule and manufacture a direction from one
                        // isolated adjective.
                        if (subjectAt < cueEnd && cueAt < subjectEnd) continue
                        val gap = when {
                            subjectEnd < cueAt -> cueAt - subjectEnd
                            cueEnd < subjectAt -> subjectAt - cueEnd
                            else -> 0
                        }
                        if (gap > REVIEW_RULE_GAP) continue
                        val span = Span(minOf(subjectAt, cueAt), maxOf(subjectEnd, cueEnd))
                        if (isBlocked(text, span, rule)) continue
                        // Naming both the subject and its cue carries more
                        // information than a shorter subject-omitted fallback.
                        // Distance is already bounded above; do not reward a
                        // clipped cue for leaving the surrounding subject behind.
                        val score = subject.length + cue.length + REVIEW_PAIR_BONUS
                        val candidate = ReviewRuleMatch(rule, span, score)
                        if (best == null || candidate.score > best!!.score ||
                            (candidate.score == best!!.score && candidate.span.length < best!!.span.length)
                        ) {
                            best = candidate
                        }
                    }
                }
            }
        }
        // Korean commonly drops an already-obvious subject. Only authored,
        // directional surfaces enter this pass; a bare ambiguous adjective is
        // never promoted merely because it resembles another word.
        for (rawCue in rule.standaloneCues) {
            val cue = normalise(rawCue)
            for (at in occurrences(text, cue, consumed)) {
                val span = Span(at, at + cue.length)
                if (isBlocked(text, span, rule)) continue
                val candidate = ReviewRuleMatch(rule, span, cue.length + REVIEW_STANDALONE_BONUS)
                if (best == null || candidate.score > best!!.score ||
                    (candidate.score == best!!.score && candidate.span.length < best!!.span.length)
                ) {
                    best = candidate
                }
            }
        }
        return best
    }

    private fun isBlocked(text: String, span: Span, rule: ReviewRule): Boolean {
        val nearbyStart = maxOf(0, span.start - REVIEW_BLOCKER_MARGIN)
        val nearbyEnd = minOf(text.length, span.endExclusive + REVIEW_BLOCKER_MARGIN)
        val nearby = text.substring(nearbyStart, nearbyEnd)
        return rule.blockers.any { normalise(it) in nearby }
    }

    private fun occurrences(text: String, needle: String, consumed: BooleanArray): List<Int> {
        if (needle.isEmpty()) return emptyList()
        val out = mutableListOf<Int>()
        var from = 0
        while (from <= text.length - needle.length) {
            val at = text.indexOf(needle, from)
            if (at < 0) break
            if (isFree(consumed, at until at + needle.length)) out += at
            from = at + 1
        }
        return out
    }

    /** Restores spaces and particles from the user's text for visible provenance. */
    private fun originalSlice(original: String, compactSpan: Span): String {
        var compactIndex = 0
        var start = -1
        var end = original.length
        original.forEachIndexed { originalIndex, ch ->
            if (ch.isWhitespace()) return@forEachIndexed
            if (compactIndex == compactSpan.start && start < 0) start = originalIndex
            compactIndex += 1
            if (compactIndex == compactSpan.endExclusive) {
                end = originalIndex + 1
                return@forEachIndexed
            }
        }
        return if (start >= 0 && start < end) original.substring(start, end).trim() else original.trim()
    }

    /**
     * Meaningful review words that no rule claimed.
     *
     * Matching uses a whitespace-free string, but review feedback must not glue
     * every remaining syllable into one alarming pseudo-word. This restores the
     * user's word boundaries, removes only well-known Korean grammatical debris,
     * and keeps unknown nouns/adjectives visible so the parser remains honest.
     */
    private fun leftoverReviewWords(original: String, consumed: BooleanArray): List<String> {
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        var compactIndex = 0

        fun flush() {
            if (current.isNotEmpty()) {
                pieces += current.toString()
                current.clear()
            }
        }

        original.forEach { ch ->
            if (ch.isWhitespace() || !ch.isLetterOrDigit()) {
                flush()
                if (!ch.isWhitespace()) compactIndex += 1
            } else {
                val free = compactIndex >= consumed.size || !consumed[compactIndex]
                if (free) current.append(ch) else flush()
                compactIndex += 1
            }
        }
        flush()

        return pieces.map(::trimReviewGrammar)
            .filter { it.length >= MIN_LEFTOVER && it !in REVIEW_NOISE_WORDS }
            .distinct()
    }

    private fun trimReviewGrammar(raw: String): String {
        var value = raw.lowercase()
        var changed: Boolean
        do {
            changed = false
            REVIEW_DISCOURSE_PREFIXES.firstOrNull { prefix ->
                value.length >= prefix.length && value.startsWith(prefix)
            }?.let { prefix ->
                value = value.removePrefix(prefix)
                changed = true
            }
            REVIEW_GRAMMAR_SUFFIXES.firstOrNull { suffix ->
                value.length >= suffix.length && value.endsWith(suffix)
            }?.let { suffix ->
                value = value.removeSuffix(suffix)
                changed = true
            }
        } while (changed)
        return value
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
            val words = mutableListOf(first until cursor)
            var matched = true
            for (index in 1 until parts.size) {
                val at = text.indexOf(parts[index], cursor)
                // Characters another reading already claimed are not intervening
                // noise. "고수는 항상 빼줘" states its reuse scope between the value's
                // own words, and charging `항상` to the gap budget lost the value
                // outright even though every word of it was present.
                if (at < 0 || (cursor until at).count { !consumed[it] } > maxGap) {
                    matched = false
                    break
                }
                words += at until (at + parts[index].length)
                cursor = at + parts[index].length
            }
            // Only the matched words themselves have to be unclaimed; what sits
            // between them belongs to whichever reading already took it.
            if (matched && words.all { isFree(consumed, it) }) return Span(first, cursor)
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

        /** Subject and opinion cue may have particles and a short adverb between them. */
        const val REVIEW_RULE_GAP = 8

        /** Negation close to a match reverses or neutralises the rule. */
        const val REVIEW_BLOCKER_MARGIN = 5

        /** A future/desire marker may sit a few words from the option phrase. */
        const val REVIEW_FUTURE_MARGIN = 12

        /** An authored subject/cue pair is more specific than a shorter fallback. */
        const val REVIEW_PAIR_BONUS = 2

        /** Standalone surfaces compete by their authored length. */
        const val REVIEW_STANDALONE_BONUS = 0

        /** Prefer value words that actually occur contiguously over gapped lookalikes. */
        const val REVIEW_EXACT_VALUE_BONUS = 20
        val WHITESPACE = Regex("\\s+")
        val PUNCTUATION = setOf('.', ',', '!', '?', '~', '·')

        val REVIEW_FUTURE_MARKERS = listOf(
            "다음에는", "다음엔", "다음번에는", "다음부턴", "담에는", "담엔",
            "앞으로", "이제부터", "하고 싶", "했으면", "좋겠", "주세요", "해줘", "부탁",
        )

        val REVIEW_DISCOURSE_PREFIXES = listOf(
            "개인적으로", "그런데", "그리고", "하지만", "그래도", "근데", "다만",
            "정말", "진짜", "완전", "엄청", "되게", "너무", "조금", "약간", "살짝", "좀",
        )

        // Longest first. These are grammatical shells only; stripping one must
        // leave a meaningful stem, otherwise REVIEW_NOISE_WORDS handles it.
        val REVIEW_GRAMMAR_SUFFIXES = listOf(
            "이었더라고요", "였더라고요", "하더라고요", "더라고요", "했었습니다",
            "이었습니다", "였습니다", "했어요", "했네요", "했습니다", "이었어요", "였어요",
            "같아요", "같네요", "듯해요", "던데요", "었어요", "았어요", "었네요", "았네요",
            "입니다", "네요", "군요", "어요", "아요", "예요", "이에요", "였음", "했음",
            "이었다", "였다", "했다", "한다", "해서는", "해서", "어서", "아서", "니까", "지만", "는데",
            "은데", "던데", "으로", "에서", "부터", "까지", "에게", "한테", "처럼",
            "이라", "라서", "이고", "하고", "이나", "라도", "보다", "만큼",
            "은", "는", "이", "가", "을", "를", "도", "만", "에", "와", "과", "로", "요",
        )

        val REVIEW_NOISE_WORDS = setOf(
            "그리고", "그런데", "하지만", "그래도", "근데", "다만", "또", "정말", "진짜",
            "완전", "엄청", "되게", "너무", "조금", "약간", "살짝", "좀", "그냥", "전체적",
            "처음", "나중", "결국", "계속", "거의", "아주", "제법", "꽤", "다시",
            "다음", "다음번", "앞으로", "이제", "이번", "오늘", "어제",
            "먹", "왔", "주문", "느낌", "생각", "정도", "부분", "편",
            "져서", "돼서", "되어서",
        )

        /** `18000원`, `1만8천원`, `2만원`, `18,000원`. */
        val AMOUNT = Regex("""([0-9][0-9,]*)(만)?(?:([0-9][0-9,]*)천)?원?""")

        /** `30분`, `1시간`. */
        val DURATION = Regex("""([0-9]{1,3})\s*(시간)?분?""")
    }
}
