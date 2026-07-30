#!/usr/bin/env python3
"""Validate one finalized v3 SAMPLE_EXPORT.

The validator checks the public schemas, APK/source identity, the immutable
Runner result, the exact 13-operation public run, evidence-to-step mapping and
the export file manifest.  It contains neither a hidden oracle nor scoring
logic.

Exit codes:
  0 PASS
  2 CONTRACT_ERROR
  4 TOOL_ERROR
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import unicodedata
import zipfile
from typing import Any

try:
    from jsonschema import Draft202012Validator, FormatChecker
except ModuleNotFoundError:
    print(
        "TOOL_ERROR: create a virtual environment and install the candidate "
        "tool dependency with `python3 -m venv .venv && "
        ".venv/bin/python -m pip install -r candidate_kit/requirements.txt`",
        file=sys.stderr,
    )
    raise SystemExit(4)


EXIT_PASS = 0
EXIT_CONTRACT_ERROR = 2
EXIT_TOOL_ERROR = 4
ZERO_SHA256 = "0" * 64
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
EVIDENCE_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
REQUIRED_EXPORT_FILES = {
    "MISSION_LOCK.json",
    "RUNTIME_IDENTITY.json",
    "EVIDENCE_INDEX.json",
    "EXPORT_INDEX.json",
    "PUBLIC_PROBE_RESULT.json",
}
MANIFEST_TOP_FILES = {
    "MISSION_LOCK.json",
    "RUNTIME_IDENTITY.json",
    "EVIDENCE_INDEX.json",
    "PUBLIC_PROBE_RESULT.json",
}
REQUIRED_OPERATIONS = {
    "RESET_AND_START",
    "UPSERT_FACT",
    "ADVANCE_SESSION",
    "REQUEST_DECISION",
    "CORRECT_FACT",
    "REVOKE_SCOPE",
    "DELETE_FACT",
    "SET_NETWORK",
    "PROCESS_KILL_RELAUNCH",
    "REPLAY_EVENT",
    "DELIVER_OUT_OF_ORDER",
    "ADVANCE_TIME",
    "EXPORT_AND_END",
}
MEDIA_TYPES = {
    ".json": "application/json",
    ".txt": "text/plain",
    ".png": "image/png",
    ".mp4": "video/mp4",
}
MISSION_ADAPTER_APK_PATH = "assets/MISSION_ADAPTER.json"
REMOVED_FIELDS = {
    "mission_lock": {"mission_lock_digest"},
    "runtime_identity": {"runtime_identity_digest"},
    "evidence_index": {"evidence_index_digest"},
    "export_index": {
        "artifact_bindings",
        "model_invoked",
        "model_receipt_evidence_ids",
        "export_index_digest",
    },
    "mission_adapter": {
        "candidate_id",
        "mission_id",
        "release_attestation_id",
        "production_parity",
    },
}


class ContractError(RuntimeError):
    """A participant-visible contract violation."""


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    normalized: set[str] = set()
    for key, value in pairs:
        normalized_key = unicodedata.normalize("NFC", key)
        if normalized_key in normalized:
            raise ContractError(f"duplicate JSON key after NFC normalization: {key!r}")
        normalized.add(normalized_key)
        result[key] = value
    return result


def parse_json_bytes(value: bytes, label: str) -> dict[str, Any]:
    parsed = json.loads(
        value.decode("utf-8"),
        object_pairs_hook=unique_object,
        parse_constant=lambda item: (_ for _ in ()).throw(
            ContractError(f"{label}: non-finite JSON number: {item}")
        ),
    )
    if not isinstance(parsed, dict):
        raise ContractError(f"{label} must contain a JSON object")
    return parsed


def load_json(path: Path) -> dict[str, Any]:
    return parse_json_bytes(path.read_bytes(), str(path))


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def bytes_sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical_json_bytes(value: Any) -> bytes:
    """Serialize the integer-only public contract deterministically."""

    def check(item: Any, location: str) -> None:
        if isinstance(item, dict):
            for key, nested in item.items():
                if not isinstance(key, str) or not key.isascii():
                    raise ContractError(
                        f"{location}: non-ASCII object keys are not supported"
                    )
                check(nested, f"{location}.{key}")
        elif isinstance(item, list):
            for index, nested in enumerate(item):
                check(nested, f"{location}[{index}]")
        elif isinstance(item, float):
            raise ContractError(
                f"{location}: floating JSON numbers are outside this contract"
            )
        elif isinstance(item, int) and not isinstance(item, bool):
            if abs(item) > 9_007_199_254_740_991:
                raise ContractError(f"{location}: integer exceeds the I-JSON range")
        elif item is not None and not isinstance(item, (str, bool, int)):
            raise ContractError(f"{location}: unsupported JSON value")

    check(value, "$")
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def content_digest(value: dict[str, Any], digest_field: str) -> str:
    payload = dict(value)
    payload.pop(digest_field, None)
    return bytes_sha256(canonical_json_bytes(payload))


def safe_relative(path: str) -> PurePosixPath:
    """Validate a portable ZIP-relative path while allowing Korean filenames."""

    if not isinstance(path, str) or not 1 <= len(path) <= 512:
        raise ContractError(f"unsafe relative path: {path!r}")
    relative = PurePosixPath(path)
    if (
        relative.is_absolute()
        or path.startswith("/")
        or re.match(r"^[A-Za-z]:", path)
        or "\\" in path
        or any(part in {"", ".", ".."} for part in path.split("/"))
        or any(unicodedata.category(character).startswith("C") for character in path)
    ):
        raise ContractError(f"unsafe relative path: {path!r}")
    return relative


def require_sha(value: Any, label: str, *, nonzero: bool = True) -> str:
    if not isinstance(value, str) or not SHA256_RE.fullmatch(value):
        raise ContractError(f"{label} must be a lowercase SHA-256")
    if nonzero and value == ZERO_SHA256:
        raise ContractError(f"{label} contains the zero placeholder")
    return value


def reject_removed_fields(value: dict[str, Any], label: str) -> None:
    found = sorted(REMOVED_FIELDS.get(label, set()) & set(value))
    if found:
        raise ContractError(f"{label} contains removed fields: {found}")


def reject_placeholders(
    value: Any,
    label: str,
    *,
    reject_zero_digest: bool = True,
) -> None:
    """Reject authoring placeholders while allowing a Runner's empty-state hash."""

    def visit(item: Any, location: str) -> None:
        if isinstance(item, dict):
            for key, nested in item.items():
                visit(nested, f"{location}.{key}")
        elif isinstance(item, list):
            for index, nested in enumerate(item):
                visit(nested, f"{location}[{index}]")
        elif isinstance(item, str):
            if "REPLACE_" in item:
                raise ContractError(f"{location} contains a REPLACE_ placeholder")
            if reject_zero_digest and re.fullmatch(
                r"(?:(?:sha256|ra-v1|mbf-v1):)?0{64}", item
            ):
                raise ContractError(f"{location} contains a zero placeholder")

    visit(value, label)


