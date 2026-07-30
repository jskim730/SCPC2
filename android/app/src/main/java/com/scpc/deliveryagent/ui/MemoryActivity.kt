package com.scpc.deliveryagent.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.LinearLayout
import com.scpc.deliveryagent.core.AsprEngine
import com.scpc.deliveryagent.core.FactKind
import com.scpc.deliveryagent.core.Relevance
import com.scpc.deliveryagent.delivery.ProductSurface
import com.scpc.deliveryagent.delivery.Slots
import com.scpc.deliveryagent.delivery.SyntheticCatalog
import com.scpc.deliveryagent.platform.Production

/**
 * My preferences and memory.
 *
 * Everything the app remembers is listed with its source, scope and whether it
 * may be auto-applied, and every entry can be corrected, revoked or deleted here
 * rather than in a hidden setting. Deleted originals leave only a marker.
 */
class MemoryActivity : Activity() {

    private lateinit var content: LinearLayout

    private val catalog: SyntheticCatalog get() = Production.catalog(this)

    private val surface: ProductSurface get() = Production.surface(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = Ui.column(this)
        setContentView(Ui.scroller(this, content))
        render()
    }

    private fun render() {
        val state = surface.state()
        val selection = AsprEngine.select(state)
        content.removeAllViews()

        content.addView(Ui.title(this, "내 취향과 기억"))
        content.addView(Ui.body(this, "현재 지시·정정·철회·삭제가 저장된 과거 기록보다 우선합니다."))

        content.addView(Ui.section(this, "저장된 항목"))
        if (state.facts.isEmpty()) {
            content.addView(Ui.body(this, "저장된 기억이 없습니다."))
        } else {
            state.facts.values.sortedBy { it.factId }.forEach { fact ->
                val relevance = AsprEngine.relevanceOf(state, fact)
                val revoked = fact.scopeIds.any { it in state.revokedScopes }
                content.addView(
                    Ui.row(
                        this,
                        catalog.slotLabel(fact.slotId),
                        catalog.valueLabel(fact.value),
                        buildString {
                            append(kindLabel(fact.kind))
                            append(" · authority v${fact.authorityVersion}")
                            append(" · ")
                            append(if (revoked) "자동 적용 철회" else permissionLabel(fact.permission.name))
                            if (relevance != Relevance.ACTIVE) {
                                append(" · ${relevanceLabel(relevance)}")
                            }
                        },
                    ),
                )
            }
        }

        content.addView(Ui.section(this, "자동 적용 허용범위"))
        val active = state.activeScopeIds()
        if (active.isEmpty()) {
            content.addView(Ui.body(this, "허용된 범위가 없습니다."))
        } else {
            active.forEach { scopeId ->
                val revoked = scopeId in state.revokedScopes
                content.addView(
                    Ui.row(
                        this,
                        scopeId.removePrefix("scope."),
                        if (revoked) "철회" else "허용",
                        if (revoked) "다시 확인함" else "자동 적용 가능",
                    ),
                )
            }
        }

        content.addView(Ui.section(this, "도착한 평가·결과"))
        if (state.appliedOutcomes.isEmpty()) {
            content.addView(Ui.body(this, "아직 없습니다."))
        } else {
            state.appliedOutcomes.values.sortedBy { it.outcomeId }.forEach { outcome ->
                content.addView(
                    Ui.row(
                        this,
                        catalog.slotLabel(outcome.slotId),
                        outcome.outcomeId.removePrefix("outcome."),
                        "${outcome.appliedAtVirtual} · 한 번만 반영",
                    ),
                )
            }
        }

        content.addView(Ui.section(this, "삭제 표식"))
        if (state.tombstones.isEmpty()) {
            content.addView(Ui.body(this, "없습니다."))
        } else {
            state.tombstones.values.sortedBy { it.tombstoneId }.forEach { tombstone ->
                content.addView(
                    Ui.row(
                        this,
                        catalog.slotLabel(tombstone.slotId),
                        "원문 삭제됨",
                        "${tombstone.deletedAtVirtual} · 복원 불가",
                    ),
                )
            }
        }

        content.addView(Ui.section(this, "제외된 기억과 이유"))
        if (selection.excluded.isEmpty()) {
            content.addView(Ui.body(this, "없습니다."))
        } else {
            selection.excluded.toSortedMap().forEach { (factId, why) ->
                val fact = state.facts[factId]
                content.addView(
                    Ui.row(
                        this,
                        catalog.slotLabel(fact?.slotId ?: factId),
                        catalog.valueLabel(fact?.value),
                        relevanceLabel(why),
                    ),
                )
            }
        }

        content.addView(Ui.divider(this))
        content.addView(Ui.section(this, "직접 관리"))
        listOf(Slots.SPICINESS, Slots.UTENSIL, Slots.RICE, Slots.SALTINESS, Slots.SIDE)
            .map(catalog::slot)
            .forEach { slot ->
            content.addView(
                Ui.button(this, "${slot.label} 자동 적용 권한 철회") {
                    surface.revokeAutoApply(slot)
                    render()
                },
            )
        }
        content.addView(
            Ui.button(this, "요청 메모 삭제") {
                surface.deleteRequestNote()
                render()
            },
        )
        content.addView(
            Ui.button(this, "전체 Reset", primary = true) { confirmReset() },
        )
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("전체 Reset")
            .setMessage("저장된 취향, 허용범위, 초안, 기록을 모두 지웁니다. 되돌릴 수 없습니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("모두 지우기") { _, _ ->
                Production.resetAll(this)
                render()
            }
            .show()
    }

    private fun kindLabel(kind: FactKind): String = when (kind) {
        FactKind.STABLE -> "직접 저장"
        FactKind.ONE_OFF -> "이번 주문만"
        FactKind.EPHEMERAL -> "일회성 요청"
        FactKind.OUTCOME -> "과거 평가"
        FactKind.INFERRED -> "추론"
    }

    private fun permissionLabel(permission: String): String = when (permission) {
        "AUTO_APPLY" -> "자동 적용 허용"
        "ASK_BEFORE_APPLY" -> "적용 전 확인"
        else -> "추천 순위에만 사용"
    }

    private fun relevanceLabel(relevance: Relevance): String = when (relevance) {
        Relevance.ACTIVE -> "현재 사용 중"
        Relevance.EXPIRED_SESSION -> "이전 주문 session 조건 — 만료"
        Relevance.EXPIRED_TIME -> "기간 만료"
        Relevance.OTHER_ENTITY -> "다른 식당 전용 — 제외"
        Relevance.OTHER_GOAL -> "다른 목표 전용 — 제외"
        Relevance.DISTRACTOR -> "현재 주문과 무관 — 제외"
        Relevance.REVOKED_UNCERTAIN -> "권한 철회 — 확인 필요"
    }
}
