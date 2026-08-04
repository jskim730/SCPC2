package com.scpc.deliveryagent.delivery

import com.scpc.deliveryagent.core.AsprEngine
import com.scpc.deliveryagent.core.FactKind
import com.scpc.deliveryagent.core.Permission
import com.scpc.deliveryagent.core.ProductionState
import com.scpc.deliveryagent.core.Relevance

/**
 * Deterministic menu recommendation.
 *
 * Candidates are scored from four sources, each of which the user can trace on
 * screen: the conditions stated for this order, the preferences that are still
 * valid and permitted for this target, the satisfaction results that arrived after
 * an earlier order, and the ratings the user left on this restaurant's own menus.
 * Nothing is inferred from a value the catalog does not carry, and a candidate is
 * never auto-confirmed: the user picks.
 *
 * Ranking is a pure function of state and catalog, and it is available to both
 * comparison arms, so the difference between them stays attributable to ASPR
 * alone rather than to ordinary order history.
 *
 * A star rating is different from a review remark. A remark names an option and
 * only ever becomes memory at the scope the user approved. A rating is the
 * user's own record of that exact restaurant-menu pair, and it ranks **below
 * everything the user asked for**: it never outranks a current condition or a
 * stored preference, it only orders candidates those sources leave tied, damped
 * by how few ratings exist (count-aware smoothing) so one tap cannot swing the
 * list. It never blocks a choice and never applies an option. Both comparison
 * arms rank with it identically, so no measured gain comes from it.
 *
 * Ratings used are the user's own past ratings on this device. No other person's
 * ratings or profile are involved.
 */
class Recommender(private val catalog: SyntheticCatalog) {

    /** Why a candidate is where it is, in words the user can act on. */
    data class Reason(val badge: String, val detail: String)

    data class Candidate(
        val valueToken: String,
        val label: String,
        val amount: Int,
        val estimateMinutes: Int,
        val score: Int,
        val reasons: List<Reason>,
        /** The user's own past ratings for this menu at this restaurant. */
        val rating: Tally,
        /**
         * Count-damped signed distance of the user's own rating from neutral:
         * `(평균 − 중간값) × n/(n+1)`, and zero with no ratings. Orders candidates
         * the scored sources leave tied; never outranks them.
         */
        val ratingAdjust: Double,
        val blockedBy: String?,
    ) {
        val offerable: Boolean get() = blockedBy == null
    }

    /**
     * Ranks this restaurant's menu for the current state.
     *
     * Items the current conditions rule out are returned too, marked with what
     * ruled them out, so the screen can say why a candidate is missing instead of
     * silently dropping it.
     */
    /**
     * The user's own past ratings, summarised for display.
     *
     * Derived from the reviews that still exist, so deleting a review removes its
     * weight from the average with no second copy to keep in sync.
     */
    data class Tally(
        val count: Int,
        val scoreSum: Int,
        val scaleMax: Int,
        val positive: Int,
        val neutral: Int,
        val negative: Int,
    ) {
        val average: Double? get() = if (count == 0) null else scoreSum.toDouble() / count

        /** For example `내 평점 4.0/5 (2건)`. */
        fun label(): String {
            val value = average ?: return "내 평점 없음"
            return "내 평점 %.1f/%d (%d건)".format(value, scaleMax, count)
        }

        fun breakdown(): String = listOfNotNull(
            "좋았어요 ${positive}".takeIf { positive > 0 },
            "괜찮았어요 ${neutral}".takeIf { neutral > 0 },
            "아쉬웠어요 ${negative}".takeIf { negative > 0 },
        ).joinToString(" · ")

        companion object {
            fun empty(scaleMax: Int) = Tally(0, 0, scaleMax, 0, 0, 0)
        }
    }

    /**
     * Ratings for this restaurant's lines, derived from the surviving reviews.
     *
     * Only reviews of this target count. A rating from another restaurant says
     * nothing about this menu, and treating it as if it did is the over-general
     * reuse this product exists to avoid.
     */
    fun tallies(state: ProductionState, restaurant: RestaurantDefinition): Map<String, Tally> =
        state.reviews
            .filter { it.entityId == restaurant.entityToken && it.lineValueToken != null }
            .groupBy { it.lineValueToken!! }
            .mapValues { (_, reviews) -> tallyOf(reviews) }
            .filterValues { it.count > 0 }