def validate_schema(
    value: dict[str, Any],
    schema_path: Path,
    label: str,
) -> None:
    schema = load_json(schema_path)
    errors = sorted(
        Draft202012Validator(
            schema,
            format_checker=FormatChecker(),
        ).iter_errors(value),
        key=lambda error: [str(part) for part in error.absolute_path],
    )
    if errors:
        messages = []
        for error in errors:
            location = ".".join(str(part) for part in error.absolute_path) or "$"
            messages.append(f"{location}: {error.message}")
        raise ContractError(f"{label} schema errors: {messages}")


def inspect_apk(apk: Path, sdk_root: Path) -> dict[str, str]:
    apkanalyzer = sdk_root / "cmdline-tools/latest/bin/apkanalyzer"
    apksigner = sdk_root / "build-tools/35.0.0/apksigner"
    if not apk.is_file():
        raise ContractError(f"APP.apk does not exist or is not a file: {apk}")
    if not apkanalyzer.is_file() or not apksigner.is_file():
        raise ContractError(
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
        signer = subprocess.run(
            [str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        ).stdout
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or error.stdout or "").strip()
        suffix = f" ({detail.splitlines()[-1]})" if detail else ""
        raise ContractError(
            "APP.apk could not be read or its signature could not be verified; "
            f"confirm that it is the final signed APK{suffix}"
        ) from error
    match = re.search(
        r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", signer
    )
    if not match:
        raise ContractError("cannot inspect APP.apk signing certificate")
    values["signing_certificate_sha256"] = match.group(1)
    values["app_release_sha256"] = file_sha256(apk)
    return values


def resolve_sdk_root(explicit: Path | None) -> Path:
    if explicit is not None:
        return explicit.resolve()
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(variable)
        if value:
            return Path(value).resolve()
    raise ContractError(
        "Android SDK path is unavailable; set ANDROID_SDK_ROOT or pass --sdk-root"
    )


def release_attestation_id(apk_identity: dict[str, str]) -> str:
    payload = {
        "app_release_sha256": apk_identity["app_release_sha256"],
        "package_name": apk_identity["package_name"],
        "signing_certificate_sha256": apk_identity["signing_certificate_sha256"],
        "version_code": apk_identity["version_code"],
        "version_name": apk_identity["version_name"],
    }
    return f"ra-v1:{bytes_sha256(canonical_json_bytes(payload))}"


def zip_entry_bytes(archive: zipfile.ZipFile, relative_path: str) -> bytes:
    expected = safe_relative(relative_path).as_posix()
    matching = [
        info
        for info in archive.infolist()
        if not info.is_dir() and info.filename == expected
    ]
    if len(matching) != 1:
        raise ContractError(
            f"SOURCE.zip must contain exactly one {expected!r}, found {len(matching)}"
        )
    return archive.read(matching[0])


def apk_mission_adapter_bytes(app_apk: Path) -> bytes:
    try:
        with zipfile.ZipFile(app_apk) as archive:
            matching = [
                info
                for info in archive.infolist()
                if not info.is_dir()
                and info.filename == MISSION_ADAPTER_APK_PATH
            ]
            if len(matching) != 1:
                raise ContractError(
                    "APP.apk must contain exactly one "
                    f"{MISSION_ADAPTER_APK_PATH}, found {len(matching)}"
                )
            value = archive.read(matching[0])
    except zipfile.BadZipFile as error:
        raise ContractError("APP.apk is not a valid APK/ZIP archive") from error
    if not value:
        raise ContractError(
            f"APP.apk {MISSION_ADAPTER_APK_PATH} must not be empty"
        )
    return value


def discover_mission_adapter(source_zip: Path) -> str:
    with zipfile.ZipFile(source_zip) as archive:
        matching = [
            info.filename
            for info in archive.infolist()
            if not info.is_dir()
            and PurePosixPath(info.filename).name == "MISSION_ADAPTER.json"
        ]
    if len(matching) != 1:
        raise ContractError(
            "SOURCE.zip must contain exactly one file named MISSION_ADAPTER.json; "
            f"found {len(matching)}. Exclude generated build/, .gradle/ and cache "
            "directories, and keep the canonical source asset only"
        )
    return safe_relative(matching[0]).as_posix()


def validate_source_zip(
    source_zip: Path,
    adapter_relative_path: str,
) -> tuple[bytes, dict[str, Any]]:
    with zipfile.ZipFile(source_zip) as archive:
        normalized: set[str] = set()
        for info in archive.infolist():
            raw_name = info.filename[:-1] if info.is_dir() else info.filename
            relative = safe_relative(raw_name)
            name = unicodedata.normalize("NFC", relative.as_posix())
            if name in normalized:
                raise ContractError(f"SOURCE.zip duplicate path: {name}")
            normalized.add(name)
            mode = info.external_attr >> 16
            if stat.S_ISLNK(mode):
                raise ContractError(f"SOURCE.zip symlink is forbidden: {name}")
        adapter_bytes = zip_entry_bytes(archive, adapter_relative_path)
    return adapter_bytes, parse_json_bytes(adapter_bytes, "MISSION_ADAPTER.json")


def matching_fields(
    expected: dict[str, Any],
    actual: dict[str, Any],
    fields: tuple[str, ...],
    label: str,
) -> None:
    mismatches = [
        field for field in fields if expected.get(field) != actual.get(field)
    ]
    if mismatches:
        raise ContractError(f"{label} differs for fields: {mismatches}")


def media_type_for(path: Path) -> str:
    try:
        return MEDIA_TYPES[path.suffix.lower()]
    except KeyError as error:
        raise ContractError(
            f"unsupported evidence file type for {path.name!r}; "
            "use .json, .txt, .png or .mp4"
        ) from error


def validate_evidence_file(path: Path) -> str:
    """Reject empty or malformed evidence before it is indexed."""

    media_type = media_type_for(path)
    if not path.is_file() or path.is_symlink():
        raise ContractError(f"evidence file is missing or unsafe: {path.name}")
    if path.stat().st_size == 0:
        raise ContractError(f"evidence file is empty: {path.name}")

    suffix = path.suffix.lower()
    if suffix == ".json":
        try:
            value = json.loads(
                path.read_text(encoding="utf-8"),
                object_pairs_hook=unique_object,
                parse_constant=lambda item: (_ for _ in ()).throw(
                    ContractError(
                        f"evidence JSON contains a non-finite number: {item}"
                    )
                ),
            )
        except (UnicodeError, json.JSONDecodeError) as error:
            raise ContractError(
                f"evidence JSON is not valid UTF-8 JSON: {path.name}"
            ) from error
        if not isinstance(value, (dict, list)):
            raise ContractError(
                f"evidence JSON must contain an object or array: {path.name}"
            )
    elif suffix == ".txt":
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeError as error:
            raise ContractError(
                f"evidence text is not valid UTF-8: {path.name}"
            ) from error
        if not text.strip():
            raise ContractError(f"evidence text is blank: {path.name}")
    elif suffix == ".png":
        with path.open("rb") as source:
            if source.read(8) != b"\x89PNG\r\n\x1a\n":
                raise ContractError(
                    f"evidence .png does not have a PNG header: {path.name}"
                )
    elif suffix == ".mp4":
        with path.open("rb") as source:
            header = source.read(32)
        if b"ftyp" not in header:
            raise ContractError(
                f"evidence .mp4 does not have an MP4 file-type header: {path.name}"
            )
    return media_type


def evidence_step_map(probe_result: dict[str, Any]) -> dict[str, list[str]]:
    mapping: dict[str, list[str]] = {}
    for step in probe_result["step_results"]:
        step_id = step["step_id"]
        references = step["receipt_ids"] + step["auto_check_evidence_ids"]
        if len(references) != len(set(references)):
            raise ContractError(f"Probe step {step_id} repeats an evidence ID")
        for evidence_id in references:
            step_ids = mapping.setdefault(evidence_id, [])
            if step_id not in step_ids:
                step_ids.append(step_id)
    declared = probe_result["evidence_ids"]
    if len(declared) != len(set(declared)):
        raise ContractError("PUBLIC_PROBE_RESULT evidence_ids contains duplicates")
    if set(mapping) != set(declared):
        raise ContractError(
            "PUBLIC_PROBE_RESULT evidence_ids differs from the IDs referenced by steps: "
            f"unreferenced={sorted(set(declared) - set(mapping))} "
            f"undeclared={sorted(set(mapping) - set(declared))}"
        )
    return {evidence_id: mapping[evidence_id] for evidence_id in declared}


def validate_external_contract(
    *,
    mission_lock: dict[str, Any],
    public_input: dict[str, Any],
    probe_result: dict[str, Any],
    app_apk: Path,
    source_zip: Path,
    public_input_path: Path,
    schema_root: Path,
    sdk_root: Path,
    adapter_relative_path: str | None = None,
    apk_identity: dict[str, str] | None = None,
) -> dict[str, Any]:
    """Validate immutable inputs shared by the finalizer and validator."""

    for label, value, schema_name in (
        ("mission_lock", mission_lock, "MISSION_LOCK.schema.json"),
        ("public_input", public_input, "PROBE_INPUT.schema.json"),
        ("probe_result", probe_result, "PROBE_RESULT.schema.json"),
    ):
        validate_schema(value, schema_root / schema_name, label)
    reject_removed_fields(mission_lock, "mission_lock")
    reject_placeholders(mission_lock, "mission_lock")
    reject_placeholders(public_input, "public_input")
    # A zero state_before_sha256 can represent the Runner's initial empty state.
    reject_placeholders(probe_result, "probe_result", reject_zero_digest=False)

    declared_result_digest = require_sha(
        probe_result.get("result_digest"), "probe_result.result_digest"
    )
    actual_result_digest = content_digest(probe_result, "result_digest")
    if declared_result_digest != actual_result_digest:
        raise ContractError(
            "probe_result.result_digest mismatch: "
            f"declared={declared_result_digest} actual={actual_result_digest}"
        )

    if apk_identity is None:
        apk_identity = inspect_apk(app_apk, sdk_root)
    if apk_identity.get("app_release_sha256") != file_sha256(app_apk):
        raise ContractError("inspected APP.apk SHA-256 differs from the APK bytes")
    for field in (
        "app_release_sha256",
        "signing_certificate_sha256",
    ):
        require_sha(apk_identity.get(field), f"APP.apk {field}")
    for field in ("package_name", "version_name", "version_code"):
        if not isinstance(apk_identity.get(field), str) or not apk_identity[field]:
            raise ContractError(f"APP.apk {field} is empty")

    expected_attestation = release_attestation_id(apk_identity)
    if public_input["release_attestation_id"] != expected_attestation:
        raise ContractError(
            "public_input.release_attestation_id does not match APP.apk identity"
        )
    probe_release = probe_result["release_binding"]
    matching_fields(
        apk_identity,
        probe_release,
        (
            "package_name",
            "version_name",
            "version_code",
            "signing_certificate_sha256",
        ),
        "Probe release binding",
    )
    if probe_release["release_attestation_id"] != expected_attestation:
        raise ContractError(
            "probe_result.release_binding.release_attestation_id does not match APP.apk"
        )
    for phase_name in ("runtime_identity_start", "runtime_identity_end"):
        if probe_result[phase_name]["release_attestation_id"] != expected_attestation:
            raise ContractError(
                f"probe_result.{phase_name}.release_attestation_id does not match APP.apk"
            )

    if adapter_relative_path is None:
        adapter_relative_path = discover_mission_adapter(source_zip)
    adapter_bytes, adapter = validate_source_zip(
        source_zip, adapter_relative_path
    )
    apk_adapter_bytes = apk_mission_adapter_bytes(app_apk)
    if apk_adapter_bytes != adapter_bytes:
        raise ContractError(
            "APP.apk assets/MISSION_ADAPTER.json differs byte-for-byte from "
            "SOURCE.zip MISSION_ADAPTER.json"
        )
    validate_schema(
        adapter, schema_root / "MISSION_ADAPTER.schema.json", "mission_adapter"
    )
    reject_removed_fields(adapter, "mission_adapter")
    reject_placeholders(adapter, "mission_adapter")
    adapter_sha256 = bytes_sha256(adapter_bytes)
    require_sha(adapter_sha256, "MISSION_ADAPTER.json SHA-256")
    if probe_result["mission_adapter_sha256"] != adapter_sha256:
        raise ContractError(
            "probe_result.mission_adapter_sha256 differs from SOURCE.zip"
        )
    source_sha256 = file_sha256(source_zip)
    require_sha(source_sha256, "SOURCE.zip SHA-256")

    if probe_result["probe_input_sha256"] != file_sha256(public_input_path):
        raise ContractError(
            "probe_result.probe_input_sha256 differs from public input raw bytes"
        )
    for field in (
        "probe_contract_profile",
        "probe_pack_id",
        "instance_id",
        "request_nonce",
        "candidate_id",
        "mission_id",
        "operator_run_token",
    ):
        if probe_result[field] != public_input[field]:
            raise ContractError(f"Probe result/input differ for {field}")
    if (
        mission_lock["candidate_id"] != probe_result["candidate_id"]
        or mission_lock["mission_id"] != probe_result["mission_id"]
    ):
        raise ContractError("Mission receipt identity differs from Probe input/result")

    input_steps = public_input["steps"]
    result_steps = probe_result["step_results"]
    if len(input_steps) != 13 or len(result_steps) != 13:
        raise ContractError("public input and result must each contain exactly 13 steps")
    for index, (input_step, result_step) in enumerate(zip(input_steps, result_steps)):
        for field in (
            "step_id",
            "event_id",
            "operation",
            "session_id",
            "virtual_time",
        ):
            if input_step[field] != result_step[field]:
                raise ContractError(f"Probe step {index} differs for {field}")
    operations = [step["operation"] for step in result_steps]
    if len(set(operations)) != 13 or set(operations) != REQUIRED_OPERATIONS:
        raise ContractError(
            "public result must contain each required operation exactly once: "
            f"missing={sorted(REQUIRED_OPERATIONS - set(operations))}"
        )

    start = probe_result["runtime_identity_start"]
    end = probe_result["runtime_identity_end"]
    if start["model_backend_frozen_id"] != end["model_backend_frozen_id"]:
        raise ContractError("Runner model configuration changed during the run")
    if end["cumulative_invocations"] < start["cumulative_invocations"]:
        raise ContractError("Runner cumulative_invocations decreased")

    return {
        "apk_identity": apk_identity,
        "release_attestation_id": expected_attestation,
        "release_id": f"app-sha256:{apk_identity['app_release_sha256']}",
        "source_archive_sha256": source_sha256,
        "mission_adapter_relative_path": adapter_relative_path,
        "mission_adapter_sha256": adapter_sha256,
        "evidence_step_map": evidence_step_map(probe_result),
        "model_configured": start["model_backend_frozen_id"] != "NOT_USED",
    }


def collect_export_files(sample_root: Path) -> dict[str, Path]:
    actual: dict[str, Path] = {}
    for path in sample_root.rglob("*"):
        if path.is_symlink():
            raise ContractError(f"SAMPLE_EXPORT symlink is forbidden: {path}")
        if path.is_dir():
            continue
        if not path.is_file():
            raise ContractError(f"SAMPLE_EXPORT contains a non-file entry: {path}")
        relative = path.relative_to(sample_root).as_posix()
        safe_relative(relative)
        if relative == "EXPORT_INDEX.json":
            continue
        if relative not in MANIFEST_TOP_FILES and not relative.startswith("evidence/"):
            raise ContractError(f"unexpected SAMPLE_EXPORT file: {relative}")
        actual[relative] = path
    return actual


def validate_sample_export(
    sample_root: Path,
    *,
    app_apk: Path,
    source_zip: Path,
    public_input_path: Path,
    schema_root: Path,
    sdk_root: Path,
    apk_identity: dict[str, str] | None = None,
) -> dict[str, Any]:
    if not sample_root.is_dir():
        raise ContractError(f"SAMPLE_EXPORT directory does not exist: {sample_root}")
    top_files = {path.name for path in sample_root.iterdir() if path.is_file()}
    missing = REQUIRED_EXPORT_FILES - top_files
    if missing:
        raise ContractError(f"SAMPLE_EXPORT missing required files: {sorted(missing)}")

    documents = {
        "mission_lock": load_json(sample_root / "MISSION_LOCK.json"),
        "runtime_identity": load_json(sample_root / "RUNTIME_IDENTITY.json"),
        "evidence_index": load_json(sample_root / "EVIDENCE_INDEX.json"),
        "export_index": load_json(sample_root / "EXPORT_INDEX.json"),
        "probe_result": load_json(sample_root / "PUBLIC_PROBE_RESULT.json"),
        "public_input": load_json(public_input_path),
    }
    export = documents["export_index"]
    declared_total = export.get("total_uncompressed_bytes")
    if (
        isinstance(declared_total, int)
        and not isinstance(declared_total, bool)
        and declared_total > 20 * 1024 * 1024
    ):
        raise ContractError("SAMPLE_EXPORT exceeds the 20 MiB limit")
    source_binding = export.get("source_binding", {})
    adapter_relative_path = source_binding.get("mission_adapter_relative_path")
    if not isinstance(adapter_relative_path, str):
        raise ContractError(
            "export_index.source_binding.mission_adapter_relative_path is missing"
        )
    external = validate_external_contract(
        mission_lock=documents["mission_lock"],
        public_input=documents["public_input"],
        probe_result=documents["probe_result"],
        app_apk=app_apk,
        source_zip=source_zip,
        public_input_path=public_input_path,
        schema_root=schema_root,
        sdk_root=sdk_root,
        adapter_relative_path=adapter_relative_path,
        apk_identity=apk_identity,
    )

    for label, schema_name in (
        ("runtime_identity", "RUNTIME_IDENTITY.schema.json"),
        ("evidence_index", "EVIDENCE_INDEX.schema.json"),
        ("export_index", "EXPORT_INDEX.schema.json"),
    ):
        validate_schema(documents[label], schema_root / schema_name, label)
        reject_removed_fields(documents[label], label)
        reject_placeholders(documents[label], label)

    probe = documents["probe_result"]
    common = {
        "candidate_id": probe["candidate_id"],
        "release_id": external["release_id"],
        "run_id": probe["run_id"],
        "operator_run_token": probe["operator_run_token"],
    }
    mission = documents["mission_lock"]
    runtime = documents["runtime_identity"]
    evidence = documents["evidence_index"]
    for label, value in (
        ("runtime_identity", runtime),
        ("evidence_index", evidence),
        ("export_index", export),
    ):
        for field, expected in common.items():
            if value[field] != expected:
                raise ContractError(f"{label}.{field} differs from immutable inputs")
    if (
        export["mission_id"] != mission["mission_id"]
        or export["candidate_id"] != mission["candidate_id"]
    ):
        raise ContractError("EXPORT_INDEX identity differs from Mission receipt")
    if export["content_class"] != "REFERENCE_SAMPLE_NOT_OFFICIAL":
        raise ContractError("EXPORT_INDEX content_class is not a reference sample")

    apk = external["apk_identity"]
    app_binding = export["app_release_binding"]
    if "release_attestation_id" in app_binding:
        raise ContractError(
            "app_release_binding must not repeat release_attestation_id"
        )
    matching_fields(
        apk,
        app_binding,
        (
            "package_name",
            "version_name",
            "version_code",
            "signing_certificate_sha256",
            "app_release_sha256",
        ),
        "EXPORT_INDEX app_release_binding",
    )
    expected_source = {
        "source_archive_sha256": external["source_archive_sha256"],
        "mission_adapter_relative_path": external[
            "mission_adapter_relative_path"
        ],
        "mission_adapter_sha256": external["mission_adapter_sha256"],
    }
    matching_fields(
        expected_source,
        source_binding,
        tuple(expected_source),
        "EXPORT_INDEX source_binding",
    )

    model_configured = external["model_configured"]
    identity_fields = {
        **apk,
        "source_archive_sha256": external["source_archive_sha256"],
        "model_configured": model_configured,
        "probe_contract_profile": probe["probe_contract_profile"],
    }
    for phase_name in ("runtime_identity_start", "runtime_identity_end"):
        runtime_phase = runtime[phase_name]
        matching_fields(
            identity_fields,
            runtime_phase,
            tuple(identity_fields),
            phase_name,
        )
        if (
            runtime_phase["cumulative_invocations"]
            != probe[phase_name]["cumulative_invocations"]
        ):
            raise ContractError(
                f"{phase_name}.cumulative_invocations differs from Runner result"
            )

    indexed: dict[str, dict[str, Any]] = {}
    indexed_paths: set[str] = set()
    for item in evidence["items"]:
        evidence_id = item["evidence_id"]
        relative = safe_relative(item["relative_path"]).as_posix()
        if (
            evidence_id in indexed
            or relative in indexed_paths
            or not relative.startswith("evidence/")
        ):
            raise ContractError(f"duplicate or invalid evidence item: {evidence_id}")
        path = sample_root / relative
        if not path.is_file() or path.is_symlink():
            raise ContractError(f"evidence file is missing or unsafe: {relative}")
        if path.stem != evidence_id:
            raise ContractError(
                f"evidence filename stem must equal evidence_id: {relative}"
            )
        if item["media_type"] != validate_evidence_file(path):
            raise ContractError(f"evidence media_type mismatch: {relative}")
        expected_steps = external["evidence_step_map"].get(evidence_id)
        if item["step_ids"] != expected_steps:
            raise ContractError(f"evidence step_ids mismatch: {evidence_id}")
        indexed[evidence_id] = item
        indexed_paths.add(relative)
    if set(indexed) != set(probe["evidence_ids"]):
        raise ContractError(
            "EVIDENCE_INDEX item IDs must equal Runner result evidence_ids"
        )

    actual_files = collect_export_files(sample_root)
    declared_files: dict[str, dict[str, Any]] = {}
    for row in export["file_manifest"]:
        relative = safe_relative(row["relative_path"]).as_posix()
        if relative == "EXPORT_INDEX.json" or relative in declared_files:
            raise ContractError(f"invalid or duplicate file_manifest path: {relative}")
        declared_files[relative] = row
    if set(declared_files) != set(actual_files):
        raise ContractError(
            "file_manifest set differs from SAMPLE_EXPORT files: "
            f"missing={sorted(set(actual_files) - set(declared_files))} "
            f"extra={sorted(set(declared_files) - set(actual_files))}"
        )
    for relative, row in declared_files.items():
        path = actual_files[relative]
        if (
            row["size_bytes"] != path.stat().st_size
            or row["sha256"] != file_sha256(path)
        ):
            raise ContractError(f"file_manifest binding mismatch: {relative}")
    total = sum(row["size_bytes"] for row in declared_files.values())
    if export["total_uncompressed_bytes"] != total:
        raise ContractError("total_uncompressed_bytes differs from file_manifest")
    if total > 20 * 1024 * 1024:
        raise ContractError("SAMPLE_EXPORT exceeds the 20 MiB limit")
    actual_evidence_paths = {
        relative for relative in actual_files if relative.startswith("evidence/")
    }
    if actual_evidence_paths != indexed_paths:
        raise ContractError("EVIDENCE_INDEX paths differ from evidence files")

    return {
        "status": "PASS",
        "candidate_id": common["candidate_id"],
        "release_id": common["release_id"],
        "run_id": common["run_id"],
        "package_name": apk["package_name"],
        "app_release_sha256": apk["app_release_sha256"],
        "source_archive_sha256": external["source_archive_sha256"],
        "sample_export_bytes": total,
        "probe_step_count": len(probe["step_results"]),
        "evidence_count": len(indexed),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("sample_export", type=Path)
    parser.add_argument("--app-apk", type=Path, default=Path("APP.apk"))
    parser.add_argument("--source-zip", type=Path, default=Path("SOURCE.zip"))
    parser.add_argument(
        "--public-input",
        type=Path,
        default=Path("PUBLIC_RUN/PROBE_INPUT.json"),
    )
    parser.add_argument(
        "--schema-root", type=Path, default=Path(__file__).resolve().parent
    )
    parser.add_argument("--sdk-root", type=Path)
    args = parser.parse_args()
    try:
        sdk_root = resolve_sdk_root(args.sdk_root)
        result = validate_sample_export(
            args.sample_export.resolve(),
            app_apk=args.app_apk.resolve(),
            source_zip=args.source_zip.resolve(),
            public_input_path=args.public_input.resolve(),
            schema_root=args.schema_root.resolve(),
            sdk_root=sdk_root,
        )
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return EXIT_PASS
    except (
        ContractError,
        OSError,
        UnicodeError,
        json.JSONDecodeError,
        zipfile.BadZipFile,
        subprocess.CalledProcessError,
    ) as error:
        print(f"CONTRACT_ERROR: {error}", file=sys.stderr)
        return EXIT_CONTRACT_ERROR
    except Exception as error:
        print(f"TOOL_ERROR: {error}", file=sys.stderr)
        return EXIT_TOOL_ERROR


if __name__ == "__main__":
    raise SystemExit(main())
