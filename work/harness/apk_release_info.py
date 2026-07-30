"""Independent APK release identity inspection used by local assignment tools."""

from __future__ import annotations

import hashlib
import json
import os
import pathlib
import re
import subprocess
from typing import Mapping, TypedDict


class ApkReleaseInfo(TypedDict):
    package_name: str
    version_name: str
    version_code: str
    apk_sha256: str
    app_release_sha256: str
    signing_certificate_sha256: str


SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


def file_sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def calculate_release_attestation_id(release: Mapping[str, str]) -> str:
    """Return the official release attestation for one exact candidate APK.

    The payload is compact JSON with lexicographically sorted keys. Only the
    five contract fields below participate in the digest.
    """

    payload = {
        "app_release_sha256": release["app_release_sha256"],
        "package_name": release["package_name"],
        "signing_certificate_sha256": release["signing_certificate_sha256"],
        "version_code": release["version_code"],
        "version_name": release["version_name"],
    }
    for key in ("app_release_sha256", "signing_certificate_sha256"):
        if not SHA256_PATTERN.fullmatch(payload[key]):
            raise ValueError(f"{key} must be lowercase SHA-256")
    for key in ("package_name", "version_code", "version_name"):
        value = payload[key]
        if not value or value != value.strip():
            raise ValueError(f"{key} must be non-empty text without outer whitespace")
    canonical = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return f"ra-v1:{hashlib.sha256(canonical).hexdigest()}"


def inspect_apk(apk: pathlib.Path, sdk_root: pathlib.Path) -> ApkReleaseInfo:
    apk = apk.resolve()
    sdk_root = sdk_root.resolve()
    _sfx = ".bat" if os.name == "nt" else ""
    apkanalyzer = sdk_root / "cmdline-tools" / "latest" / "bin" / ("apkanalyzer" + _sfx)
    apksigner = sdk_root / "build-tools" / "35.0.0" / ("apksigner" + _sfx)
    if not apk.is_file():
        raise ValueError(f"APP.apk does not exist or is not a file: {apk}")
    if not apkanalyzer.is_file() or not apksigner.is_file():
        raise ValueError(
            "Android SDK tools are missing: install cmdline-tools/latest "
            "(apkanalyzer) and build-tools/35.0.0 (apksigner), then set "
            "ANDROID_SDK_ROOT or pass --sdk-root"
        )
    values: dict[str, str] = {}
    try:
        for key, command in (
            ("package_name", "application-id"),
            ("version_name", "version-name"),
            ("version_code", "version-code"),
        ):
            values[key] = subprocess.run(
                [str(apkanalyzer), "manifest", command, str(apk)],
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            ).stdout.strip()
        signer_output = subprocess.run(
            [str(apksigner), "verify", "--print-certs", str(apk)],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        ).stdout
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or error.stdout or "").strip()
        suffix = f" ({detail.splitlines()[-1]})" if detail else ""
        raise ValueError(
            "APP.apk could not be read or its signature could not be verified; "
            f"confirm that it is the final signed APK{suffix}"
        ) from error
    match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", signer_output)
    if not match:
        raise RuntimeError("cannot read candidate signing certificate digest")
    app_release_sha256 = file_sha256(apk)
    return ApkReleaseInfo(
        package_name=values["package_name"],
        version_name=values["version_name"],
        version_code=values["version_code"],
        apk_sha256=app_release_sha256,
        app_release_sha256=app_release_sha256,
        signing_certificate_sha256=match.group(1),
    )