    /** The average across every rated order at this restaurant. */
    fun restaurantTally(state: ProductionState, restaurant: RestaurantDefinition): Tally =
        tallyOf(state.reviews.filter { it.entityId == restaurant.entityToken })

    private fun tallyOf(
        reviews: List<com.scpc.deliveryagent.core.ReviewRecord>,
    ): Tally {
        var count = 0
        var sum = 0
        var positive = 0
        var neutral = 0
        var negative = 0
        reviews.forEach { review ->
            val rating = catalog.rating(review.ratingToken) ?: return@forEach
            count += 1
            sum += rating.score
            when (rating.sentiment) {
                Sentiment.POSITIVE -> positive += 1
                Sentiment.NEUTRAL -> neutral += 1
                Sentiment.NEGATIVE -> negative += 1
            }
        }
        return Tally(count, sum, catalog.ratingScaleMax, positive, neutral, negative)
    }

    /**
     * What the user asked for, separated from where it was read.
     *
     * Ranking needs three things a person can state: the traits they named, a
     * budget, and a time limit. Until now those were always read from the draft's
     * own state, which only exists once a restaurant has been chosen. Carrying
     * them in a value object lets the same scorer run over a sentence typed before
     * any restaurant exists, so there is no second ranking rule to keep in sync
     * with this one.
     */
    data class Conditions(
        val traits: Set<String>,
        val budget: Int?,
        val etaLimitMinutes: Int?,
        /**
         * Dishes the user named by their own words.
         *
         * Naming one is not a trait to rank every dish by — it is the answer. It
         * only means anything before a restaurant is chosen, where it says which
         * restaurants to put in front of the user.
         */
        val namedMenus: Set<String> = emptySet(),
        /** Kinds of food the sentence named, when it named no dish outright. */
        val namedTypes: Set<String> = emptySet(),
    ) {
        /** Union of traits, and the tighter of each limit — as `DraftPricing` does. */
        operator fun plus(other: Conditions) = Conditions(
            traits = traits + other.traits,
            budget = listOfNotNull(budget, other.budget).minOrNull(),
            etaLimitMinutes = listOfNotNull(etaLimitMinutes, other.etaLimitMinutes).minOrNull(),
            namedMenus = namedMenus + other.namedMenus,
            namedTypes = namedTypes + other.namedTypes,
        )

        companion object {
            val NONE = Conditions(emptySet(), null, null)

            /** Exactly what `candidates` derived inline before this type existed. */
            fun ofState(catalog: SyntheticCatalog, state: ProductionState): Conditions {
                val pricing = DraftPricing(catalog)
                return Conditions(
                    traits = state.fields.values
                        .filter {
                            catalog.slotOfFieldSlotId(it.slotId)?.kind == SlotKind.USER_CONDITION
                        }
                        .mapNotNull { it.value }
                        .toSet(),
                    budget = pricing.budgetLimit(state),
                    etaLimitMinutes = pricing.etaLimitMinutes(state),
                )
            }

            /**
             * The same three things, read from one typed sentence.
             *
             * Wider than [ofState] on purpose. A draft has already sorted what the
             * user stated from what was stored earlier, so state only contributes
             * its condition slots; a sentence has not, and every value in it was
             * just said. The menu slot is the one exclusion — naming a dish is a
             * choice, not a trait to rank dishes by.
             */
            fun ofUtterance(catalog: SyntheticCatalog, utterance: Utterance): Conditions =
                Conditions(
                    traits = utterance.values
                        .filter { it.scopeToken != Slots.MAIN }
                        .map { it.valueToken }
                        .toSet(),
                    budget = utterance.values
                        .mapNotNull { catalog.value(it.valueToken)?.budgetLimit }
                        .minOrNull(),
                    etaLimitMinutes = utterance.values
                        .mapNotNull { catalog.value(it.valueToken)?.etaLimitMinutes }
                        .minOrNull(),
                    namedMenus = utterance.values
                        .filter { it.scopeToken == Slots.MAIN }
                        .map { it.valueToken }
                        .toSet(),
                    namedTypes = utterance.menuTypes.toSet(),
                )
        }
    }

    /** One ranked menu that names the restaurant it belongs to. */
    data class Discovery(
        val restaurant: RestaurantDefinition,
        val candidate: Candidate,
    )

