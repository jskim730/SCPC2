package com.scpc.deliveryagent.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import com.scpc.deliveryagent.core.FieldStatus
import com.scpc.deliveryagent.platform.Arm
import com.scpc.deliveryagent.platform.Production

/**
 * Paired comparison screen.
 *
 * The arm is chosen here, explicitly, by a person. It is never inferred from a
 * probe pack id, a surface nonce or any caller string. The two arms use separate
 * stores and separate evidence directories, so a save, delete or reset in one is
 * invisible to the other.
 */
class ComparisonActivity : Activity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = Ui.column(this)
        setContentView(Ui.scroller(this, content))
        render()
    }

    private fun render() {
        content.removeAllViews()
        val selected = Production.selectedArm(this)

        content.addView(Ui.title(this, "비교 실행"))
        content.addView(
            Ui.body(
                this,
                "같은 APK·화면·입력·판단 엔진에서 Signature mechanism(ASPR)만 끈 상태를 비교합니다. " +
                    "준비 버튼이 시작 snapshot을 맞춘 뒤 두 arm의 저장공간과 증거는 다시 분리됩니다.",
            ),
        )

        content.addView(Ui.section(this, "같은 시작점 준비"))
        val pair = Production.comparisonPair(this)
        content.addView(
            Ui.body(
                this,
                if (pair == null) {
                    "아직 paired 시작점을 만들지 않았습니다. full에서 비교 직전 상태를 만든 뒤 아래 버튼을 누르세요."
                } else {
                    "pair ${pair.pairId}\n" +
                        "state ${pair.sourceStateSha256.take(16)}… · " +
                        "catalog ${pair.catalogSha256.take(16)}… · " +
                        "conversation ${pair.conversationSha256?.take(16)?.plus("…") ?: "없음"}"
                },
            ),
        )
        content.addView(
            Ui.button(this, "full 현재 상태로 paired 시작점 만들기", primary = pair == null) {
                Production.prepareComparisonPair(this)
                render()
            },
        )
        content.addView(
            Ui.body(
                this,
                "이 작업은 claim-off의 기존 진행상태와 양 arm의 이전 evidence를 새 비교 시작점으로 교체합니다.",
            ),
        )

        content.addView(Ui.section(this, "현재 선택"))
        content.addView(
            Ui.body(
                this,
                when (selected) {
                    Arm.FULL -> "full — ASPR 켜짐"
                    Arm.CLAIM_OFF -> "claim-off — 최신 값만 참고, 새 session에서 재확인"
                },
            ),
        )

        content.addView(Ui.section(this, "arm별 현재 상태"))
        Arm.entries.forEach { arm ->
            val state = Production.core(this, arm).state()
            val confirmed = state.fields.values.count { it.status == FieldStatus.CONFIRMED }
            val open = state.fields.values.count { it.status != FieldStatus.CONFIRMED }
            content.addView(
                Ui.row(
                    this,
                    arm.namespace,
                    "session ${state.sessionLabel.ifEmpty { "-" }}",
                    "확인 완료 $confirmed · 확인 필요 $open · VIL ${state.resolutions.size}",
                ),
            )
            content.addView(
                Ui.mono(
                    this,
                    "  namespace=${state.namespace} runId=${state.runId.ifEmpty { "-" }} " +
                        "epoch=${state.processEpoch} 중복commit차단=${state.duplicateActionAttempts}",
                ),
            )
        }

        content.addView(Ui.divider(this))
        content.addView(Ui.section(this, "arm 선택"))
        content.addView(
            Ui.button(this, "full 로 실행 (ASPR 켜짐)", primary = selected == Arm.FULL) {
                Production.selectArm(this, Arm.FULL)
                render()
            },
        )
        content.addView(
            Ui.button(this, "claim-off 로 실행 (mechanism만 끔)", primary = selected == Arm.CLAIM_OFF) {
                Production.selectArm(this, Arm.CLAIM_OFF)
                render()
            },
        )

        content.addView(Ui.divider(this))
        content.addView(
            Ui.body(
                this,
                "비교지표 VIL은 유효한 초안까지 사용자가 추가로 확인·입력해야 한 field-resolution event 수이며 " +
                    "ledger에서 계산합니다. 점수·anchor·Q는 앱이 계산하지 않습니다.",
            ),
        )
    }
}
