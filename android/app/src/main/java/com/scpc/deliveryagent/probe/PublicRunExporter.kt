package com.scpc.deliveryagent.probe

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.scpc.deliveryagent.core.Ids
import com.scpc.deliveryagent.platform.Arm
import com.scpc.deliveryagent.platform.Production
import java.io.File
import org.json.JSONObject

/** Writes one self-contained public run into a user-selected document tree. */
object PublicRunExporter {

    data class Summary(
        val folderName: String,
        val folderUri: Uri,
        val evidenceCount: Int,
    )

    fun export(context: Context, treeUri: Uri, arm: Arm, result: JSONObject): Summary {
        val runId = result.getString("run_id")
        val declared = result.getJSONArray("evidence_ids").let { array ->
            (0 until array.length()).map(array::getString)
        }
        require(declared.size == declared.toSet().size) { "evidence_ids에 중복이 있습니다" }

        val sourceRoot = Production.runEvidenceRoot(context, arm, runId)
        require(sourceRoot.isDirectory) { "실행 $runId 의 evidence 폴더가 없습니다" }
        val sources = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("json", "txt", "png", "mp4") }
            .toList()
        val byId = sources.groupBy(File::nameWithoutExtension)
        require(byId.values.all { it.size == 1 }) { "같은 evidence ID의 파일이 둘 이상입니다" }
        require(byId.keys == declared.toSet()) {
            "result와 evidence 파일이 다릅니다: " +
                "missing=${declared.toSet() - byId.keys} extra=${byId.keys - declared.toSet()}"
        }
        sources.filter { it.extension.equals("json", ignoreCase = true) }.forEach { file ->
            val document = JSONObject(file.readText(Charsets.UTF_8))
            require(document.optString("evidence_id") == file.nameWithoutExtension ||
                document.optString("receipt_id") == file.nameWithoutExtension
            ) { "${file.name} 내부 ID가 파일명과 다릅니다" }
        }

        val resolver = context.contentResolver
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val folderName = "SCPC2_PUBLIC_RUN_${Ids.segment(runId).take(32)}_${arm.namespace}"
        val outputRoot = DocumentsContract.createDocument(
            resolver,
            rootDocument,
            DocumentsContract.Document.MIME_TYPE_DIR,
            folderName,
        ) ?: error("내보내기 폴더를 만들 수 없습니다")

        fun createDirectory(parent: Uri, name: String): Uri =
            DocumentsContract.createDocument(
                resolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            ) ?: error("$name 폴더를 만들 수 없습니다")

        fun createFile(parent: Uri, name: String, mime: String, bytes: ByteArray) {
            val uri = DocumentsContract.createDocument(resolver, parent, mime, name)
                ?: error("$name 파일을 만들 수 없습니다")
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: error("$name 파일을 쓸 수 없습니다")
        }

        createFile(
            outputRoot,
            "PROBE_RESULT.json",
            "application/json",
            result.toString(2).toByteArray(Charsets.UTF_8),
        )
        val directories = mutableMapOf("" to outputRoot)
        sources.sortedBy { it.relativeTo(sourceRoot).invariantSeparatorsPath }.forEach { source ->
            val relative = source.relativeTo(sourceRoot).invariantSeparatorsPath
            val parts = relative.split('/')
            var parentPath = ""
            var parentUri = outputRoot
            parts.dropLast(1).forEach { part ->
                val path = if (parentPath.isEmpty()) part else "$parentPath/$part"
                parentUri = directories.getOrPut(path) { createDirectory(parentUri, part) }
                parentPath = path
            }
            val mime = when (source.extension.lowercase()) {
                "json" -> "application/json"
                "txt" -> "text/plain"
                "png" -> "image/png"
                "mp4" -> "video/mp4"
                else -> "application/octet-stream"
            }
            createFile(parentUri, source.name, mime, source.readBytes())
        }
        return Summary(folderName, outputRoot, sources.size)
    }
}