    fun candidates(
        state: ProductionState,
        restaurant: RestaurantDefinition,
        limit: Int = 3,
        conditions: Conditions = Conditions.ofState(catalog, state),
    ): List<Candidate> {
        val pricing = DraftPricing(catalog)
        val ratings = tallies(state, restaurant)
        val budget = conditions.budget
        val etaLimit = conditions.etaLimitMinutes
        val active = activeTraitTokens(state)
        val outcomeTraits = outcomeTraitTokens(state)
        val conditionTraits = conditions.traits
        val unusable = catalog.unusableValueTokens()

        // A recommendation anchors the order on a main menu. Side menus are added
        // as accompanying lines from their own surface, not ranked against mains —
        // unless the user named one, in which case it is the thing being asked
        // for. "김밥" used to reach no candidate at all, and the offer fell back to
        // every restaurant's mains: an answer about 국밥 and 마라탕 to a question
        // about 김밥.
        return (
            catalog.mainMenus(restaurant) +
                catalog.sideMenus(restaurant).filter { namedRank(conditions, it.token) > 0 }
            )
            .map { item ->
                val reasons = mutableListOf<Reason>()
                var score = 0

                // One trait earns one reason at its strongest source. A value that
                // is both stated today and stored would otherwise be counted twice
                // and listed twice, which reads as padding and inflates the score.
                val traits = item.traits.toSet()
                val fromCondition = conditionTraits intersect traits
                val fromPreference = (active intersect traits) - fromCondition
                val fromOutcome = (outcomeTraits intersect traits) - fromCondition - fromPreference

                fromCondition.sorted().forEach { trait ->
                    score += CONDITION_WEIGHT
                    reasons += Reason("오늘 입력", catalog.valueLabel(trait))
                }
                fromPreference.sorted().forEach { trait ->
                    score += PREFERENCE_WEIGHT
                    reasons += Reason("직접 저장", catalog.valueLabel(trait))
                }
                fromOutcome.sorted().forEach { trait ->
                    score += OUTCOME_WEIGHT
                    reasons += Reason("지난 평가", catalog.valueLabel(trait))
                }
                if (item.etaMinutes != null) {
                    reasons += Reason("현재 메뉴정보", "${item.etaMinutes}분")
                }

                val rating = ratings[item.token] ?: Tally.empty(catalog.ratingScaleMax)
                val ratingAdjust = ratingAdjustOf(rating)
                if (rating.count > 0) {
                    // The user's own record of this exact pair is a visible reason,
                    // subordinate to everything above.
                    reasons += Reason(
                        "내 평점",
                        "%.1f/%d (%d건)".format(rating.average, rating.scaleMax, rating.count),
                    )
                }

                val blockedBy = when {
                    item.token in unusable || !item.inStock -> "품절"
                    budget != null && item.priceDelta > budget ->
                        "예산 ${pricing.formatAmount(budget)} 초과"
                    etaLimit != null && (item.etaMinutes ?: 0) > etaLimit ->
                        "희망 ${etaLimit}분 초과"
                    // A rating never blocks a choice; only hard facts do.
                    else -> null
                }

                Candidate(
                    valueToken = item.token,
                    label = item.label,
                    amount = item.priceDelta,
                    estimateMinutes = item.etaMinutes ?: 0,
                    score = score,
                    reasons = reasons,
                    rating = rating,
                    ratingAdjust = ratingAdjust,
                    blockedBy = blockedBy,
                )
            }
            // The rating orders only what the scored sources leave tied; remaining
            // ties break on the cheaper and then the faster item, so the order is
            // total and the same every run.
            .sortedWith(
                compareBy<Candidate> { it.blockedBy != null }
                    .thenByDescending { it.score }
                    .thenByDescending { it.ratingAdjust }
                    .thenBy { it.amount }
                    .thenBy { it.estimateMinutes }
                    .thenBy { it.valueToken },
            )
            .take(limit)
    }

