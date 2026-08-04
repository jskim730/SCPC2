package com.scpc.deliveryagent.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import com.scpc.deliveryagent.platform.Production
import com.scpc.deliveryagent.probe.ProbeRunLog
import com.scpc.deliveryagent.probe.PublicRunExporter
import com.scpc.deliveryagent.probe.PublicProbeRunner
import org.json.JSONObject

/**
 * Public rehearsal screen.
 *
 * Exposes the three controls the official contract requires, with those exact
 * accessibility content descriptions, reachable by touch, keyboard and
 * UIAutomator. Import errors, run progress, success or failure and the export
 * location are all reported as they actually happened. This screen runs the same
 * adapter and production core the protected component runs, and computes no
 * score, expected relation or anchor.
 */
class ProbeConsoleActivity : Activity() {

    private lateinit var content: LinearLayout

    private var imported: PublicProbeRunner.ImportedInput? = null
    private var importedName: String = "-"
    private var result: JSONObject? = null
    private var status: String = "대기 중"
    private var exportPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = Ui.column(this)
        setContentView(Ui.scroller(this, content))
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(Ui.title(this, "평가·내보내기"))
        content.addView(
            Ui.body(
                this,
                "공개 연습 입력을 불러와 제품과 같은 production core로 실행하고, 점수가 없는 결과를 저장합니다.",
            ),
        )

        content.addView(Ui.section(this, "상태"))
        content.addView(
            Ui.body(
                this,
                buildString {
                    append("불러온 입력: $importedName\n")
                    append("step 수: ${imported?.stepCount ?: 0}\n")
                    append("비교 arm: ${Production.selectedArm(this@ProbeConsoleActivity).namespace}\n")
                    append("진행: $status\n")
                    append("결과 step 수: ${result?.optJSONArray("step_results")?.length() ?: 0}\n")
                    append("저장 위치: ${exportPath ?: "아직 없음"}\n")
                    append("최근 중단: ${ProbeRunLog.lastAbortReason(this@ProbeConsoleActivity) ?: "없음"}")
                },
            ),
        )

        content.addView(Ui.divider(this))
        content.addView(
            Ui.button(
                this,
                text = "공개 입력 불러오기",
                description = getString(com.scpc.deliveryagent.R.string.probe_import_description),
                primary = true,
            ) { pickInput() },
        )
        content.addView(
            Ui.button(
                this,
                text = "불러온 step 실행",
                description = getString(com.scpc.deliveryagent.R.string.probe_run_description),
            ) { runImported() },
        )
        content.addView(
            Ui.button(
                this,
                text = "결과·증거 내보내기",
                description = getString(com.scpc.deliveryagent.R.string.probe_export_description),
            ) { exportResult() },
        )

        content.addView(Ui.divider(this))
        content.addView(Ui.section(this, "증거 파일"))
        val runId = result?.optString("run_id")?.takeIf { it.isNotEmpty() }
        val evidenceRoot = if (runId == null) {
            Production.evidenceRoot(this, Production.selectedArm(this))
        } else {
            Production.runEvidenceRoot(this, Production.selectedArm(this), runId)
        }
        content.addView(
            Ui.mono(
                this,
                if (evidenceRoot.exists()) {
                    evidenceRoot.walkTopDown().filter { it.isFile }
                        .map { it.relativeTo(evidenceRoot).path.replace('\\', '/') }
                        .sorted()
                        .joinToString("\n")
                        .ifEmpty { "없음" }
                } else {
                    "없음"
                },
            ),
        )
    }

    private fun pickInput() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
        }
        try {
            startActivityForResult(intent, REQUEST_IMPORT)
        } catch (error: Exception) {
            status = "파일 선택기를 열 수 없습니다: ${error.message}"
            render()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            REQUEST_IMPORT -> importInput(uri)
            REQUEST_EXPORT -> writeExportBundle(uri, data?.flags ?: 0)
        }
    }

    private fun importInput(uri: Uri) {
        status = "불러오는 중"
        render()
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("입력을 읽을 수 없습니다")
            imported = PublicProbeRunner.import(bytes)
            importedName = uri.lastPathSegment ?: uri.toString()
            result = null
            exportPath = null
            status = "불러오기 성공 — 실행할 수 있습니다"
        } catch (error: Exception) {
            imported = null
            importedName = "-"
            // Import failure is shown as failure, never as a run that succeeded.
            status = "불러오기 실패: ${error.message}"
        }
        render()
    }

    private fun runImported() {
        val input = imported
        if (input == null) {
            status = "먼저 공개 입력을 불러오세요"
            render()
            return
        }
        status = "실행 중 (${input.stepCount} step)"
        render()
        try {
            result = PublicProbeRunner.run(this, input)
            status = "실행 완료 — 내보낼 수 있습니다"
        } catch (error: Exception) {
            result = null
            status = "실행 실패: ${error.message}"
        }
        render()
    }

    private fun exportResult() {
        if (result == null) {
            status = "먼저 실행하세요"
            render()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        try {
            status = "저장 위치 선택 중"
            render()
            startActivityForResult(intent, REQUEST_EXPORT)
        } catch (error: Exception) {
            exportPath = null
            status = "저장 위치를 선택할 수 없습니다: ${error.message}"
            render()
        }
    }

    private fun writeExportBundle(uri: Uri, flags: Int) {
        val document = result
        if (document == null) {
            exportPath = null
            status = "내보낼 결과가 없어졌습니다. 다시 실행하세요"
            render()
            return
        }
        try {
            val takeFlags = flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (takeFlags != 0) {
                try {
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (_: SecurityException) {
                    // Some document providers grant access for this operation only.
                }
            }
            val summary = PublicRunExporter.export(
                this,
                uri,
                Production.selectedArm(this),
                document,
            )
            exportPath = "${summary.folderName} (${summary.folderUri})"
            status = "내보내기 완료 — result 1개 · evidence ${summary.evidenceCount}/${summary.evidenceCount}개"
        } catch (error: Exception) {
            exportPath = null
            status = "내보내기 실패: ${error.message}"
        }
        render()
    }

    private companion object {
        const val REQUEST_IMPORT = 4101
        const val REQUEST_EXPORT = 4102
    }
}
