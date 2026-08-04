package com.scpc.deliveryagent.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.scpc.deliveryagent.core.Digest
import org.json.JSONObject
import org.scpc.r2.probe.ProbeAdapter
import org.scpc.r2.probe.ProbeContract

/**
 * Technical identity of the installed release, read from the artifacts.
 *
 * Nothing here is typed in by hand. The package name, version and signing
 * certificate come from Android's own [PackageManager], and the mission adapter
 * digest is the SHA-256 of the `assets/MISSION_ADAPTER.json` bytes actually
 * shipped in this APK. The release attestation is not invented either: it is
 * taken from the run input, where the official tooling bound it to the real APK
 * bytes.
 *
 * On the protected path the starter AAR produces these values itself. This class
 * exists so the public rehearsal screen can show and export the same facts
 * without reimplementing any part of the official protocol.
 */
object ReleaseIdentity {

    private const val MISSION_ADAPTER_ASSET = "MISSION_ADAPTER.json"
    private const val NOT_USED = "NOT_USED"

    fun missionAdapterSha256(context: Context): String {
        val bytes = context.applicationContext.assets.open(MISSION_ADAPTER_ASSET)
            .use { it.readBytes() }
        require(bytes.isNotEmpty()) { "$MISSION_ADAPTER_ASSET is empty" }
        require(bytes.size <= 1024 * 1024) { "$MISSION_ADAPTER_ASSET larger than 1 MiB" }
        return Digest.bytes(bytes)
    }

    fun releaseBinding(context: Context, releaseAttestationId: String): JSONObject {
        val application = context.applicationContext
        val manager = application.packageManager
        val packageName = application.packageName

        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }

        @Suppress("DEPRECATION")
        val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo
            val signatures = when {
                signing == null -> emptyArray()
                signing.hasMultipleSigners() -> signing.apkContentsSigners
                else -> signing.signingCertificateHistory
            }
            signatures.firstOrNull()?.toByteArray()
        } else {
            info.signatures?.firstOrNull()?.toByteArray()
        }
        requireNotNull(certificate) { "installed package has no signing certificate" }

        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }

        return JSONObject()
            .put("package_name", packageName)
            .put("version_name", info.versionName ?: "0")
            .put("version_code", versionCode.toString())
            .put("signing_certificate_sha256", Digest.bytes(certificate))
            .put("release_attestation_id", releaseAttestationId)
    }

    fun runtimeIdentity(
        context: Context,
        adapter: ProbeAdapter,
        releaseAttestationId: String,
    ): JSONObject {
        val runtime = adapter.runtimeState(context)
        return JSONObject()
            // The submitted release configures no model and no inference backend,
            // so the frozen id is reported as not used rather than as a model.
            .put("model_backend_frozen_id", if (runtime.modelConfigured) modelId(releaseAttestationId) else NOT_USED)
            .put("release_attestation_id", releaseAttestationId)
            .put("cumulative_invocations", runtime.cumulativeInvocations.coerceIn(0, 60))
    }

    /**
     * Resolves the adapter class registered in this app's manifest, the same
     * metadata key the starter AAR reads, so the public screen cannot accidentally
     * drive a different adapter than the protected component.
     */
    fun registeredAdapter(context: Context): ProbeAdapter {
        val application = context.applicationContext
        val metadata = application.packageManager
            .getApplicationInfo(application.packageName, PackageManager.GET_META_DATA)
            .metaData
        val className = metadata?.getString(ProbeContract.META_ADAPTER_CLASS)
        requireNotNull(className) { "manifest is missing ${ProbeContract.META_ADAPTER_CLASS}" }
        val loaded = Class.forName(className, true, application.classLoader)
            .getDeclaredConstructor()
            .newInstance()
        return loaded as ProbeAdapter
    }

    private fun modelId(releaseAttestationId: String): String =
        "mbf-v1:" + Digest.utf8(releaseAttestationId)
}
