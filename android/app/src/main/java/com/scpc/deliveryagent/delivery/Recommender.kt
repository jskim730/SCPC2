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
 * an earlier order, and the ratings the user left on this restaurant's own lines.
 * Nothing is inferred from a value the catalog does not carry, and a candidate is
 * never auto-confirmed: the user picks.
 *
 * Ranking is a pure function of state and catalog, and it is available to both
 * comparison arms, so the difference between them stays attributable to ASPR
 * alone rather than to ordinary order history.
 *
 * A star rating is different from a review remark and is treated differently. A
 * remark names an option and becomes a scoped result the user agreed to, which is
 * why it ranks. A rating is raw history about one whole order, so it is **shown,
 * not scored**: the average appears next to the price and the estimate so the user
 * can compare, and the ranking does not move because of it. That keeps the ranking
 * explainable, keeps a popular-but-wrong dish from climbing over what the user
 * actually asked for, and keeps the measured comparison gain attributable to the
 * mechanism rather than to ordinary order history.
 *
 * Ratings shown are the user's own past ratings on this device. No other person's
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
        /** The user's own past ratings for this line. Display only. */
        val rating: Tally,
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

    fun candidates(
        state: ProductionState,
        restaurant: RestaurantDefinition,
        limit: Int = 3,
    ): List<Candidate> {
        val pricing = DraftPricing(catalog)
        val ratings = tallies(state, restaurant)
        val budget = pricing.budgetLimit(state)
        val etaLimit = pricing.etaLimitMinutes(state)
        val active = activeTraitTokens(state)
        val outcomeTraits = outcomeTraitTokens(state)
        val conditionTraits = conditionTraitTokens(state)
        val unusable = catalog.unusableValueTokens()

        return restaurant.menu
            .map { item ->
                val reasons = mutableListOf<Reason>()
                var score = 0

                conditionTraits.intersect(item.traits.toSet()).sorted().forEach { trait ->
                    score += CONDITION_WEIGHT
                    reasons += Reason("오늘 입력", catalog.valueLabel(trait))
                }
                active.intersect(item.traits.toSet()).sorted().forEach { trait ->
                    score += PREFERENCE_WEIGHT
                    reasons += Reason("직접 저장", catalog.valueLabel(trait))
                }
                outcomeTraits.intersect(item.traits.toSet()).sorted().forEach { trait ->
                    score += OUTCOME_WEIGHT
                    reasons += Reason("지난 평가", catalog.valueLabel(trait))
                }
                if (item.etaMinutes != null) {
                    reasons += Reason("현재 메뉴정보", "${item.etaMinutes}분")
                }

                val blockedBy = when {
                    item.token in unusable || !item.inStock -> "품절"
                    budget != null && item.priceDelta > budget ->
                        "예산 ${pricing.formatAmount(budget)} 초과"
                    etaLimit != null && (item.etaMinutes ?: 0) > etaLimit ->
                        "희망 ${etaLimit}분 초과"
                    else -> null
                }

                Candidate(
                    valueToken = item.token,
                    label = item.label,
                    amount = item.priceDelta,
                    estimateMinutes = item.etaMinutes ?: 0,
                    score = score,
                    reasons = reasons,
                    // Shown so the user can compare, and deliberately not part of
                    // the score above.
                    rating = ratings[item.token] ?: Tally.empty(catalog.ratingScaleMax),
                    blockedBy = blockedBy,
                )
            }
            // Ties break on the cheaper and then the faster item, so the order is
            // total and the same every run.
            .sortedWith(
                compareBy<Candidate> { it.blockedBy != null }
                    .thenByDescending { it.score }
                    .thenBy { it.amount }
                    .thenBy { it.estimateMinutes }
                    .thenBy { it.valueToken },
            )
            .take(limit)
    }

    /** Trait tokens the user stated for this order. */
    private fun conditionTraitTokens(state: ProductionState): Set<String> =
        state.fields.values
            .filter { catalog.slotOfFieldSlotId(it.slotId)?.kind == SlotKind.USER_CONDITION }
            .mapNotNull { it.value }
            .toSet()

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