    /**
     * The same ranking across every restaurant, one row per restaurant.
     *
     * The declared long-horizon goal is that saying what you want in conversation
     * produces 식당·메뉴 candidates. Before a restaurant is chosen there is no draft
     * to read conditions from, so they come from the sentence instead — the scorer
     * below is the one used after a restaurant exists, unchanged.
     *
     * One row per restaurant, because the point being demonstrated is breadth: the
     * user did not have to pick a restaurant before being understood. A second
     * menu from a restaurant already listed would spend a row without making that
     * point.
     */
    fun discoveries(
        state: ProductionState,
        conditions: Conditions,
        limit: Int = 3,
    ): List<Discovery> = catalog.restaurants
        .flatMap { restaurant ->
            candidates(state, restaurant, Int.MAX_VALUE, conditions)
                .map { candidate -> Discovery(restaurant, candidate).named(conditions) }
        }
        // Naming a dish or a kind of food is an answer, not a preference to rank
        // by, so nothing outside it belongs in the offer. Padding the list to its
        // usual length with 떡볶이 because only two restaurants serve 마라 answers a
        // question the user did not ask. Shown only if it leaves something.
        .let { all ->
            val asked = all.filter { namedRank(conditions, it.candidate.valueToken) > 0 }
            asked.ifEmpty { all }
        }
        // The per-restaurant chain, applied across restaurants. It ends on
        // valueToken, which SyntheticCatalog requires unique across every slot
        // value and every restaurant's menu, so the order is total — the same
        // every run and the same in both comparison arms.
        .sortedWith(
            compareBy<Discovery> { it.candidate.blockedBy != null }
                // A dish the user named by name comes before anything scoring
                // could put there, and its own kind comes next: someone who says
                // "마라탕" is asking who serves it, then what else is like it.
                .thenByDescending { namedRank(conditions, it.candidate.valueToken) }
                .thenByDescending { it.candidate.score }
                .thenByDescending { it.candidate.ratingAdjust }
                .thenBy { it.candidate.amount }
                .thenBy { it.candidate.estimateMinutes }
                .thenBy { it.candidate.valueToken },
        )
        .distinctBy { it.restaurant.entityToken }
        .take(limit)

    /**
     * 2 for a dish the sentence named, 1 for one of the same authored kind, 0
     * otherwise. Naming a dish is an answer, not a trait, so it is ranked here
     * rather than scored alongside conditions.
     */
    private fun namedRank(conditions: Conditions, menuToken: String): Int {
        if (conditions.namedMenus.isEmpty() && conditions.namedTypes.isEmpty()) return 0
        if (menuToken in conditions.namedMenus) return 2
        val type = catalog.menuTypeOf(menuToken)?.token ?: return 0
        // Naming a kind — "마라", "분식" — puts every dish of that kind ahead of the
        // rest without pretending the user picked one of them.
        if (type in conditions.namedTypes) return 1
        val sameKind = conditions.namedMenus.any { catalog.menuTypeOf(it)?.token == type }
        return if (sameKind) 1 else 0
    }

    /** Says on the row itself why a named dish, or its kind, is at the top. */
    private fun Discovery.named(conditions: Conditions): Discovery =
        when (namedRank(conditions, candidate.valueToken)) {
            2 -> copy(
                candidate = candidate.copy(
                    reasons = listOf(Reason("말한 메뉴", "말씀하신 메뉴입니다")) + candidate.reasons,
                ),
            )

            1 -> copy(
                candidate = candidate.copy(
                    reasons = listOf(
                        Reason(
                            "같은 종류",
                            catalog.menuTypeOf(candidate.valueToken)?.label.orEmpty(),
                        ),
                    ) + candidate.reasons,
                ),
            )

            else -> this
        }

    /** `(평균 − 중간값) × n/(n+1)`; zero with no ratings of this exact pair. */
    private fun ratingAdjustOf(rating: Tally): Double {
        val average = rating.average ?: return 0.0
        val neutral = (1 + rating.scaleMax) / 2.0
        return (average - neutral) * rating.count / (rating.count + 1.0)
    }

    /** Trait tokens from preferences that are valid and permitted right now. */
    private fun activeTraitTokens(state: ProductionState): Set<String> {
        val selection = AsprEngine.select(state)
        return selection.winners.values
            .filter { AsprEngine.relevanceOf(state, it) == Relevance.ACTIVE }
            .filter { it.kind == FactKind.STABLE || it.kind == FactKind.ONE_OFF }
            .filter { it.permission != Permission.RANK_ONLY }
            .filter { fact -> fact.scopeIds.none { it in state.revokedScopes } }
            .map { it.value }
            .toSet()
    }

    /** Trait tokens a delayed satisfaction result proposed. */
    private fun outcomeTraitTokens(state: ProductionState): Set<String> =
        AsprEngine.select(state).winners.values
            .filter { it.kind == FactKind.OUTCOME }
            .map { it.value }
            .toSet()

    private companion object {
        const val CONDITION_WEIGHT = 5
        const val PREFERENCE_WEIGHT = 3
        const val OUTCOME_WEIGHT = 2
    }
}
